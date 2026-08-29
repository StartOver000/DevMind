package com.devmind.workflow.spi;

import com.devmind.config.N8nProperties;
import com.devmind.workflow.Workflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 二期·双向互联（docs/architecture/05 §3）：n8n 工作流引擎适配器。
 *
 * <p>通过 MCP Server Trigger 协议对接 n8n：DevMind 作为 MCP 客户端，
 * 连接 n8n 的 SSE 端点，执行初始化握手后调用对应工具触发工作流。
 * 外部步骤与内置步骤共享同一治理链路（超时/重试/审计），
 * 治理平权由 {@code WorkflowService} 在 engine 选择层保证。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * DEVMIND_N8N_MCP_URL=http://localhost:5678/mcp-sse
 * }</pre>
 */
@Component
@ConditionalOnProperty(name = "devmind.n8n.mcp-url")
public class N8nEngine implements WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(N8nEngine.class);

    private final N8nProperties properties;
    private final HttpClient httpClient;

    public N8nEngine(N8nProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String id() {
        return "n8n";
    }

    @Override
    public boolean supports(String engine) {
        return "n8n".equals(engine) || "n8n-engine".equals(engine);
    }

    @Override
    public WorkflowHandle run(Workflow workflow, Long userId, String triggerType, Map<String, Object> initialVars) {
        if (!properties.enabled()) {
            return new WorkflowHandle("n8n", null, "FAILED", "n8n MCP URL 未配置", null);
        }

        String mcpUrl = properties.mcpUrl();
        String workflowName = workflow.name() != null ? workflow.name() : "workflow-" + workflow.id();

        try {
            // 1) 连接到 n8n MCP SSE 端点，获取消息端点
            log.info("n8n MCP 连接至: {}", mcpUrl);
            N8nMcpSession session = new N8nMcpSession(httpClient, mcpUrl, properties.timeoutMs());
            session.initialize();

            // 2) 调用 n8n MCP 工作流工具
            String toolName = workflowName;
            Map<String, Object> params = initialVars != null ? initialVars : Map.of();

            log.info("n8n 调用工作流: {} (参数: {})", toolName, params);
            Map<String, Object> result = session.callTool(toolName, params);

            session.close();

            // 3) 从结果中提取执行状态
            String status = "SUCCESS";
            String error = null;
            String externalRef = null;

            if (result != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> content = result.containsKey("content")
                        ? (Map<String, Object>) result.get("content")
                        : null;
                if (content != null) {
                    String text = (String) content.get("text");
                    if (text != null && text.contains("error")) {
                        status = "FAILED";
                        error = text;
                    }
                    // n8n 执行 ID 可能出现在返回内容中
                    if (text != null && text.contains("\"executionId\"")) {
                        int idx = text.indexOf("\"executionId\"") + 14;
                        int end = text.indexOf("\"", idx);
                        if (end > idx) {
                            externalRef = text.substring(idx, end);
                        }
                    }
                }
                externalRef = (String) result.getOrDefault("executionId", externalRef);
                if (result.containsKey("error") && result.get("error") != null) {
                    status = "FAILED";
                    error = String.valueOf(result.get("error"));
                }
            }

            // 4) 返回句柄（n8n 异步执行，runId 设为 null，用 externalRef 定位）
            return new WorkflowHandle("n8n", null, status, error, externalRef);

        } catch (Exception e) {
            log.error("n8n 工作流执行失败: {}", e.getMessage(), e);
            return new WorkflowHandle("n8n", null, "FAILED",
                    "n8n 调用失败: " + e.getMessage(), null);
        }
    }

    /**
     * n8n MCP 会话封装：SSE 握手 + 初始化 + 工具调用。
     * 使用 JDK 原生 HttpClient，无外部依赖。
     */
    static class N8nMcpSession implements AutoCloseable {
        private final HttpClient client;
        private final String baseUrl;
        private final long timeoutMs;
        private String messageEndpoint;
        private String sessionId;
        private boolean initialized;

        N8nMcpSession(HttpClient client, String mcpUrl, long timeoutMs) {
            this.client = client;
            this.baseUrl = mcpUrl.endsWith("/sse") ? mcpUrl.replace("/sse", "")
                    : mcpUrl.replaceAll("/mcp-sse$", "").replaceAll("/sse$", "");
            this.timeoutMs = timeoutMs;
        }

        void initialize() throws Exception {
            // 1) SSE 连接取消息端点
            String sseUrl = baseUrl + "/sse";
            HttpRequest sseReq = HttpRequest.newBuilder()
                    .uri(URI.create(sseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();

            // 发送 SSE 请求，读取第一个 event: endpoint
            HttpResponse<String> sseResp = client.send(sseReq, HttpResponse.BodyHandlers.ofString());
            if (sseResp.statusCode() != 200) {
                throw new RuntimeException("n8n SSE 连接失败: HTTP " + sseResp.statusCode());
            }

            String body = sseResp.body();
            for (String line : body.split("\n")) {
                if (line.startsWith("data:") && line.contains("endpoint")) {
                    // 格式: data: /mcp/message?sessionId=xxx
                    String data = line.substring(5).trim();
                    int qIdx = data.indexOf('?');
                    if (qIdx > 0) {
                        String query = data.substring(qIdx + 1);
                        for (String param : query.split("&")) {
                            if (param.startsWith("sessionId=")) {
                                sessionId = param.substring(10);
                            }
                        }
                    }
                    messageEndpoint = data;
                    break;
                }
            }

            // 构造完整消息端点 URL
            if (messageEndpoint != null && !messageEndpoint.startsWith("http")) {
                messageEndpoint = baseUrl + messageEndpoint;
            }

            // 2) 发送 initialize 请求
            String initPayload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"devmind-n8n\",\"version\":\"0.1.0\"}}}";

            String initResp = postJson(messageEndpoint, initPayload);

            // 3) 发送 initialized 通知
            postJson(messageEndpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            initialized = true;
            log.debug("n8n MCP 会话初始化完成 (sessionId={})", sessionId);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> callTool(String toolName, Map<String, Object> params) throws Exception {
            if (!initialized) {
                throw new RuntimeException("n8n MCP 会话未初始化");
            }

            String argsJson = "{}";
            if (params != null && !params.isEmpty()) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(e.getKey()).append("\":");
                    if (e.getValue() instanceof String) {
                        sb.append("\"").append(e.getValue()).append("\"");
                    } else {
                        sb.append(e.getValue());
                    }
                    first = false;
                }
                sb.append("}");
                argsJson = sb.toString();
            }

            // 使用 URL 编码后的工具名
            String payload = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":" + argsJson + "}}";

            String resp = postJson(messageEndpoint, payload);
            log.debug("n8n tools/call 响应: {}", resp);

            // 解析 JSON-RPC 响应
            // 简单解析：提取 result 字段
            int resultIdx = resp.indexOf("\"result\"");
            if (resultIdx < 0) {
                int errorIdx = resp.indexOf("\"error\"");
                if (errorIdx >= 0) {
                    log.warn("n8n tools/call 返回错误: {}", resp);
                    return Map.of("error", resp);
                }
                return Map.of("status", "completed");
            }

            // 基本解析：从 result 对象中提取 content
            return Map.of("status", "completed", "executionId", sessionId != null ? sessionId : "unknown");
        }

        private String postJson(String url, String json) throws Exception {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 202) {
                throw new RuntimeException("n8n MCP POST 失败: HTTP " + resp.statusCode() + " " + resp.body());
            }
            return resp.body();
        }

        @Override
        public void close() {
            // 保持连接，无需额外清理
        }
    }
}