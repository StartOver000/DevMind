package com.devmind.workflow;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.security.PromptInjectionDetector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhook 触发端点（M3-1）：
 * 外部系统 POST /api/webhooks/{token} 即可触发工作流，无需登录。
 * - token 是工作流创建时生成的随机调用凭据；
 * - 请求体（JSON 对象）会注入为工作流初始变量 {{var}}；
 * - 默认同步执行并返回运行结果；
 * - ?async=true：后台执行立即返回 ACCEPTED（含 resultUrl，可轮询结果）；
 *   配合 callbackUrl 在完成后回调结果（M3-3）；
 * - GET /api/webhooks/{token}/runs/{runId}：异步执行的结果查询（token 鉴权）。
 */
@RestController
@SuppressWarnings("null")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowRepository repository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final PromptInjectionDetector injectionDetector;
    /** 是否对 webhook payload 做 Prompt 注入检测（P2 安全专项，默认开启） */
    private final boolean injectionCheckEnabled;
    /** 异步触发的后台执行线程池 */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public WebhookController(
            WorkflowRepository repository,
            WorkflowRunRepository runRepository,
            WorkflowExecutor executor,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            PromptInjectionDetector injectionDetector,
            @Value("${devmind.security.webhook-injection-check:true}") boolean injectionCheckEnabled
    ) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
        this.injectionDetector = injectionDetector;
        this.injectionCheckEnabled = injectionCheckEnabled;
    }

    @PreDestroy
    public void shutdown() {
        asyncExecutor.shutdownNow();
    }

    @PostMapping("/api/webhooks/{token}")
    public Map<String, Object> trigger(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @RequestParam(defaultValue = "false") boolean async,
            @RequestParam(required = false) String callbackUrl,
            @RequestBody(required = false) String body
    ) {
        Workflow workflow = repository.findByWebhookToken(token);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "无效的 webhook token");
        }
        if (!"ENABLED".equals(workflow.status())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流已停用");
        }
        if (callbackUrl != null && !callbackUrl.isBlank()
                && !callbackUrl.startsWith("http://") && !callbackUrl.startsWith("https://")) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "callbackUrl 必须是 http/https 地址");
        }
        Map<String, Object> initialVars = parseBody(body);
        // Prompt 注入检测（P2 安全专项）：payload 中命中注入模式时拒绝触发，
        // 防止恶意内容通过 {{var}} 进入工作流 LLM prompt。
        if (injectionCheckEnabled) {
            PromptInjectionDetector.Detection detection = injectionDetector.inspect(initialVars);
            if (detection.hit()) {
                log.warn("webhook 触发被拒绝：检测到疑似 Prompt 注入 (workflow={}, matches={})",
                        workflow.name(), detection.matches());
                throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                        "检测到疑似 Prompt 注入内容，已拒绝触发。命中片段: " + String.join(" / ", detection.matches()));
            }
        }
        if (async) {
            return triggerAsync(workflow, callbackUrl, initialVars);
        }
        // 同步执行（与 cron 触发一致，以创建者身份执行）
        WorkflowRun run = executor.execute(workflow, workflow.createdBy(), "webhook", initialVars);
        log.info("webhook 触发工作流 {} (runId={}, status={})", workflow.name(), run.id(), run.status());
        return Map.of(
                "runId", run.id(),
                "workflow", workflow.name(),
                "status", run.status(),
                "error", run.error() == null ? "" : run.error(),
                "totalCost", run.totalCost()
        );
    }

    /** 异步触发：先落 run 记录（供外部轮询），后台执行；完成后回调 callbackUrl（可选） */
    private Map<String, Object> triggerAsync(Workflow workflow, String callbackUrl, Map<String, Object> initialVars) {
        Long userId = workflow.createdBy();
        String workflowName = workflow.name();
        Long tenantId = workflow.tenantId();
        // 先插入 run 记录拿到 runId，外部系统即可凭 resultUrl 轮询（无需等执行完）
        Long runId = runRepository.insertRun(workflow.id(), tenantId, "webhook");
        String token = repository.findWebhookToken(tenantId, workflow.id());
        String resultUrl = "/api/webhooks/" + token + "/runs/" + runId;
        asyncExecutor.submit(() -> {
            try {
                WorkflowRun run = executor.executeExistingRun(workflow, userId, "webhook", initialVars, runId);
                log.info("webhook 异步触发工作流 {} (runId={}, status={})", workflowName, run.id(), run.status());
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    postCallback(callbackUrl, run, workflowName);
                }
            } catch (Exception ex) {
                log.error("webhook 异步执行工作流 {} 失败: {}", workflowName, ex.getMessage());
                runRepository.finishRun(runId, "FAILED", ex.getMessage() == null ? "执行失败" : ex.getMessage());
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    postCallbackError(callbackUrl, workflowName, ex.getMessage());
                }
            }
        });
        return Map.of(
                "accepted", true,
                "runId", runId,
                "workflow", workflowName,
                "status", "ACCEPTED",
                "resultUrl", resultUrl,
                "message", "工作流已在后台执行，可通过 resultUrl 轮询结果，或由 callbackUrl 回调通知"
        );
    }

    /**
     * 异步结果查询：外部系统用 webhook token + runId 轮询执行结果（无需登录）。
     * 校验 runId 归属该 token 对应的工作流；返回状态、错误与各步骤输出。
     */
    @GetMapping("/api/webhooks/{token}/runs/{runId}")
    public Map<String, Object> getRunResult(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @org.springframework.web.bind.annotation.PathVariable Long runId
    ) {
        Workflow workflow = repository.findByWebhookToken(token);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "无效的 webhook token");
        }
        WorkflowRun run = runRepository.findRun(workflow.tenantId(), runId);
        if (run == null || !workflow.id().equals(run.workflowId())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "运行记录不存在或不属于该工作流");
        }
        List<WorkflowRunStep> steps = runRepository.listSteps(runId);
        List<Map<String, Object>> stepList = new ArrayList<>();
        if (steps != null) {
            for (WorkflowRunStep s : steps) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("index", s.stepIndex());
                step.put("tool", s.toolName());
                step.put("status", s.status());
                step.put("costMs", s.costMs());
                if (s.error() != null && !s.error().isBlank()) {
                    step.put("error", s.error());
                }
                if (s.outputJson() != null && !s.outputJson().isBlank()) {
                    step.put("output", safeParseJson(s.outputJson()));
                }
                stepList.add(step);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.id());
        result.put("workflow", workflow.name());
        result.put("status", run.status());
        result.put("error", run.error() == null ? "" : run.error());
        result.put("totalCost", run.totalCost());
        result.put("steps", stepList);
        return result;
    }

    /** 把步骤输出 JSON 文本解析为对象（失败返回原文本，保证可读） */
    private Object safeParseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return json;
        }
    }

    /** 执行成功后回调结果 */
    private void postCallback(String callbackUrl, WorkflowRun run, String workflowName) {
        try {
            restClientBuilder.build().post().uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "runId", run.id(),
                            "workflow", workflowName,
                            "status", run.status(),
                            "error", run.error() == null ? "" : run.error(),
                            "totalCost", run.totalCost()
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("webhook 回调成功 (url={}, runId={})", callbackUrl, run.id());
        } catch (Exception ex) {
            log.warn("webhook 回调失败 (url={}): {}", callbackUrl, ex.getMessage());
        }
    }

    /** 执行失败时回调错误 */
    private void postCallbackError(String callbackUrl, String workflowName, String error) {
        try {
            restClientBuilder.build().post().uri(callbackUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "workflow", workflowName,
                            "status", "FAILED",
                            "error", error == null ? "执行失败" : error
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("webhook 失败回调发送失败 (url={}): {}", callbackUrl, ex.getMessage());
        }
    }

    private Map<String, Object> parseBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.isObject()) {
                return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                });
            }
        } catch (Exception ex) {
            log.warn("webhook 请求体解析失败，忽略注入变量: {}", ex.getMessage());
        }
        return null;
    }
}

