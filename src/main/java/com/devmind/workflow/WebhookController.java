package com.devmind.workflow;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Webhook 触发端点（M3-1）：
 * 外部系统 POST /api/webhooks/{token} 即可触发工作流，无需登录。
 * - token 是工作流创建时生成的随机调用凭据；
 * - 请求体（JSON 对象）会注入为工作流初始变量 {{var}}；
 * - 同步执行并返回运行结果。
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WorkflowRepository repository;
    private final WorkflowExecutor executor;
    private final ObjectMapper objectMapper;

    public WebhookController(
            WorkflowRepository repository,
            WorkflowExecutor executor,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/webhooks/{token}")
    public Map<String, Object> trigger(
            @org.springframework.web.bind.annotation.PathVariable String token,
            @RequestBody(required = false) String body
    ) {
        Workflow workflow = repository.findByWebhookToken(token);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "无效的 webhook token");
        }
        if (!"ENABLED".equals(workflow.status())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流已停用");
        }
        Map<String, Object> initialVars = parseBody(body);
        // 外部触发以创建者身份执行（与 cron 触发一致）
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
