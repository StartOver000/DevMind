package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流轻量执行器：按 steps_json 顺序调度工具，支持 {{var}} 步骤间数据传递，
 * 复用 {@link ToolRegistry}（含动态接口工具）。防重叠执行（同一工作流 RUNNING 时拒绝）。
 */
@Component
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final ToolRegistry toolRegistry;
    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    /** 并行组执行线程池（M3-2） */
    private final ExecutorService parallelExecutor = Executors.newCachedThreadPool();

    public WorkflowExecutor(
            ToolRegistry toolRegistry,
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = toolRegistry;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        parallelExecutor.shutdownNow();
    }

    /** 工作流中的一个步骤 */
    public record WorkflowStep(String tool, String paramsJson, String outputVar) {
    }

    /** 步骤组：单步=顺序执行；多步=并行执行 */
    private record StepGroup(List<WorkflowStep> steps, boolean parallel) {
    }

    /**
     * 执行工作流（同步）：逐步骤执行并记录；任一步骤失败则整次 FAILED 停止。
     *
     * @return 执行结果（含状态与错误）
     */
    public WorkflowRun execute(Workflow workflow, Long userId, String triggerType) {
        return execute(workflow, userId, triggerType, null);
    }

    /**
     * 执行工作流，支持注入初始变量（webhook 触发时把请求体注入为 {{var}}）。
     * 步骤组支持并行：steps_json 元素可为普通步骤对象，或 {"parallel": [{step}, ...]} 并行组。
     * 任一步骤失败则整次 FAILED（并行组内会等所有步骤跑完再判失败）。
     */
    public WorkflowRun execute(Workflow workflow, Long userId, String triggerType, Map<String, Object> initialVars) {
        if (runRepository.hasRunning(workflow.tenantId(), workflow.id())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流正在执行中，请稍后再试");
        }
        List<StepGroup> groups = parseSteps(workflow.stepsJson());
        if (groups == null || groups.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤为空");
        }
        Long runId = runRepository.insertRun(workflow.id(), workflow.tenantId(), triggerType);
        Map<String, Object> vars = new ConcurrentHashMap<>();
        if (initialVars != null) {
            vars.putAll(initialVars);
        }
        AtomicInteger stepIndex = new AtomicInteger(0);
        AtomicReference<String> failure = new AtomicReference<>();
        for (StepGroup group : groups) {
            if (failure.get() != null) {
                break;
            }
            if (group.parallel()) {
                runParallel(group, runId, vars, userId, workflow.name(), stepIndex, failure);
            } else {
                runStep(group.steps().get(0), stepIndex.getAndIncrement(), runId, vars, userId, workflow.name(), failure);
            }
        }
        runRepository.finishRun(runId, failure.get() == null ? "SUCCESS" : "FAILED", failure.get());
        return runRepository.findRun(workflow.tenantId(), runId);
    }

    /** 并行执行组内所有步骤，等待全部完成 */
    private void runParallel(StepGroup group, Long runId, Map<String, Object> vars, Long userId,
                             String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure) {
        List<WorkflowStep> steps = group.steps();
        int[] indexes = new int[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            indexes[i] = stepIndex.getAndIncrement();
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final int index = indexes[i];
            final WorkflowStep step = steps.get(i);
            futures.add(CompletableFuture.runAsync(
                    () -> runStep(step, index, runId, vars, userId, workflowName, failure),
                    parallelExecutor
            ));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /** 执行单个步骤并记录（失败写入 failure 引用，不抛异常） */
    private void runStep(WorkflowStep step, int index, Long runId, Map<String, Object> vars, Long userId,
                         String workflowName, AtomicReference<String> failure) {
        String input = fillTemplate(step.paramsJson(), vars);
        long start = System.currentTimeMillis();
        try {
            String output = toolRegistry.execute(step.tool(), input, userId, "workflow", runId);
            long costMs = System.currentTimeMillis() - start;
            runRepository.insertStep(runId, index, step.tool(), input, output, "SUCCESS", costMs, null);
            if (step.outputVar() != null && !step.outputVar().isBlank()) {
                vars.put(step.outputVar(), output);
            }
            log.info("工作流 {} 步骤 {} 成功 (tool={}, cost={}ms)", workflowName, index + 1, step.tool(), costMs);
        } catch (Exception ex) {
            long costMs = System.currentTimeMillis() - start;
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            runRepository.insertStep(runId, index, step.tool(), input, null, "FAILED", costMs, message);
            log.warn("工作流 {} 步骤 {} 失败 (tool={}): {}", workflowName, index + 1, step.tool(), message);
            if (failure.get() == null) {
                failure.set(message);
            }
        }
    }

    /** 解析步骤：普通对象或 {"parallel": [...]} 并行组 */
    private List<StepGroup> parseSteps(String stepsJson) {
        try {
            JsonNode array = objectMapper.readTree(stepsJson);
            if (!array.isArray() || array.isEmpty()) {
                return null;
            }
            List<StepGroup> groups = new ArrayList<>();
            for (JsonNode node : array) {
                JsonNode parallel = node.path("parallel");
                if (parallel.isArray() && !parallel.isEmpty()) {
                    List<WorkflowStep> steps = new ArrayList<>();
                    for (JsonNode item : parallel) {
                        WorkflowStep step = parseStep(item);
                        if (step != null) {
                            steps.add(step);
                        }
                    }
                    if (!steps.isEmpty()) {
                        groups.add(new StepGroup(steps, true));
                    }
                } else {
                    WorkflowStep step = parseStep(node);
                    if (step != null) {
                        groups.add(new StepGroup(List.of(step), false));
                    }
                }
            }
            return groups.isEmpty() ? null : groups;
        } catch (Exception ex) {
            log.warn("工作流步骤解析失败: {}", ex.getMessage());
            return null;
        }
    }

    private WorkflowStep parseStep(JsonNode node) {
        String tool = node.path("tool").asText("");
        if (tool.isBlank()) {
            return null;
        }
        try {
            JsonNode params = node.path("params");
            String paramsJson = (params == null || params.isMissingNode() || params.isNull())
                    ? "{}" : objectMapper.writeValueAsString(params);
            return new WorkflowStep(tool, paramsJson, node.path("output_var").asText(""));
        } catch (Exception ex) {
            log.warn("步骤参数序列化失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 模板填充：在 JSON 节点层面替换字符串字段中的 {{key}} 为上一步输出。
     * 使用 Jackson 序列化，变量值（可能是 JSON 文本）会被正确转义，保证结果 JSON 合法。
     * 变量作用域仅本次 run。
     */
    private String fillTemplate(String paramsJson, Map<String, Object> vars) {
        if (paramsJson == null || vars.isEmpty()) {
            return paramsJson;
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            transform(root, vars);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            // 参数本身非法：退化为简单字符串替换（保底）
            log.warn("工作流模板填充回退到字符串替换: {}", ex.getMessage());
            String result = paramsJson;
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
            return result;
        }
    }

    private void transform(JsonNode node, Map<String, Object> vars) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode child = node.get(key);
                if (child != null && child.isTextual()) {
                    String text = child.asText();
                    String replaced = text;
                    for (Map.Entry<String, Object> entry : vars.entrySet()) {
                        replaced = replaced.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
                    }
                    if (!replaced.equals(text)) {
                        ((ObjectNode) node).put(key, replaced);
                    }
                } else if (child != null) {
                    transform(child, vars);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                transform(item, vars);
            }
        }
    }
}
