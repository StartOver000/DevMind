package com.devmind.agent;

import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.guard.TaskMode;
import com.devmind.guard.ToolAccessDeniedException;
import com.devmind.skill.SkillMatcher;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 工具执行器（P2 拆分：工具校验/执行/超时 + Plan-Execute + 内部特判工具从 AgentService 抽出）。
 * 职责：校验并执行单个工具调用（带超时）、回填消息与轨迹、执行 plan 计划、
 * 特判执行 4 个内部工具（update_skill / load_skill / delete_memory / run_workflow）。
 */
public class AgentToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentToolExecutor.class);

    /** 工具结果回填给模型的最大字符数 */
    private static final int MAX_TOOL_RESULT_CHARS = 2000;

    private final ToolRegistry toolRegistry;
    private final ToolCallValidator toolCallValidator;
    private final MeterRegistry meterRegistry;
    private final AgentConversationStore conversationStore;
    private final AgentMemoryRepository memoryRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 工具执行线程池（配合超时熔断）：统一有界池，命名线程 + 上限 + 队列 */
    private final java.util.concurrent.ExecutorService toolExecutor =
            com.devmind.common.DevMindExecutors.toolExecutor();

    /** 可选注入：技能匹配器（load_skill 命中计数）；测试/未启用为 null */
    private SkillMatcher skillMatcher;
    /** 可选注入：技能服务（update_skill / load_skill）；测试/未启用为 null */
    private com.devmind.skill.SkillService skillService;
    /** 可选注入：工作流服务（run_workflow）；测试/未启用为 null */
    private com.devmind.workflow.WorkflowService workflowService;

    /** 工具执行结果（纯执行产物，不含消息回填，可跨线程安全传递） */
    public record ToolExecOutcome(String output, boolean ok, long costMs) {
    }

    /** 计划中的一个执行步骤 */
    private record PlanStep(String tool, String argsJson, String goal) {
    }

    public AgentToolExecutor(
            ToolRegistry toolRegistry,
            ToolCallValidator toolCallValidator,
            MeterRegistry meterRegistry,
            AgentConversationStore conversationStore,
            AgentMemoryRepository memoryRepository,
            UserService userService
    ) {
        this.toolRegistry = toolRegistry;
        this.toolCallValidator = toolCallValidator;
        this.meterRegistry = meterRegistry;
        this.conversationStore = conversationStore;
        this.memoryRepository = memoryRepository;
        this.userService = userService;
    }

    /** 技能匹配器可选注入（load_skill 命中统计用；测试环境不启用） */
    public void setSkillMatcher(SkillMatcher skillMatcher) {
        this.skillMatcher = skillMatcher;
    }

    /** 技能服务可选注入（update_skill / load_skill 用） */
    public void setSkillService(com.devmind.skill.SkillService skillService) {
        this.skillService = skillService;
    }

    /** 工作流服务可选注入（run_workflow 用） */
    public void setWorkflowService(com.devmind.workflow.WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    /** 提交工具执行为异步任务（并行执行路径：校验+超时在内部处理，结果可安全跨线程传递） */
    public java.util.concurrent.Future<ToolExecOutcome> submitExecute(
            AiModelGateway.ToolCall tc,
            Long userId
    ) {
        return submitExecute(tc, userId, TaskMode.WRITE);
    }

    /** 带任务模式的并行执行提交：只读任务下的写工具会在执行前被拒绝 */
    public java.util.concurrent.Future<ToolExecOutcome> submitExecute(
            AiModelGateway.ToolCall tc,
            Long userId,
            TaskMode taskMode
    ) {
        return toolExecutor.submit(() -> executeToolCore(tc, userId, taskMode));
    }

    /**
     * 执行单个工具调用：先校验（工具名/参数 JSON），非法回填错误不中断；合法则带超时执行。
     * 不碰共享状态（messages/trace），供并行执行使用。
     */
    public ToolExecOutcome executeToolCore(AiModelGateway.ToolCall tc, Long userId) {
        return executeToolCore(tc, userId, TaskMode.WRITE);
    }

    /** 带任务模式的单工具执行：执行期授权在 {@link ToolRegistry#execute} 内收口 */
    public ToolExecOutcome executeToolCore(AiModelGateway.ToolCall tc, Long userId, TaskMode taskMode) {
        long start = System.currentTimeMillis();
        ToolCallValidator.Validation validation = toolCallValidator.validate(tc.name(), tc.argumentsJson());
        if (!validation.valid()) {
            meterRegistry.counter("devmind.agent.tool_invalid", "reason", "invalid").increment();
            log.warn("agent 工具调用校验失败: {}", validation.error());
            return new ToolExecOutcome("{\"error\": \"工具调用无效: " + validation.error() + "\"}",
                    false, System.currentTimeMillis() - start);
        }
        try {
            java.util.concurrent.Future<String> future = toolExecutor.submit(() ->
                    toolRegistry.execute(validation.toolName(), validation.argumentsJson(), userId, "agent", null, taskMode));
            String output = future.get(AgentTools.TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            return new ToolExecOutcome(output, true, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException ex) {
            meterRegistry.counter("devmind.agent.tool_timeout").increment();
            log.warn("agent 工具 {} 执行超时（{}s）", tc.name(), AgentTools.TOOL_TIMEOUT_SECONDS);
            return new ToolExecOutcome("{\"error\": \"工具执行超时\"}",
                    false, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.ExecutionException ex) {
            // 授权拒绝在执行前抛出，被 Future 包装成 ExecutionException —— 解包成可读的拒绝结果回填模型
            if (ex.getCause() instanceof ToolAccessDeniedException denied) {
                meterRegistry.counter("devmind.agent.tool_denied").increment();
                log.warn("agent 工具 {} 被授权策略拒绝: {}", tc.name(), denied.policyId());
                return new ToolExecOutcome(deniedJson(denied), false, System.currentTimeMillis() - start);
            }
            String message = ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage();
            log.warn("agent 工具 {} 执行失败: {}", tc.name(), message);
            return new ToolExecOutcome("{\"error\": \"工具执行失败: " + message + "\"}",
                    false, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            log.warn("agent 工具 {} 执行失败: {}", tc.name(), ex.getMessage());
            return new ToolExecOutcome("{\"error\": \"工具执行失败: " + ex.getMessage() + "\"}",
                    false, System.currentTimeMillis() - start);
        }
    }

    /**
     * 把工具执行结果回填到 messages 并记录轨迹（主线程串行调用，保证消息顺序）。
     * 执行细节：{@link #executeToolCall}
     */
    public ToolTraceItem backfillTool(
            AiModelGateway.ToolCall tc,
            ToolExecOutcome outcome,
            List<java.util.Map<String, Object>> messages,
            Long conversationId,
            Consumer<ToolTraceItem> onTrace
    ) {
        String output = AgentTools.truncate(outcome.output(), MAX_TOOL_RESULT_CHARS);
        messages.add(java.util.Map.of(
                "role", "tool",
                "tool_call_id", tc.id(),
                "content", output
        ));
        ToolTraceItem item = new ToolTraceItem(
                tc.name(),
                AgentTools.truncate(tc.argumentsJson(), 120),
                outcome.ok(),
                outcome.costMs()
        );
        if (onTrace != null) {
            onTrace.accept(item);
        }
        conversationStore.persistTrace(conversationId, tc.name(),
                AgentTools.truncate(tc.argumentsJson(), 200), outcome.ok(), outcome.costMs());
        return item;
    }

    /**
     * 执行单个工具调用（校验+超时+回填），返回轨迹项。
     * 供 Plan-Execute 顺序执行步骤复用；普通多工具并行路径改用 {@link #executeToolCore} + {@link #backfillTool}。
     */
    public ToolTraceItem executeToolCall(
            AiModelGateway.ToolCall tc,
            Long userId,
            List<java.util.Map<String, Object>> messages,
            Long conversationId,
            Consumer<ToolTraceItem> onTrace
    ) {
        return executeToolCall(tc, userId, messages, conversationId, onTrace, TaskMode.WRITE);
    }

    /** 带任务模式的单工具执行 + 回填（只读任务下写工具会被拒绝并以失败结果回填） */
    public ToolTraceItem executeToolCall(
            AiModelGateway.ToolCall tc,
            Long userId,
            List<java.util.Map<String, Object>> messages,
            Long conversationId,
            Consumer<ToolTraceItem> onTrace,
            TaskMode taskMode
    ) {
        return backfillTool(tc, executeToolCore(tc, userId, taskMode), messages, conversationId, onTrace);
    }

    /**
     * Plan-Execute 计划执行器：解析 plan 参数 → 逐 step 执行（复用 {@link #executeToolCall}）→
     * 每个 step 结果回填 messages，失败不中断（供模型重规划）。返回是否全部成功。
     */
    public boolean executePlan(
            AiModelGateway.ToolCall planCall,
            Long userId,
            List<java.util.Map<String, Object>> messages,
            List<ToolTraceItem> trace,
            Consumer<ToolTraceItem> onTrace,
            Long conversationId
    ) {
        return executePlan(planCall, userId, messages, trace, onTrace, conversationId, TaskMode.WRITE);
    }

    /** 带任务模式的计划执行（计划里的每个 step 同样受执行期授权约束） */
    public boolean executePlan(
            AiModelGateway.ToolCall planCall,
            Long userId,
            List<java.util.Map<String, Object>> messages,
            List<ToolTraceItem> trace,
            Consumer<ToolTraceItem> onTrace,
            Long conversationId,
            TaskMode taskMode
    ) {
        List<PlanStep> steps = parsePlan(planCall.argumentsJson());
        if (steps == null) {
            messages.add(java.util.Map.of(
                    "role", "tool",
                    "tool_call_id", planCall.id(),
                    "content", "{\"error\": \"计划解析失败，请直接回答或逐个调用工具\"}"
            ));
            return false;
        }
        // 计划本身也计入轨迹，便于前端可视化
        ToolTraceItem planItem = new ToolTraceItem(
                AgentTools.PLAN_TOOL_NAME,
                AgentTools.truncate(planCall.argumentsJson(), 200),
                true,
                0
        );
        trace.add(planItem);
        if (onTrace != null) {
            onTrace.accept(planItem);
        }
        boolean allOk = true;
        int idx = 1;
        for (PlanStep step : steps) {
            AiModelGateway.ToolCall stepCall = new AiModelGateway.ToolCall(
                    planCall.id() + "-s" + idx++,
                    step.tool(),
                    step.argsJson() == null ? "{}" : step.argsJson()
            );
            ToolTraceItem item = executeToolCall(stepCall, userId, messages, conversationId, onTrace, taskMode);
            trace.add(item);
            if (!item.ok()) {
                allOk = false;
            }
        }
        meterRegistry.counter("devmind.agent.plan", "result", allOk ? "success" : "partial").increment();
        return allOk;
    }

    /**
     * 对话式修正技能（update_skill 内部工具）：解析 skillId + instruction，
     * 交给 SkillService 用 LLM 重写技能内容。返回执行结果（供模型告知用户）。
     */
    public ToolExecOutcome executeUpdateSkill(AiModelGateway.ToolCall tc, Long userId) {
        return executeUpdateSkill(tc, userId, TaskMode.WRITE);
    }

    /**
     * 对话式修正技能（update_skill 内部工具）——带任务模式的入口：只读任务直接拒绝。
     */
    public ToolExecOutcome executeUpdateSkill(AiModelGateway.ToolCall tc, Long userId, TaskMode taskMode) {
        if (taskMode == null || taskMode.readOnly()) {
            return internalWriteDenied(AgentTools.UPDATE_SKILL_TOOL_NAME, userId, tc.argumentsJson());
        }
        if (skillService == null) {
            return new ToolExecOutcome(
                    "{\"error\": \"技能服务不可用，请稍后再试\"}", false, 0);
        }
        long start = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
            long skillId = root.path("skillId").asLong(0);
            String instruction = root.path("instruction").asText("").trim();
            if (skillId <= 0) {
                return new ToolExecOutcome("{\"error\": \"缺少有效的 skillId\"}", false,
                        System.currentTimeMillis() - start);
            }
            if (instruction.isEmpty()) {
                return new ToolExecOutcome("{\"error\": \"缺少修改指令 instruction\"}", false,
                        System.currentTimeMillis() - start);
            }
            com.devmind.skill.SkillService.UpdateResult updated =
                    skillService.updateByInstruction(userId, skillId, instruction);
            meterRegistry.counter("devmind.agent.skill_update_total").increment();
            String summary = "已更新技能【" + updated.skill().name() + "】（ID " + updated.skill().id() + "）。\n"
                    + "【修改前】" + AgentTools.truncate(updated.oldContent(), MAX_TOOL_RESULT_CHARS) + "\n"
                    + "【修改后】" + AgentTools.truncate(updated.newContent(), MAX_TOOL_RESULT_CHARS) + "\n"
                    + "请向用户展示修改前后对比，并询问修改是否符合预期；若用户仍不满意，继续引导其说明要求后再次调用 update_skill。";
            return new ToolExecOutcome(summary, true, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new ToolExecOutcome("{\"error\": \"技能更新失败: " + message + "\"}", false,
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * 按需加载技能全文（load_skill 内部工具，渐进披露）：解析 skillId，
     * 经 SkillService.get 走可见性校验（个人技能仅本人/团队技能全员）后返回完整规范文本。
     * 命中即视为一次有效使用，自增 hit_count。
     */
    public ToolExecOutcome executeLoadSkill(AiModelGateway.ToolCall tc, Long userId) {
        if (skillService == null) {
            return new ToolExecOutcome(
                    "{\"error\": \"技能服务不可用，请稍后再试\"}", false, 0);
        }
        long start = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
            long skillId = root.path("skillId").asLong(0);
            if (skillId <= 0) {
                return new ToolExecOutcome("{\"error\": \"缺少有效的 skillId\"}", false,
                        System.currentTimeMillis() - start);
            }
            com.devmind.skill.Skill skill = skillService.get(userId, skillId);
            // 命中一次即自增（与关键词命中同一统计口径，反映技能真实使用热度）
            try {
                if (skillMatcher != null) {
                    skillMatcher.recordLoad(userService.tenantIdOf(userId), skillId);
                }
            } catch (Exception ignored) {
                // 统计失败不影响主流程
            }
            String content = AgentTools.truncate(skill.content(), MAX_TOOL_RESULT_CHARS);
            String summary = "技能【" + skill.name() + "】（ID " + skill.id() + "，"
                    + ("personal".equals(skill.scope()) ? "个人" : "团队") + "）完整规范如下：\n" + content
                    + "\n请遵循该规范完成当前任务。";
            return new ToolExecOutcome(summary, true, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new ToolExecOutcome("{\"error\": \"技能加载失败: " + message + "\"}", false,
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * 对话式删除长期记忆（delete_memory 内部工具）：解析 memoryId，
     * 删除对应用户记忆条目。返回执行结果（供模型告知用户）。
     */
    public ToolExecOutcome executeDeleteMemory(AiModelGateway.ToolCall tc, Long userId) {
        return executeDeleteMemory(tc, userId, TaskMode.WRITE);
    }

    /**
     * 对话式删除长期记忆（delete_memory 内部工具）——带任务模式的入口：只读任务直接拒绝。
     */
    public ToolExecOutcome executeDeleteMemory(AiModelGateway.ToolCall tc, Long userId, TaskMode taskMode) {
        if (taskMode == null || taskMode.readOnly()) {
            return internalWriteDenied(AgentTools.DELETE_MEMORY_TOOL_NAME, userId, tc.argumentsJson());
        }
        long start = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
            long memoryId = root.path("memoryId").asLong(0);
            if (memoryId <= 0) {
                return new ToolExecOutcome("{\"error\": \"缺少有效的 memoryId\"}", false,
                        System.currentTimeMillis() - start);
            }
            int affected = memoryRepository.deleteById(userId, memoryId);
            if (affected == 0) {
                return new ToolExecOutcome("{\"error\": \"记忆不存在或无权删除: " + memoryId + "\"}", false,
                        System.currentTimeMillis() - start);
            }
            meterRegistry.counter("devmind.agent.memory_delete_total").increment();
            String summary = "已删除长期记忆条目（ID " + memoryId + "）。请告知用户该记忆已删除，不再需要遵循。";
            return new ToolExecOutcome(summary, true, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new ToolExecOutcome("{\"error\": \"记忆删除失败: " + message + "\"}", false,
                    System.currentTimeMillis() - start);
        }
    }

    /**
     * 执行工作流（run_workflow 内部工具，技能引用资源联动执行）：解析 workflowId，
     * 交给 WorkflowService.run 复用确定性编排引擎执行（顺序/并行/条件分支），返回运行结果。
     */
    public ToolExecOutcome executeRunWorkflow(AiModelGateway.ToolCall tc, Long userId) {
        return executeRunWorkflow(tc, userId, TaskMode.WRITE);
    }

    /**
     * 执行工作流（run_workflow 内部工具）——带任务模式的入口：只读任务直接拒绝。
     */
    public ToolExecOutcome executeRunWorkflow(AiModelGateway.ToolCall tc, Long userId, TaskMode taskMode) {
        if (taskMode == null || taskMode.readOnly()) {
            return internalWriteDenied(AgentTools.RUN_WORKFLOW_TOOL_NAME, userId, tc.argumentsJson());
        }
        if (workflowService == null) {
            return new ToolExecOutcome(
                    "{\"error\": \"工作流服务不可用，请稍后再试\"}", false, 0);
        }
        long start = System.currentTimeMillis();
        try {
            JsonNode root = objectMapper.readTree(tc.argumentsJson() == null ? "{}" : tc.argumentsJson());
            long workflowId = root.path("workflowId").asLong(0);
            if (workflowId <= 0) {
                return new ToolExecOutcome("{\"error\": \"缺少有效的 workflowId\"}", false,
                        System.currentTimeMillis() - start);
            }
            com.devmind.workflow.WorkflowRun run = workflowService.run(workflowId, userId);
            meterRegistry.counter("devmind.agent.workflow_run_total").increment();
            String status = run == null ? "UNKNOWN" : run.status();
            String summary = "工作流已执行完成（workflowId=" + workflowId + "，runId="
                    + (run == null ? "-" : run.id())
                    + "，状态=" + status + (run != null && run.error() != null ? "，错误=" + run.error() : "") + "）。"
                    + "请基于该执行结果向用户汇报；若执行失败，说明失败原因。";
            return new ToolExecOutcome(summary, "SUCCESS".equals(status), System.currentTimeMillis() - start);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new ToolExecOutcome("{\"error\": \"工作流执行失败: " + message + "\"}", false,
                    System.currentTimeMillis() - start);
        }
    }

    /** 拒绝结果回填给模型：带策略号，便于模型向用户解释「为什么没做」 */
    private String deniedJson(ToolAccessDeniedException denied) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "error", "工具调用被拒绝，未执行",
                    "denied", true,
                    "policy", denied.policyId(),
                    "reason", denied.getMessage()
            ));
        } catch (Exception ex) {
            return "{\"error\": \"工具调用被拒绝\", \"denied\": true}";
        }
    }

    /**
     * 只读任务下拦截内部写工具（update_skill / delete_memory / run_workflow）。
     * 这些工具不经过 {@link ToolRegistry#execute}，因此在这里做同样的任务模式闸门，
     * 并把拒绝写进 tool_call_log（与 registry 路径同一张表、同一口径）。
     */
    private ToolExecOutcome internalWriteDenied(String toolName, Long userId, String argumentsJson) {
        meterRegistry.counter("devmind.agent.tool_denied", "tool", toolName, "policy", "TASK_SCOPE_READ_ONLY")
                .increment();
        if (toolRegistry != null) {
            toolRegistry.recordDenied(toolName, userId, "agent", "TASK_SCOPE_READ_ONLY",
                    "当前任务为只读模式，不允许写操作", argumentsJson);
        }
        log.info("只读任务拦截内部写工具: {}", toolName);
        return new ToolExecOutcome(
                "{\"error\":\"工具调用被拒绝，未执行\",\"denied\":true,"
                        + "\"policy\":\"TASK_SCOPE_READ_ONLY\",\"tool\":\"" + toolName + "\","
                        + "\"reason\":\"当前任务为只读模式，不允许写操作\"}",
                false, 0);
    }

    /** 解析 plan 工具参数为有序步骤列表；无法解析（非数组/为空/工具名为空）返回 null */
    private List<PlanStep> parsePlan(String argumentsJson) {
        try {
            JsonNode root = objectMapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            JsonNode steps = root.path("steps");
            if (!steps.isArray() || steps.isEmpty()) {
                return null;
            }
            List<PlanStep> result = new ArrayList<>();
            for (JsonNode step : steps) {
                String tool = step.path("tool").asText("");
                if (tool.isBlank()) {
                    continue;
                }
                String goal = step.path("goal").asText("");
                JsonNode args = step.path("args");
                String argsJson = (args == null || args.isMissingNode() || args.isNull())
                        ? "{}" : objectMapper.writeValueAsString(args);
                result.add(new PlanStep(tool, argsJson, goal));
            }
            return result.isEmpty() ? null : result;
        } catch (Exception ex) {
            log.warn("agent 计划解析失败: {}", ex.getMessage());
            return null;
        }
    }
}
