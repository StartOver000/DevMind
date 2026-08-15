package com.devmind.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 /chat/completions 的 SSE 流式客户端（公共工具）：
 * 主网关（ZhipuRestModelGateway）与备用网关（OpenAiCompatibleGateway）共用的 token 级流式读取。
 * 请求体 = model + messages + stream:true + 各自 extraParams；逐块回调 delta.content。
 */
public final class SseChatStreamer {

    private static final Logger log = LoggerFactory.getLogger(SseChatStreamer.class);

    private SseChatStreamer() {
    }

    /**
     * 流式调用 OpenAI 兼容端点。失败（网络/HTTP/解析中断）抛 IllegalStateException，交由上层降级链处理。
     */
    public static void streamChatCompletions(
            String baseUrl,
            String apiKey,
            String model,
            List<Map<String, Object>> messages,
            Map<String, Object> extraParams,
            ObjectMapper objectMapper,
            Consumer<String> onToken
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (extraParams != null) {
            body.putAll(extraParams);
        }
        try {
            String json = objectMapper.writeValueAsString(body);
            String normalizedBase = baseUrl.replaceAll("/+$", "");
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(normalizedBase + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(120))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json))
                    .build();
            java.net.http.HttpResponse<InputStream> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("流式接口失败 HTTP " + response.statusCode() + ": " + err);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!acceptLine(line, objectMapper, onToken)) {
                        break;
                    }
                }
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("流式读取中断: " + ex.getMessage());
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("流式请求失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("流式请求被中断");
        }
    }

    /**
     * 处理一行 SSE 数据：解析 delta.content 并回调。
     *
     * @return false 表示收到 [DONE] 应结束读取
     */
    static boolean acceptLine(String line, ObjectMapper objectMapper, Consumer<String> onToken) {
        if (line == null || !line.startsWith("data:")) {
            return true;
        }
        String payload = line.substring(5).trim();
        if (payload.isEmpty()) {
            return true;
        }
        if ("[DONE]".equals(payload)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode content = node.path("choices").path(0).path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                onToken.accept(content.asText());
            }
        } catch (Exception ex) {
            // 单条 SSE 数据解析失败（心跳/空行等）不中断流
            log.debug("SSE 数据解析跳过: {}", ex.getMessage());
        }
        return true;
    }
}
