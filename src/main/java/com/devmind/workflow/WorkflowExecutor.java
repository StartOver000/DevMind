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
    private final WorkflowConditionEvaluator conditionEvaluator;
    /** 并行组执行线程池（M3-2） */
    private final ExecutorService parallelExecutor = Executors.newCachedThreadPool();
    /** 单步工具执行超时（秒）：防止底层调用挂起导致 run 永久 RUNNING（阻塞定时/后续执行） */
    private static final long TOOL_TIMEOUT_SECONDS = 30;

    public WorkflowExecutor(
            ToolRegistry toolRegistry,
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper,
            WorkflowConditionEvaluator conditionEvaluator
    ) {
        this.toolRegistry = toolRegistry;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.conditionEvaluator = conditionEvaluator;
    }

    @PreDestroy
    public void shutdown() {
        parallelExecutor.shutdownNow();
    }

    /** 工作流中的一个步骤 */
    public record WorkflowStep(String tool, String paramsJson, String outputVar) {
    }

    /** 执行单元：顺序步骤 / 并行组 / 条件分支（if） */
    private sealed interface StepUnit permits SequentialUnit, ParallelUnit, IfUnit {
    }

    private record SequentialUnit(WorkflowStep step) implements StepUnit {
    }

    private record ParallelUnit(List<WorkflowStep> steps) implements StepUnit {
    }

    private record IfUnit(String condition, List<StepUnit> thenBranch, List<StepUnit> elseBranch) implements StepUnit {
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
     * 执行单元：普通步骤对象 | {"parallel": [...]} 并行组 | {"if": "条件", "then": [...], "else": [...]} 条件分支。
     * 任一步骤失败则整次 FAILED（并行组内会等所有步骤跑完再判失败）。
     */
    public WorkflowRun execute(Workflow workflow, Long userId, String triggerType, Map<String, Object> initialVars) {
        if (runRepository.hasRunning(workflow.tenantId(), workflow.id())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流正在执行中，请稍后再试");
        }
        List<StepUnit> units = parseUnits(workflow.stepsJson());
        if (units == null || units.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤为空");
        }
        Long runId = runRepository.insertRun(workflow.id(), workflow.tenantId(), triggerType);
        return executeExistingRun(workflow, userId, triggerType, initialVars, runId);
    }

    /**
     * 异步/Webhook 场景：run 记录已预先插入（调用方需先拿 runId 供外部轮询），
     * 这里只执行并收尾。跳过 hasRunning 检查与 insertRun（占位即视为进行中）。
     */
    public WorkflowRun executeExistingRun(Workflow workflow, Long userId, String triggerType,
                                          Map<String, Object> initialVars, Long runId) {
        List<StepUnit> units = parseUnits(workflow.stepsJson());
        if (units == null || units.isEmpty()) {
            runRepository.finishRun(runId, "FAILED", "工作流步骤为空");
            return runRepository.findRun(workflow.tenantId(), runId);
        }
        Map<String, Object> vars = new ConcurrentHashMap<>();
        if (initialVars != null) {
            vars.putAll(initialVars);
        }
        AtomicInteger stepIndex = new AtomicInteger(0);
        AtomicReference<String> failure = new AtomicReference<>();
        executeUnits(units, runId, vars, userId, workflow.name(), stepIndex, failure);
        runRepository.finishRun(runId, failure.get() == null ? "SUCCESS" : "FAILED", failure.get());
        return runRepository.findRun(workflow.tenantId(), runId);
    }

    /** 按顺序执行单元列表（条件分支递归） */
    private void executeUnits(List<StepUnit> units, Long runId, Map<String, Object> vars, Long userId,
                              String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure) {
        for (StepUnit unit : units) {
            if (failure.get() != null) {
                break;
            }
            if (unit instanceof SequentialUnit sequential) {
                runStep(sequential.step(), stepIndex.getAndIncrement(), runId, vars, userId, workflowName, failure);
            } else if (unit instanceof ParallelUnit parallel) {
                runParallel(parallel.steps(), runId, vars, userId, workflowName, stepIndex, failure);
            } else if (unit instanceof IfUnit ifUnit) {
                boolean matched = conditionEvaluator.evaluate(ifUnit.condition(), vars);
                log.info("工作流 {} 条件 [{}] → {}", workflowName, ifUnit.condition(), matched ? "THEN" : "ELSE");
                executeUnits(matched ? ifUnit.thenBranch() : ifUnit.elseBranch(),
                        runId, vars, userId, workflowName, stepIndex, failure);
            }
        }
    }

    /** 并行执行所有步骤，等待全部完成 */
    private void runParallel(List<WorkflowStep> steps, Long runId, Map<String, Object> vars, Long userId,
                             String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure) {
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
            // 提交到线程池执行并带超时（防止底层调用挂起导致 run 永久 RUNNING，阻塞定时/后续执行）
            String output = executeWithTimeout(step.tool(), input, userId, runId, start);
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

    /**
     * 带超时的工具执行：提交线程池 + Future.get(超时)。
     * 超时（或线程被中断）时取消任务并抛异常 → 步骤标记 FAILED，run 正常结束，
     * 不会像之前那样无限阻塞导致 run 永久 RUNNING。
     */
    private String executeWithTimeout(String tool, String input, Long userId, Long runId, long start) {
        java.util.concurrent.Future<String> future = parallelExecutor.submit(() -> {
            try {
                return toolRegistry.execute(tool, input, userId, "workflow", runId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        try {
            return future.get(TOOL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            future.cancel(true);
            throw new IllegalStateException("工具执行超时（超过 " + TOOL_TIMEOUT_SECONDS + " 秒）: " + tool);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw cause instanceof RuntimeException r ? r : new RuntimeException(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("工具执行被中断: " + tool);
        }
    }

    /** 解析步骤数组 → 执行单元列表（支持 if 递归） */
    private List<StepUnit> parseUnits(String stepsJson) {
        try {
            JsonNode array = objectMapper.readTree(stepsJson);
            if (!array.isArray() || array.isEmpty()) {
                return null;
            }
            List<StepUnit> units = new ArrayList<>();
            for (JsonNode node : array) {
                StepUnit unit = parseUnit(node);
                if (unit != null) {
                    units.add(unit);
                }
            }
            return units.isEmpty() ? null : units;
        } catch (Exception ex) {
            log.warn("工作流步骤解析失败: {}", ex.getMessage());
            return null;
        }
    }

    /** 解析单个元素：if 分支 / parallel 并行组 / 普通步骤 */
    private StepUnit parseUnit(JsonNode node) {
        // 条件分支：{"if": "{{x}} > 100", "then": [...], "else": [...]}
        String condition = node.path("if").asText("");
        if (!condition.isBlank()) {
            JsonNode thenNode = node.path("then");
            JsonNode elseNode = node.path("else");
            List<StepUnit> thenUnits = parseUnitsFromNode(thenNode);
            List<StepUnit> elseUnits = parseUnitsFromNode(elseNode);
            if (thenUnits == null && elseUnits == null) {
                return null;
            }
            return new IfUnit(condition,
                    thenUnits == null ? new ArrayList<>() : thenUnits,
                    elseUnits == null ? new ArrayList<>() : elseUnits);
        }
        // 并行组：{"parallel": [{step}, ...]}
        JsonNode parallel = node.path("parallel");
        if (parallel.isArray() && !parallel.isEmpty()) {
            List<WorkflowStep> steps = new ArrayList<>();
            for (JsonNode item : parallel) {
                WorkflowStep step = parseStep(item);
                if (step != null) {
                    steps.add(step);
                }
            }
            return steps.isEmpty() ? null : new ParallelUnit(steps);
        }
        // 普通步骤
        WorkflowStep step = parseStep(node);
        return step == null ? null : new SequentialUnit(step);
    }

    private List<StepUnit> parseUnitsFromNode(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<StepUnit> units = new ArrayList<>();
        for (JsonNode item : node) {
            StepUnit unit = parseUnit(item);
            if (unit != null) {
                units.add(unit);
            }
        }
        return units.isEmpty() ? null : units;
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
