package com.devmind.workflow;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Webhook 触发端点（M3-1）：
 * 外部系统 POST /api/webhooks/{token} 即可触发工作流，无需登录。
 * - token 是工作流创建时生成的随机调用凭据；
 * - 请求体（JSON 对象）会注入为工作流初始变量 {{var}}；
 * - 默认同步执行并返回运行结果；
 * - ?async=true：后台执行立即返回 ACCEPTED；配合 callbackUrl 在完成后回调结果（M3-3）。
 */
@RestController
@SuppressWarnings("null")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowRepository repository;
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    /** 异步触发的后台执行线程池 */
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public WebhookController(
            WorkflowRepository repository,
            WorkflowExecutor executor,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder
    ) {
        this.repository = repository;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
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

    /** 异步触发：后台执行，立即返回 ACCEPTED；完成后回调 callbackUrl（可选） */
    private Map<String, Object> triggerAsync(Workflow workflow, String callbackUrl, Map<String, Object> initialVars) {
        Long userId = workflow.createdBy();
        String workflowName = workflow.name();
        asyncExecutor.submit(() -> {
            try {
                WorkflowRun run = executor.execute(workflow, userId, "webhook", initialVars);
                log.info("webhook 异步触发工作流 {} (runId={}, status={})", workflowName, run.id(), run.status());
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    postCallback(callbackUrl, run, workflowName);
                }
            } catch (Exception ex) {
                log.error("webhook 异步执行工作流 {} 失败: {}", workflowName, ex.getMessage());
                if (callbackUrl != null && !callbackUrl.isBlank()) {
                    postCallbackError(callbackUrl, workflowName, ex.getMessage());
                }
            }
        });
        return Map.of(
                "accepted", true,
                "workflow", workflowName,
                "status", "ACCEPTED",
                "message", "工作流已在后台执行，结果将通过回调通知或运行记录查询获取"
        );
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

