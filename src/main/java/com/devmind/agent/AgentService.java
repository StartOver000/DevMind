package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.chat.dto.Reference;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.LocalRagAnswerer;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.skill.SkillMatcher;
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 研发问答 Agent：ReAct 式执行循环。
 * 模型自主调用工具（kb_search / sql_diagnose 等）→ 工具结果回填 → 再决策，直到输出最终回答。
 * 韧性：模型调用走 {@link ChatRouter}（超时/熔断/降级）；单工具失败回填错误不中断；
 * 全链路失败降级为本地 RAG 回答。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 最大工具调用轮数（防死循环；多步任务 + 重试后仍应在此内收尾） */
    private static final int MAX_TOOL_ROUNDS = 5;
    /** 工具结果回填给模型的最大字符数 */
    private static final int MAX_TOOL_RESULT_CHARS = 2000;
    /** 每次会话结束自动提取长期记忆的最大条数 */
    private static final int MAX_EXTRACT_ITEMS = 5;
    private static final int MAX_MEMORY_KEY_CHARS = 20;
    private static final int MAX_MEMORY_VALUE_CHARS = 100;

    private static final String SYSTEM_PROMPT = """
            你是 DevMind 研发助手 Agent。根据用户问题自主决定调用哪些工具获取信息，再给出最终回答。

            可调用工具：
            - plan：多步任务的执行计划（≥3 个独立步骤时提交 plan，含 goal 与有序 steps，每步一个工具）。
            - update_skill：用户指出某条技能规范不对/需要调整时，调用本工具修改技能内容。
            - load_skill：按需加载技能完整规范。system 中【可参考技能清单】只列了名称/描述；
              当当前任务确实涉及清单中某项时，先调用 load_skill（skillId 取清单中的 ID）拿到完整规范再执行。
            - delete_memory：用户要求忘记/删除某条长期记忆（system 中【用户长期记忆】里带【记忆 ID x】的条目）时，调用本工具删除该条记忆。
            - kb_search：检索研发知识库，获取与问题相关的文档片段（含相似度分数）。
            - kb_info：查询当前用户可访问的知识库列表。
            - doc_list：查询指定知识库内的文档清单（文件名、状态、文本块数）。
            - sql_diagnose：分析 SQL 执行计划，识别风险并给出优化建议。
            - usage_query：查询当前用户的模型用量与估算费用。

            规则：
            1. 先调用需要的工具，拿到结果后再回答；不要编造工具结果。
            2. 多维度问题（如 SQL 性能 + 优化方案）可依次调用多个工具；
               需要 ≥3 个独立步骤时，先提交 plan 计划，再按步骤执行。
            3. 工具结果不足以回答时，明确说明，可换关键词再次检索。
            4. 最终回答需引用来源文件名，格式 [来源: 文件名]。
            5. 已执行过工具的轮次：基于工具返回结果直接总结，不要重复调用相同工具。
            6. 若当前任务参考了技能规范（system 中带【技能 ID x：名称】），且用户指出该规范有
               问题/要修改（如"这个技能不对"、"把第 2 步改成先查 A"），调用 update_skill
               （skillId 取规范中的 ID，instruction 为用户原话）。修改后必须向用户展示
               "修改前 → 修改后"对比，并询问是否符合预期；用户仍不满意则继续调整。
            7. 若 system 中只有【可参考技能清单】（未给全文），而当前任务确实与清单中某项
               技能相关（如用户明确要求按某项规范执行），必须先用 load_skill 加载其全文并遵循，
               不要凭名称猜测技能内容。
            8. 若用户要求忘记/删除某条长期记忆（如"忘掉这条""删掉刚才那个偏好"，对应
               system 中【用户长期记忆】里带【记忆 ID x】的条目），调用 delete_memory
               （memoryId 取该条目的 ID），删除后告知用户。
            """;

    /** 会话结束后自动提取用户长期偏好的提取器提示词 */
    private static final String MEMORY_EXTRACT_PROMPT = """
            你是一个用户偏好提取器。根据用户与 AI 助手的对话，提取用户明确表达的长期偏好或关键事实。
            规则：
            1. 只提取用户明确表达的内容（如使用的技术栈、语言偏好、回答风格要求、常用工具等），不要猜测或推断。
            2. 每行一条，格式：key: value。key 简短（不超过 20 字），value 为具体内容（不超过 100 字）。
            3. 没有可提取的内容时输出空。
            """;

    /**
     * Plan-Execute：模型为多步任务提交计划的内部工具名（不注册到 ToolRegistry，由本类特判处理）。
     * 模型多步任务（如：先检索知识库 → 再诊断 SQL → 再汇总）时调用 plan 提交有序步骤；单步任务无需使用。
     */
    public static final String PLAN_TOOL_NAME = "plan";
    private static final String PLAN_TOOL_DESC = "为多步任务制定执行计划：当任务需要多个步骤（如先检索再诊断再总结）时，按执行顺序提交 steps；每个 step 调用一个工具并说明目标。单步任务无需使用本工具。";
    private static final String PLAN_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "goal": { "type": "string", "description": "任务目标" },
                "steps": {
                  "type": "array",
                  "description": "有序执行步骤，每步调用一个工具",
                  "items": {
                    "type": "object",
                    "properties": {
                      "tool": { "type": "string", "description": "要调用的工具名，如 kb_search" },
                      "args": { "type": "object", "description": "工具参数" },
                      "goal": { "type": "string", "description": "本步骤目标" }
                    },
                    "required": ["tool", "goal"]
                  }
                }
              },
              "required": ["goal", "steps"]
            }
            """;

    /**
     * update_skill：对话式修正技能的内部工具（不注册 ToolRegistry，由本类特判处理）。
     * 用户指出某条技能规范需要调整时，模型调用本工具提交 skillId 与修改指令。
     */
    public static final String UPDATE_SKILL_TOOL_NAME = "update_skill";
    private static final String UPDATE_SKILL_TOOL_DESC = "修正一条技能规范：当用户指出某条技能（system 中带【技能 ID x：名称】）有问题/需要调整时，传入该技能的 ID 和用户的修改要求，本工具会更新技能内容。";
    private static final String UPDATE_SKILL_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillId": { "type": "integer", "description": "要修改的技能 ID" },
                "instruction": { "type": "string", "description": "用户的修改意见/要求（原话即可）" }
              },
              "required": ["skillId", "instruction"]
            }
            """;

    /**
     * load_skill：按需加载技能全文的内部工具（渐进披露）。
     * system 中【可参考技能清单】只含名称/描述；模型判断当前任务涉及某项时调用本工具获取完整规范。
     */
    public static final String LOAD_SKILL_TOOL_NAME = "load_skill";
    private static final String LOAD_SKILL_TOOL_DESC = "加载一项技能（Skill）的完整规范文本：当 system 中的【可参考技能清单】里某项技能与当前任务相关时，传入其 ID 获取完整规范并遵循。";
    private static final String LOAD_SKILL_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "skillId": { "type": "integer", "description": "要加载的技能 ID" }
              },
              "required": ["skillId"]
            }
            """;

    /**
     * delete_memory：对话式删除长期记忆的内部工具（Guide-52 记忆升级，对标 Coze）。
     * 用户要求"忘掉/删除某条记忆"时，模型调用本工具删除对应记忆条目。
     */
    public static final String DELETE_MEMORY_TOOL_NAME = "delete_memory";
    private static final String DELETE_MEMORY_TOOL_DESC = "删除一条用户长期记忆：当用户要求忘记/删除某条已记录的用户偏好或记忆（system 中【用户长期记忆】里的条目）时，传入其 ID 删除该条记忆。";
    private static final String DELETE_MEMORY_TOOL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "memoryId": { "type": "integer", "description": "要删除的记忆条目 ID" }
              },
              "required": ["memoryId"]
            }
            """;

    private final ChatRouter chatRouter;
    private final ToolRegistry toolRegistry;
    private final AgentConversationRepository conversationRepository;
    private final AgentMemoryRepository memoryRepository;
    private final UserService userService;
    private final ModelUsageService modelUsageService;
    private final AiModelGateway modelGateway;
    private final RetrievalService retrievalService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;
    private final ToolCallValidator toolCallValidator;
    private final ToolAccessService toolAccessService;
    private final ChatFileStore chatFileStore;
    /** 技能匹配器（Guide-51）：可选注入，测试/无技能时不启用 */
    private SkillMatcher skillMatcher;
    /** 技能服务（对话式修正 update_skill）：可选注入，测试环境不启用 */
    private com.devmind.skill.SkillService skillService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 单工具执行超时（秒） */
    private static final int TOOL_TIMEOUT_SECONDS = 20;

    /** 计划中的一个执行步骤 */
    private record PlanStep(String tool, String argsJson, String goal) {
    }
    /** 工具执行线程池（配合超时熔断） */
    private final java.util.concurrent.ExecutorService toolExecutor =
            java.util.concurrent.Executors.newCachedThreadPool();

    public AgentService(
            ChatRouter chatRouter,
            ToolRegistry toolRegistry,
            AgentConversationRepository conversationRepository,
            AgentMemoryRepository memoryRepository,
            UserService userService,
            ModelUsageService modelUsageService,
            AiModelGateway modelGateway,
            RetrievalService retrievalService,
            KnowledgeBaseService knowledgeBaseService,
            DevMindProperties properties,
            MeterRegistry meterRegistry,
            ToolCallValidator toolCallValidator,
            ToolAccessService toolAccessService,
            ChatFileStore chatFileStore
    ) {
        this.chatRouter = chatRouter;
        this.toolRegistry = toolRegistry;
        this.conversationRepository = conversationRepository;
        this.memoryRepository = memoryRepository;
        this.userService = userService;
        this.modelUsageService = modelUsageService;
        this.modelGateway = modelGateway;
        this.retrievalService = retrievalService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.toolCallValidator = toolCallValidator;
        this.toolAccessService = toolAccessService;
        this.chatFileStore = chatFileStore;
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    /** 技能匹配器可选注入（避免破坏既有测试构造器；生产环境由 Spring 自动注入） */
    @Autowired(required = false)
    public void setSkillMatcher(SkillMatcher skillMatcher) {
        this.skillMatcher = skillMatcher;
    }

    /** 技能服务可选注入（对话式修正 update_skill 用） */
    @Autowired(required = false)
    public void setSkillService(com.devmind.skill.SkillService skillService) {
        this.skillService = skillService;
    }

    /** 匹配当前请求命中的技能规范（Guide-51 P1）：未注入 matcher 或未命中时返回空 */
    /** 技能匹配（渐进披露）：返回命中全文 + 可加载清单 */
    private com.devmind.skill.SkillMatcher.MatchResult matchSkills(String question, Long userId) {
        if (skillMatcher == null) {
            return new com.devmind.skill.SkillMatcher.MatchResult(List.of(), List.of());
        }
        try {
            Long tenantId = userService.tenantIdOf(userId);
            return skillMatcher.match(question, tenantId, userId);
        } catch (Exception ex) {
            log.warn("技能匹配失败: {}", ex.getMessage());
            return new com.devmind.skill.SkillMatcher.MatchResult(List.of(), List.of());
        }
    }

    public AgentChatResponse chat(AgentChatRequest request, Long userId) {
        return doChat(request, userId, null, null);
    }

    /**
     * Agent 流式入口：与 {@link #chat} 逻辑一致，但每次工具执行完成时通过
     * {@code onTrace} 实时回调（供 SSE 推送工具轨迹），模型原生思考过程通过
     * {@code onThinking} 实时回调（供 SSE 推送 reasoning），最终答案由调用方分片推送。
     */
    public AgentChatResponse chatStream(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace
    ) {
        return doChat(request, userId, onTrace, null);
    }

    /** 流式入口 + 思考过程回调（reasoning 实时推送） */
    public AgentChatResponse chatStream(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace,
            Consumer<String> onThinking
    ) {
        return doChat(request, userId, onTrace, onThinking);
    }

    private AgentChatResponse doChat(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace,
            Consumer<String> onThinking
    ) {
        userService.requireUser(userId);
        String rawQuestion = request.question() == null ? "" : request.question().trim();
        if (rawQuestion.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "问题不能为空");
        }
        // 上传文件注入：fileIds 对应的文本作为分析上下文拼到问题前
        String question = enrichWithFiles(rawQuestion, request.fileIds(), userId);

        Long conversationId = resolveConversation(request.conversationId(), question, userId);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        // 技能注入（Guide-51 渐进披露）：关键词命中注入全文；其余仅给清单，按需 load_skill
        com.devmind.skill.SkillMatcher.MatchResult skillMatch = matchSkills(question, userId);
        List<String> skillParts = new ArrayList<>();
        if (skillMatch.injectFull() != null && !skillMatch.injectFull().isEmpty()) {
            skillParts.add("【相关技能规范】以下技能与当前任务直接相关，请遵循（与通用规则冲突以本条为准）：\n"
                    + String.join("\n---\n", skillMatch.injectFull()));
        }
        if (skillMatch.catalog() != null && !skillMatch.catalog().isEmpty()) {
            skillParts.add("【可参考技能清单】以下技能可能相关。若当前任务确实涉及某项，调用 load_skill 加载其完整规范后遵循：\n"
                    + String.join("\n", skillMatch.catalog()));
        }
        if (!skillParts.isEmpty()) {
            messages.add(Map.of("role", "system", "content", String.join("\n\n", skillParts)));
        }
        // 长期记忆：注入用户偏好（跨会话保留）；带 ID 供 delete_memory 定位
        List<com.devmind.agent.dto.MemoryItem> memory = memoryRepository.listByUser(userId);
        if (memory != null && !memory.isEmpty()) {
            String memoryText = memory.stream()
                    .map(m -> "【记忆 ID " + m.id() + "】" + m.key() + ": " + m.value())
                    .collect(java.util.stream.Collectors.joining("；"));
            messages.add(Map.of("role", "system", "content", "【用户长期记忆】" + memoryText + "（回答时可参考这些用户偏好；用户要求删除某条时用 delete_memory）"));
        }
        // 多轮记忆：加载该会话最近的历史消息作为上下文
        if (conversationId != null && conversationId > 0) {
            List<AgentMessage> history = conversationRepository.listMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 6);
                for (int i = from; i < history.size(); i++) {
                    AgentMessage historyItem = history.get(i);
                    messages.add(Map.of("role", historyItem.role(), "content", historyItem.content()));
                }
            }
        }
        if (request.history() != null) {
            for (AgentChatRequest.HistoryItem item : request.history()) {
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", question));

        List<ToolTraceItem> trace = new ArrayList<>();
        // 工具可见性：按用户授权过滤（内置工具全部可用，动态接口工具需被授权）
        Long tenantId = userService.tenantIdOf(userId);
        Set<String> accessible = toolAccessService.accessibleToolNames(tenantId, userId);
        List<AiModelGateway.ToolSpec> tools = new ArrayList<>(toolRegistry.all().stream()
                .filter(tool -> accessible.contains(tool.name()))
                .map(tool -> new AiModelGateway.ToolSpec(tool.name(), tool.description(), tool.parametersJsonSchema()))
                .toList());
        // 注入内部计划工具（Plan-Execute）：模型多步任务时先提交计划，由本类特判执行
        tools.add(new AiModelGateway.ToolSpec(PLAN_TOOL_NAME, PLAN_TOOL_DESC, PLAN_TOOL_SCHEMA));
        // 注入技能修正工具（对话式调整）：模型发现用户要改技能时特判执行
        tools.add(new AiModelGateway.ToolSpec(UPDATE_SKILL_TOOL_NAME, UPDATE_SKILL_TOOL_DESC, UPDATE_SKILL_TOOL_SCHEMA));
        // 注入技能加载工具（渐进披露）：system 只给技能清单，模型按需加载全文
        tools.add(new AiModelGateway.ToolSpec(LOAD_SKILL_TOOL_NAME, LOAD_SKILL_TOOL_DESC, LOAD_SKILL_TOOL_SCHEMA));
        // 注入记忆删除工具（可追溯记忆）：用户要求忘记某条记忆时特判执行
        tools.add(new AiModelGateway.ToolSpec(DELETE_MEMORY_TOOL_NAME, DELETE_MEMORY_TOOL_DESC, DELETE_MEMORY_TOOL_SCHEMA));

        try {
            // 计划失败后是否还能引导模型重规划（限 1 次，避免死循环）
            boolean replanAllowed = true;
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                AiModelGateway.ChatResult result = chatRouter.chatWithTools(SYSTEM_PROMPT, messages, tools);
                if (result == null) {
                    throw new IllegalStateException("模型网关返回空结果");
                }
                recordUsage(userId, result, question);
                // 模型原生思考过程（reasoning）：实时透传给调用方（SSE thinking 事件）
                String reasoning = result.reasoning();
                if (reasoning != null && !reasoning.isBlank() && onThinking != null) {
                    onThinking.accept(reasoning);
                }
                List<AiModelGateway.ToolCall> toolCalls = result.toolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String answer = result.content() == null ? "" : result.content();
                    saveMessages(conversationId, rawQuestion, answer);
                    extractMemory(userId, rawQuestion, answer);
                    return new AgentChatResponse(conversationId, answer, List.of(), trace);
                }
                // 回填 assistant（含 tool_calls）
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.content() == null ? "" : result.content());
                assistantMsg.put("tool_calls", toolCalls.stream()
                        .map(tc -> Map.of(
                                "id", tc.id(),
                                "type", "function",
                                "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson())
                        ))
                        .toList());
                messages.add(assistantMsg);
                // 工具执行：plan 走计划执行器（顺序执行）；普通工具并发执行（结果按原顺序回填）
                boolean hasPlan = false;
                boolean planAllOk = true;
                List<AiModelGateway.ToolCall> parallelCalls = new ArrayList<>();
                Map<AiModelGateway.ToolCall, java.util.concurrent.Future<ToolExecOutcome>> futures = new LinkedHashMap<>();
                for (AiModelGateway.ToolCall tc : toolCalls) {
                    if (PLAN_TOOL_NAME.equals(tc.name())) {
                        hasPlan = true;
                        if (!executePlan(tc, userId, messages, trace, onTrace, conversationId)) {
                            planAllOk = false;
                        }
                    } else if (UPDATE_SKILL_TOOL_NAME.equals(tc.name())) {
                        // 对话式修正技能：特判执行，结果回填
                        ToolExecOutcome outcome = executeUpdateSkill(tc, userId);
                        trace.add(backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else if (LOAD_SKILL_TOOL_NAME.equals(tc.name())) {
                        // 按需加载技能全文：特判执行，结果回填
                        ToolExecOutcome outcome = executeLoadSkill(tc, userId);
                        trace.add(backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else if (DELETE_MEMORY_TOOL_NAME.equals(tc.name())) {
                        // 对话式删除长期记忆：特判执行，结果回填
                        ToolExecOutcome outcome = executeDeleteMemory(tc, userId);
                        trace.add(backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } else {
                        parallelCalls.add(tc);
                        futures.put(tc, toolExecutor.submit(() -> executeToolCore(tc, userId)));
                    }
                }
                // 并发工具：按 tool_calls 原顺序等待结果并回填，保证 tool 消息与调用一一对应
                for (AiModelGateway.ToolCall tc : parallelCalls) {
                    try {
                        ToolExecOutcome outcome = futures.get(tc)
                                .get(TOOL_TIMEOUT_SECONDS + 5L, java.util.concurrent.TimeUnit.SECONDS);
                        trace.add(backfillTool(tc, outcome, messages, conversationId, onTrace));
                    } catch (Exception ex) {
                        // 理论不可达：executeToolCore 内部已捕获所有异常并返回失败结果
                        ToolExecOutcome outcome = new ToolExecOutcome(
                                "{\"error\": \"工具执行异常: " + ex.getMessage() + "\"}", false, 0);
                        trace.add(backfillTool(tc, outcome, messages, conversationId, onTrace));
                    }
                }
                // 计划中有步骤失败：提示模型重新规划（仅一次），下一轮模型可提交新计划或直接回答
                if (hasPlan && !planAllOk && replanAllowed) {
                    meterRegistry.counter("devmind.agent.replan_total").increment();
                    log.warn("agent 计划执行有步骤失败，提示模型重新规划");
                    messages.add(Map.of(
                            "role", "system",
                            "content", "【提示】上一步计划中有步骤执行失败（见工具返回的错误信息）。请重新规划一个更合理的计划，或直接基于已有信息回答。"
                    ));
                    replanAllowed = false;
                }
            }
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "Agent 工具调用轮数超限");
        } catch (Exception ex) {
            log.warn("agent 链路失败，降级本地 RAG: {}", ex.getMessage());
            AgentChatResponse fallback = fallbackToLocalRag(conversationId, rawQuestion, userId, trace);
            // 携带上传文件时降级：明确告知文件未参与分析，避免误导
            if (request.fileIds() != null && !request.fileIds().isEmpty()) {
                fallback = new AgentChatResponse(
                        fallback.conversationId(),
                        "（提示：大模型暂不可用，上传的文件未参与分析，以下为知识库兜底回答）\n\n" + fallback.answer(),
                        fallback.references(),
                        fallback.toolTrace()
                );
            }
            saveMessages(conversationId, rawQuestion, fallback.answer());
            return fallback;
        }
    }

    /** 把上传文件的文本注入为问题上下文（文件分析场景） */
    private String enrichWithFiles(String question, List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        for (String fileId : fileIds) {
            ChatFileStore.ChatFile file = chatFileStore.get(fileId, userId);
            if (file != null) {
                sb.append("【上传文件：").append(file.fileName()).append("】\n")
                        .append(file.text()).append("\n\n");
            }
        }
        if (sb.isEmpty()) {
            return question;
        }
        return sb + "用户问题：" + question;
    }

    /** 工具执行结果（纯执行产物，不含消息回填，可跨线程安全传递） */
    private record ToolExecOutcome(String output, boolean ok, long costMs) {
    }

    /**
     * 执行单个工具调用：先校验（工具名/参数 JSON），非法回填错误不中断；合法则带超时执行。
     * 不碰共享状态（messages/trace），供并行执行使用。
     */
    private ToolExecOutcome executeToolCore(AiModelGateway.ToolCall tc, Long userId) {
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
                    toolRegistry.execute(validation.toolName(), validation.argumentsJson(), userId));
            String output = future.get(TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            return new ToolExecOutcome(output, true, System.currentTimeMillis() - start);
        } catch (java.util.concurrent.TimeoutException ex) {
            meterRegistry.counter("devmind.agent.tool_timeout").increment();
            log.warn("agent 工具 {} 执行超时（{}s）", tc.name(), TOOL_TIMEOUT_SECONDS);
            return new ToolExecOutcome("{\"error\": \"工具执行超时\"}",
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
    private ToolTraceItem backfillTool(
            AiModelGateway.ToolCall tc,
            ToolExecOutcome outcome,
            List<Map<String, Object>> messages,
            Long conversationId,
            Consumer<ToolTraceItem> onTrace
    ) {
        String output = truncate(outcome.output(), MAX_TOOL_RESULT_CHARS);
        messages.add(Map.of(
                "role", "tool",
                "tool_call_id", tc.id(),
                "content", output
        ));
        ToolTraceItem item = new ToolTraceItem(
                tc.name(),
                truncate(tc.argumentsJson(), 120),
                outcome.ok(),
                outcome.costMs()
        );
        if (onTrace != null) {
            onTrace.accept(item);
        }
        persistTrace(conversationId, tc.name(), truncate(tc.argumentsJson(), 200), outcome.ok(), outcome.costMs());
        return item;
    }

    /**
     * 执行单个工具调用（校验+超时+回填），返回轨迹项。
     * 供 Plan-Execute 顺序执行步骤复用；普通多工具并行路径改用 {@link #executeToolCore} + {@link #backfillTool}。
     */
    private ToolTraceItem executeToolCall(
            AiModelGateway.ToolCall tc,
            Long userId,
            List<Map<String, Object>> messages,
            Long conversationId,
            Consumer<ToolTraceItem> onTrace
    ) {
        return backfillTool(tc, executeToolCore(tc, userId), messages, conversationId, onTrace);
    }

    /**
     * Plan-Execute 计划执行器：解析 plan 参数 → 逐 step 执行（复用 {@link #executeToolCall}）→
     * 每个 step 结果回填 messages，失败不中断（供模型重规划）。返回是否全部成功。
     */
    private boolean executePlan(
            AiModelGateway.ToolCall planCall,
            Long userId,
            List<Map<String, Object>> messages,
            List<ToolTraceItem> trace,
            Consumer<ToolTraceItem> onTrace,
            Long conversationId
    ) {
        List<PlanStep> steps = parsePlan(planCall.argumentsJson());
        if (steps == null) {
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", planCall.id(),
                    "content", "{\"error\": \"计划解析失败，请直接回答或逐个调用工具\"}"
            ));
            return false;
        }
        // 计划本身也计入轨迹，便于前端可视化
        ToolTraceItem planItem = new ToolTraceItem(
                PLAN_TOOL_NAME,
                truncate(planCall.argumentsJson(), 200),
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
            ToolTraceItem item = executeToolCall(stepCall, userId, messages, conversationId, onTrace);
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
    private ToolExecOutcome executeUpdateSkill(AiModelGateway.ToolCall tc, Long userId) {
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
                    + "【修改前】" + truncate(updated.oldContent(), MAX_TOOL_RESULT_CHARS) + "\n"
                    + "【修改后】" + truncate(updated.newContent(), MAX_TOOL_RESULT_CHARS) + "\n"
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
    private ToolExecOutcome executeLoadSkill(AiModelGateway.ToolCall tc, Long userId) {
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
                skillMatcher.recordLoad(userService.tenantIdOf(userId), skillId);
            } catch (Exception ignored) {
                // 统计失败不影响主流程
            }
            String content = truncate(skill.content(), MAX_TOOL_RESULT_CHARS);
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
    private ToolExecOutcome executeDeleteMemory(AiModelGateway.ToolCall tc, Long userId) {
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

    /**
     * 会话结束后自动从对话中提取用户长期偏好，合并写入 agent_memory。
     * 完全静默：模型不可用（429/熔断/降级）或输出解析失败都不影响主流程。
     */
    private void extractMemory(Long userId, String question, String answer) {
        if (userId == null) {
            return;
        }
        try {
            String dialogue = "用户：" + (question == null ? "" : question)
                    + "\n助手：" + (answer == null ? "" : answer);
            AiModelGateway.ChatResult result = chatRouter.chat(MEMORY_EXTRACT_PROMPT, dialogue);
            String content = result == null ? "" : result.content();
            if (content == null || content.isBlank()) {
                return;
            }
            int count = 0;
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                int idx = trimmed.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (key.length() > MAX_MEMORY_KEY_CHARS) {
                    key = key.substring(0, MAX_MEMORY_KEY_CHARS);
                }
                if (value.length() > MAX_MEMORY_VALUE_CHARS) {
                    value = value.substring(0, MAX_MEMORY_VALUE_CHARS);
                }
                memoryRepository.upsert(userId, key, value);
                count++;
                if (count >= MAX_EXTRACT_ITEMS) {
                    break;
                }
            }
            if (count > 0) {
                log.info("agent 自动提取长期记忆 {} 条（user={}）", count, userId);
            }
        } catch (Exception ex) {
            log.warn("agent 长期记忆自动提取失败（不影响主流程）: {}", ex.getMessage());
        }
    }

    /** 查询会话消息（历史展示） */
    public List<AgentMessage> messages(Long conversationId, Long userId) {
        userService.requireUser(userId);
        if (conversationId == null || !conversationRepository.existsForUser(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversationRepository.listMessages(conversationId);
    }

    /** 查询会话工具调用轨迹（历史展示） */
    public List<ToolTraceItem> trace(Long conversationId, Long userId) {
        userService.requireUser(userId);
        if (conversationId == null || !conversationRepository.existsForUser(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversationRepository.listTraces(conversationId);
    }

    /** 查询长期记忆 */
    public List<com.devmind.agent.dto.MemoryItem> memory(Long userId) {
        userService.requireUser(userId);
        return memoryRepository.listByUser(userId);
    }

    /** 更新长期记忆（全量覆盖） */
    public void updateMemory(MemoryUpdateRequest request, Long userId) {
        userService.requireUser(userId);
        memoryRepository.replaceAll(userId, request == null ? List.of() : request.items());
    }

    /** 删除单条长期记忆（可追溯记忆：按 id 删除；不存在时抛错提示） */
    public void deleteMemory(Long id, Long userId) {
        userService.requireUser(userId);
        if (id == null || id <= 0) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "缺少有效的记忆 ID");
        }
        int affected = memoryRepository.deleteById(userId, id);
        if (affected == 0) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "记忆不存在或无权删除: " + id);
        }
    }

    private void saveMessages(Long conversationId, String question, String answer) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        try {
            conversationRepository.saveMessage(conversationId, "user", question);
            conversationRepository.saveMessage(conversationId, "assistant", answer == null ? "" : answer);
        } catch (Exception ex) {
            log.warn("agent 消息持久化失败: {}", ex.getMessage());
        }
    }

    private void persistTrace(Long conversationId, String tool, String args, boolean ok, long costMs) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        try {
            conversationRepository.saveTrace(conversationId, tool, args, ok, costMs);
        } catch (Exception ex) {
            log.warn("agent 轨迹持久化失败: {}", ex.getMessage());
        }
    }

    private Long resolveConversation(Long conversationId, String question, Long userId) {
        if (conversationId != null && conversationId > 0) {
            return conversationId;
        }
        String title = truncate(question, 100);
        Long id = conversationRepository.create(userId, title);
        return id == null ? 0L : id;
    }

    private AgentChatResponse fallbackToLocalRag(
            Long conversationId,
            String question,
            Long userId,
            List<ToolTraceItem> trace
    ) {
        meterRegistry.counter("devmind.rag.degraded").increment();
        try {
            Long kbId = firstAccessibleKnowledgeBase(userId);
            if (kbId == null) {
                return new AgentChatResponse(conversationId, "Agent 暂不可用，且没有可访问的知识库。", List.of(), trace);
            }
            List<RetrievalResult> results = searchWithFallback(kbId, question);
            if (results.isEmpty()) {
                return new AgentChatResponse(conversationId, "知识库中没有找到足够相关内容。", List.of(), trace);
            }
            String answer = LocalRagAnswerer.answer(question, results) + "\n\n（本地降级模式）";
            List<Reference> refs = results.stream()
                    .map(r -> new Reference(
                            r.documentId(),
                            r.documentName(),
                            r.chunkId(),
                            truncate(r.content(), 300),
                            round(r.similarityScore()),
                            r.metadata()
                    ))
                    .toList();
            return new AgentChatResponse(conversationId, answer, refs, trace);
        } catch (Exception ex) {
            log.error("agent 本地降级失败", ex);
            return new AgentChatResponse(conversationId, "Agent 暂不可用，请稍后重试。", List.of(), trace);
        }
    }

    private Long firstAccessibleKnowledgeBase(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        if (list.items() == null || list.items().isEmpty()) {
            return null;
        }
        // 优先选有文档的库，避免检索空库
        return list.items().stream()
                .filter(item -> "ENABLED".equals(item.status()) && item.documentCount() != null && item.documentCount() > 0)
                .findFirst()
                .map(item -> item.id())
                .orElseGet(() -> list.items().stream()
                        .filter(item -> "ENABLED".equals(item.status()))
                        .findFirst()
                        .map(item -> item.id())
                        .orElse(null));
    }

    private List<RetrievalResult> searchWithFallback(Long kbId, String question) {
        try {
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            return retrievalService.searchHybrid(
                    kbId,
                    vector,
                    question,
                    properties.retrievalTopK(),
                    properties.retrievalMinScore(),
                    properties.retrievalVectorWeight(),
                    properties.retrievalKeywordWeight(),
                    properties.retrievalHybridEnabled()
            );
        } catch (Exception ex) {
            log.warn("agent 降级检索 embedding 不可用，走关键词检索: {}", ex.getMessage());
            return retrievalService.searchByKeywords(kbId, question, properties.retrievalTopK(), Map.of());
        }
    }

    private void recordUsage(Long userId, AiModelGateway.ChatResult result, String question) {
        try {
            modelUsageService.record(
                    userId,
                    "agent",
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    question,
                    result.content() == null ? "" : result.content()
            );
        } catch (Exception ex) {
            log.warn("agent 用量记录失败: {}", ex.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
