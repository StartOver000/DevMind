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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;

/**
 * 工作流轻量执行器：按 steps_json 顺序调度工具，支持 {{var}} 步骤间数据传递，
 * 复用 {@link ToolRegistry}（含动态接口工具）。
 * 并发排队：同一工作流并发触发时排队串行（webhook 风暴场景从"拒绝"改为"等待"），不同工作流互不影响。
 */
@Component
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final ToolRegistry toolRegistry;
    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowConditionEvaluator conditionEvaluator;
    /** 并行组执行线程池（M3-2） */
    /** 并行组执行线程池：统一有界池，并行步骤有限 */
    private final ExecutorService parallelExecutor = com.devmind.common.DevMindExecutors.workflowParallel();
    /** 单步工具执行超时（秒）：防止底层调用挂起导致 run 永久 RUNNING（阻塞定时/后续执行） */
    private static final long TOOL_TIMEOUT_SECONDS = 30;
    /** 同一工作流允许的最大排队触发数（超出直接拒绝，防止 webhook 风暴无限堆积） */
    private static final int MAX_QUEUED_PER_WORKFLOW = 10;
    /** 排队等待许可超时（秒）：前一个执行太久时不再干等 */
    private static final long QUEUE_WAIT_SECONDS = 60;
    /** 步骤级重试次数（P2-3 分级重试）：仅瞬时故障（超时/模型 5xx/限流）重试，其余直接失败 */
    private static final int STEP_RETRY_MAX = 2;
    /** 步骤级重试退避（毫秒） */
    private static final long STEP_RETRY_BACKOFF_MS = 300;
    /** 每工作流执行闸门：permit=1 保证同工作流串行，waiting 统计排队深度 */
    private final ConcurrentHashMap<Long, WorkflowGate> gates = new ConcurrentHashMap<>();

    private static final class WorkflowGate {
        final Semaphore permit = new Semaphore(1);
        final AtomicInteger waiting = new AtomicInteger();
    }

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

    /** 工作流中的一个步骤；outputAppend=true 时结果追加到 outputVar（字段级 Append，G6） */
    public record WorkflowStep(String tool, String paramsJson, String outputVar, boolean outputAppend) {
        public WorkflowStep(String tool, String paramsJson, String outputVar) {
            this(tool, paramsJson, outputVar, false);
        }
    }

    /** 执行单元：顺序步骤 / 并行组 / 条件分支（if）/ 循环（loop，P2-2） */
    private sealed interface StepUnit permits SequentialUnit, ParallelUnit, IfUnit, LoopUnit {
    }

    private record SequentialUnit(WorkflowStep step) implements StepUnit {
    }

    private record ParallelUnit(List<WorkflowStep> steps) implements StepUnit {
    }

    private record IfUnit(String condition, List<StepUnit> thenBranch, List<StepUnit> elseBranch) implements StepUnit {
    }

    /**
     * 循环单元（P2-2）：条件驱动循环 + 最大轮次上限（安全边界，防死循环）。
     * 语义：条件为真时执行 body，每轮后重新求值；达到 maxRounds 无条件退出。
     */
    private record LoopUnit(String condition, int maxRounds, List<StepUnit> body) implements StepUnit {
    }

    /** 循环默认最大轮次（未显式配置时的安全边界） */
    private static final int DEFAULT_LOOP_MAX_ROUNDS = 5;
    /** 循环配置的最大轮次上限（防配置过大导致失控） */
    private static final int LOOP_MAX_ROUNDS_CAP = 100;

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
        // 并发排队：同一工作流并发触发时排队串行，而不是直接拒绝（修复 webhook 风暴/定时重叠时
        // 下游高频失败）。排队上限与等待超时兜底（防无限堆积/挂死）；不同工作流互不影响；
        // 跨进程场景由下方 hasRunning（DB 层）兜底拒绝。
        WorkflowGate gate = gates.computeIfAbsent(workflow.id(), id -> new WorkflowGate());
        int queued = gate.waiting.incrementAndGet();
        try {
            if (queued > MAX_QUEUED_PER_WORKFLOW) {
                throw new ApiException(ErrorCode.WORKFLOW_BUSY,
                        "工作流排队已满（同工作流最多 " + MAX_QUEUED_PER_WORKFLOW + " 个等待），请稍后再试");
            }
            boolean acquired = gate.permit.tryAcquire(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ApiException(ErrorCode.WORKFLOW_BUSY,
                        "工作流排队等待超时（" + QUEUE_WAIT_SECONDS + " 秒），请稍后再试");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.WORKFLOW_BUSY, "排队等待被中断");
        } finally {
            gate.waiting.decrementAndGet();
        }
        try {
            if (runRepository.hasRunning(workflow.tenantId(), workflow.id())) {
                throw new ApiException(ErrorCode.WORKFLOW_BUSY, "工作流正在执行中，请稍后再试");
            }
            List<StepUnit> units = parseUnits(workflow.stepsJson());
            if (units == null || units.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤为空");
            }
            Long runId = runRepository.insertRun(workflow.id(), workflow.tenantId(), triggerType);
            return executeExistingRun(workflow, userId, triggerType, initialVars, runId);
        } finally {
            gate.permit.release();
        }
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

    /**
     * 循环执行（P2-2）：条件为真执行 body，每轮后重评估；达到 maxRounds 无条件退出。
     * 防死循环三件套：继续条件（condition）+ 退出条件（condition 为假）+ 安全边界（maxRounds）。
     */
    private void executeLoop(LoopUnit loopUnit, Long runId, Map<String, Object> vars, Long userId,
                             String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure) {
        int rounds = 0;
        while (rounds < loopUnit.maxRounds()) {
            if (failure.get() != null) {
                return;
            }
            boolean continueLoop = conditionEvaluator.evaluate(loopUnit.condition(), vars);
            if (!continueLoop) {
                log.info("工作流 {} 循环条件不再满足，退出循环（rounds={}）", workflowName, rounds);
                return;
            }
            rounds++;
            log.info("工作流 {} 循环第 {} 轮（max={}）", workflowName, rounds, loopUnit.maxRounds());
            executeUnits(loopUnit.body(), runId, vars, userId, workflowName, stepIndex, failure);
        }
        log.warn("工作流 {} 循环达到最大轮次上限 {}，强制退出（防死循环）", workflowName, loopUnit.maxRounds());
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
            } else if (unit instanceof LoopUnit loopUnit) {
                executeLoop(loopUnit, runId, vars, userId, workflowName, stepIndex, failure);
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

    /**
     * State 更新策略（G6）：默认 Replace（覆盖）；output_append=true 时字段级 Append（追加）。
     * 用 ConcurrentHashMap.compute 保证读改写原子；Append 语义下并发写同一变量按到达顺序拼接，
     * 顺序由并行调度决定——这是八股里 Append 更新策略的固有不确定性（与 Replace 不同）。
     */
    private void writeOutput(Map<String, Object> vars, String outputVar, String output, boolean append) {
        if (!append) {
            vars.put(outputVar, output);
            return;
        }
        vars.compute(outputVar, (k, existing) -> existing == null ? output : existing + "\n" + output);
    }

    /** 执行单个步骤并记录（失败写入 failure 引用，不抛异常）；瞬时故障自动重试（P2-3） */
    private void runStep(WorkflowStep step, int index, Long runId, Map<String, Object> vars, Long userId,
                         String workflowName, AtomicReference<String> failure) {
        String input = fillTemplate(step.paramsJson(), vars);
        long start = System.currentTimeMillis();
        Exception lastError = null;
        for (int attempt = 0; attempt <= STEP_RETRY_MAX; attempt++) {
            try {
                // 提交到线程池执行并带超时（防止底层调用挂起导致 run 永久 RUNNING，阻塞定时/后续执行）
                String output = executeWithTimeout(step.tool(), input, userId, runId, start);
                long costMs = System.currentTimeMillis() - start;
                runRepository.insertStep(runId, index, step.tool(), input, output, "SUCCESS", costMs, null);
                if (step.outputVar() != null && !step.outputVar().isBlank()) {
                    writeOutput(vars, step.outputVar(), output, step.outputAppend());
                }
                log.info("工作流 {} 步骤 {} 成功 (tool={}, cost={}ms)", workflowName, index + 1, step.tool(), costMs);
                return;
            } catch (Exception ex) {
                lastError = ex;
                boolean retryable = isRetryable(ex);
                if (retryable && attempt < STEP_RETRY_MAX) {
                    log.warn("工作流 {} 步骤 {} 瞬时失败将重试 (attempt={}/{}): {}",
                            workflowName, index + 1, attempt + 1, STEP_RETRY_MAX,
                            ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    try {
                        Thread.sleep(STEP_RETRY_BACKOFF_MS * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    break;
                }
            }
        }
        // 重试耗尽或不可重试：记录 FAILED 并终止本次 run
        Exception ex = lastError;
        long costMs = System.currentTimeMillis() - start;
        String message = ex == null || ex.getMessage() == null
                ? (ex == null ? "未知错误" : ex.getClass().getSimpleName()) : ex.getMessage();
        runRepository.insertStep(runId, index, step.tool(), input, null, "FAILED", costMs, message);
        log.warn("工作流 {} 步骤 {} 失败 (tool={}): {}", workflowName, index + 1, step.tool(), message);
        if (failure.get() == null) {
            failure.set(message);
        }
    }

    /**
     * 瞬时故障判定（P2-3 分级重试）：模型调用失败（5xx/网关）、限流（429）可重试；
     * 网络抖动（连接重置等）可重试。超时挂起不重试（每次重试都要再等完整超时，放大阻塞）；
     * 业务 4xx（认证/参数/越权）、非幂等写操作失败不重试——重试无用或有资损风险。
     */
    private boolean isRetryable(Exception ex) {
        if (ex instanceof ApiException api) {
            ErrorCode code = api.getCode();
            return code == ErrorCode.MODEL_CALL_FAILED
                    || code == ErrorCode.RATE_LIMITED
                    || code == ErrorCode.WORKFLOW_BUSY;
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        // 快速返回的网络瞬时错误可重试；超时（挂起）不重试
        return msg.contains("Connection reset")
                || msg.contains("ConnectException")
                || msg.contains("connect timed out");
    }

    /**
     * 断点恢复（P2-3）：对已失败的 run，从首个失败步骤续跑（跳过已成功步骤）。
     * 已成功步骤的副作用不重复执行；失败点及之后重新执行。
     */
    public WorkflowRun resume(Workflow workflow, Long userId, String triggerType,
                              Map<String, Object> initialVars, Long runId) {
        List<WorkflowRunStep> steps = runRepository.listSteps(runId);
        if (steps.isEmpty()) {
            // 无历史步骤记录：等价于从头执行
            return executeExistingRun(workflow, userId, triggerType, initialVars, runId);
        }
        // 已成功步骤 index 集合（跳过），首个失败 index 作为续跑起点
        java.util.Set<Integer> successIndexes = new java.util.HashSet<>();
        int resumeFrom = Integer.MAX_VALUE;
        for (WorkflowRunStep s : steps) {
            if ("SUCCESS".equals(s.status())) {
                successIndexes.add(s.stepIndex());
            } else if ("FAILED".equals(s.status()) && s.stepIndex() < resumeFrom) {
                resumeFrom = s.stepIndex();
            }
        }
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
        executeUnitsResuming(units, runId, vars, userId, workflow.name(),
                stepIndex, failure, successIndexes, resumeFrom);
        runRepository.finishRun(runId, failure.get() == null ? "SUCCESS" : "FAILED", failure.get());
        return runRepository.findRun(workflow.tenantId(), runId);
    }

    /** 断点恢复专用：跳过程序中已成功步骤的 index，仅从失败点续跑（其余同 executeUnits） */
    private void executeUnitsResuming(List<StepUnit> units, Long runId, Map<String, Object> vars, Long userId,
                                      String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure,
                                      java.util.Set<Integer> successIndexes, int resumeFrom) {
        for (StepUnit unit : units) {
            if (failure.get() != null) {
                break;
            }
            if (unit instanceof SequentialUnit sequential) {
                runStepResuming(sequential.step(), stepIndex.getAndIncrement(), runId, vars, userId,
                        workflowName, failure, successIndexes, resumeFrom);
            } else if (unit instanceof ParallelUnit parallel) {
                runParallelResuming(parallel.steps(), runId, vars, userId, workflowName, stepIndex, failure,
                        successIndexes, resumeFrom);
            } else if (unit instanceof IfUnit ifUnit) {
                boolean matched = conditionEvaluator.evaluate(ifUnit.condition(), vars);
                executeUnitsResuming(matched ? ifUnit.thenBranch() : ifUnit.elseBranch(),
                        runId, vars, userId, workflowName, stepIndex, failure, successIndexes, resumeFrom);
            } else if (unit instanceof LoopUnit loopUnit) {
                executeLoop(loopUnit, runId, vars, userId, workflowName, stepIndex, failure);
            }
        }
    }

    private void runParallelResuming(List<WorkflowStep> steps, Long runId, Map<String, Object> vars, Long userId,
                                     String workflowName, AtomicInteger stepIndex, AtomicReference<String> failure,
                                     java.util.Set<Integer> successIndexes, int resumeFrom) {
        int[] indexes = new int[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            indexes[i] = stepIndex.getAndIncrement();
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final int index = indexes[i];
            final WorkflowStep step = steps.get(i);
            futures.add(CompletableFuture.runAsync(
                    () -> runStepResuming(step, index, runId, vars, userId, workflowName, failure, successIndexes, resumeFrom),
                    parallelExecutor
            ));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void runStepResuming(WorkflowStep step, int index, Long runId, Map<String, Object> vars, Long userId,
                                 String workflowName, AtomicReference<String> failure,
                                 java.util.Set<Integer> successIndexes, int resumeFrom) {
        // 已成功步骤跳过（不重复执行，避免非幂等副作用）；失败点之前无条件跳过
        if (index < resumeFrom || successIndexes.contains(index)) {
            return;
        }
        runStep(step, index, runId, vars, userId, workflowName, failure);
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
        // 循环：{"loop": "条件", "maxRounds": N, "steps": [...]}
        String loopCondition = node.path("loop").asText("");
        if (!loopCondition.isBlank()) {
            JsonNode stepsNode = node.path("steps");
            List<StepUnit> body = parseUnitsFromNode(stepsNode);
            if (body == null) {
                return null;
            }
            int maxRounds = node.path("maxRounds").asInt(DEFAULT_LOOP_MAX_ROUNDS);
            maxRounds = Math.min(Math.max(maxRounds, 1), LOOP_MAX_ROUNDS_CAP);
            return new LoopUnit(loopCondition, maxRounds, body);
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
            return new WorkflowStep(tool, paramsJson, node.path("output_var").asText(""),
                    node.path("output_append").asBoolean(false));
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
                    String replaced = replaceVars(text, vars);
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

    /**
     * 变量替换：支持 {{key}} 与 {{key.field}}（从上一步 JSON 输出提取字段）。
     * 接口工具编排场景必须支持字段提取——上一步返回 {"id":"cus_xxx",...}，
     * 下一步参数要 {{customer.id}} 才能拿到 ID 传下去（修复前只能整段替换，无法取字段）。
     */
    private static final java.util.regex.Pattern VAR_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)\\}\\}");

    private String replaceVars(String text, Map<String, Object> vars) {
        java.util.regex.Matcher m = VAR_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String expr = m.group(1);
            String[] parts = expr.split("\\.");
            Object value = vars.get(parts[0]);
            if (value != null && parts.length > 1) {
                value = extractField(value, parts, 1);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    value == null ? m.group(0) : String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 从 JSON 字符串/JsonNode 按路径取叶子字段的文本值（缺失返回 null） */
    private Object extractField(Object json, String[] parts, int startIdx) {
        JsonNode node = null;
        if (json instanceof String s) {
            try {
                node = objectMapper.readTree(s);
            } catch (Exception ex) {
                return null;
            }
        } else if (json instanceof JsonNode jn) {
            node = jn;
        } else {
            return null;
        }
        for (int i = startIdx; i < parts.length && node != null; i++) {
            node = node.path(parts[i]);
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.isContainerNode() ? node.toString() : node.asText();
    }
}
