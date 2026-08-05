package com.devmind.ai;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

    /** 连续失败多少次后触发熔断 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** 熔断打开后多久尝试恢复 */
    private static final long CIRCUIT_OPEN_MS = 60_000L;
    /** 备用模型（常为免费模型，有瞬时限流）最大调用尝试次数 */
    private static final int FALLBACK_MAX_ATTEMPTS = 3;

    private final AiModelGateway primaryGateway;
    private final RestClient.Builder restClientBuilder;
    private final DevMindProperties properties;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final Timer chatTimer;
    private final Counter failedCounter;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    public ChatRouter(
            AiModelGateway primaryGateway,
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.primaryGateway = primaryGateway;
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.chatTimer = Timer.builder("devmind.model.calls.duration")
                .description("模型聊天调用耗时")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("devmind.model.calls.failed")
                .description("模型聊天调用失败次数")
                .register(meterRegistry);
    }

    public AiModelGateway.ChatResult chat(String systemPrompt, String userPrompt) {
        if (isCircuitOpen()) {
            // 熔断打开：不再重试主/备模型，快速失败，让上层立即走本地 RAG 降级
            throw new ApiException(
                    ErrorCode.MODEL_CALL_FAILED,
                    "模型服务连续失败，已进入熔断降级，请稍后重试"
            );
        }
        try {
            AiModelGateway.ChatResult result = chatTimer.record(() -> primaryGateway.chat(systemPrompt, userPrompt));
            consecutiveFailures.set(0);
            return result;
        } catch (Exception primaryError) {
            failedCounter.increment();
            if (properties.modelFallbackBaseUrl().isBlank()) {
                return fail(primaryError);
            }
            try {
                AiModelGateway.ChatResult fallback = chatTimer.record(() -> callFallback(systemPrompt, userPrompt));
                consecutiveFailures.set(0);
                return fallback;
            } catch (Exception fallbackError) {
                failedCounter.increment();
                return fail(new ApiException(
                        ErrorCode.MODEL_CALL_FAILED,
                        "模型调用失败: " + fallbackError.getMessage()
                ));
            }
        }
    }

    /**
     * Agent 工具调用：优先主网关，失败自动切备用模型（备用支持 function calling 时可用），
     * 全部失败才走 fail() 统一熔断策略。
     */
    public AiModelGateway.ChatResult chatWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<AiModelGateway.ToolSpec> tools
    ) {
        if (isCircuitOpen()) {
            throw new ApiException(
                    ErrorCode.MODEL_CALL_FAILED,
                    "模型服务连续失败，已进入熔断降级，请稍后重试"
            );
        }
        try {
            AiModelGateway.ChatResult result = chatTimer.record(() -> primaryGateway.chatWithTools(systemPrompt, messages, tools));
            consecutiveFailures.set(0);
            return result;
        } catch (Exception primaryError) {
            failedCounter.increment();
            if (properties.modelFallbackBaseUrl().isBlank()) {
                return fail(primaryError);
            }
            try {
                AiModelGateway.ChatResult fallback = chatTimer.record(() -> callFallbackWithTools(systemPrompt, messages, tools));
                consecutiveFailures.set(0);
                return fallback;
            } catch (Exception fallbackError) {
                failedCounter.increment();
                return fail(new ApiException(
                        ErrorCode.MODEL_CALL_FAILED,
                        "模型调用失败: " + fallbackError.getMessage()
                ));
            }
        }
    }

    /** 主备均失败：429 限流立即熔断（持续状态，重试无意义）；其他错误累计达到阈值后熔断。 */
    private AiModelGateway.ChatResult fail(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        boolean rateLimited = message.contains("429") || message.contains("Too Many");
        if (rateLimited || consecutiveFailures.incrementAndGet() >= CIRCUIT_FAILURE_THRESHOLD) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
        }
        if (error instanceof ApiException apiError) {
            throw apiError;
        }
        throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型调用失败: " + error.getMessage());
    }

    private boolean isCircuitOpen() {
        long openUntil = circuitOpenUntil;
        if (openUntil == 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= openUntil) {
            // 熔断时间到，半开：重置计数，放一个请求试探
            circuitOpenUntil = 0L;
            consecutiveFailures.set(0);
            return false;
        }
        return true;
    }

    private AiModelGateway.ChatResult callFallback(String systemPrompt, String userPrompt) {
        RestClient client = restClientBuilder.baseUrl(properties.modelFallbackBaseUrl()).build();
        Map<String, Object> body = Map.of(
                "model", properties.modelFallbackChatModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.2
        );
        FallbackChatResponse response = callFallbackWithRetry(() -> client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + secretCipher.resolve(properties.modelFallbackApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FallbackChatResponse.class));
        return new AiModelGateway.ChatResult(
                response.choices().get(0).message().content(),
                properties.modelFallbackChatModel(),
                null,
                null
        );
    }

    /**
     * 备用模型工具调用：备用链路（如 OpenRouter Nemotron）支持 function calling 时，
     * 主模型 429/故障下 Agent 仍可完成工具编排。请求体含 tools 定义。
     */
    @SuppressWarnings("null")
    private AiModelGateway.ChatResult callFallbackWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<AiModelGateway.ToolSpec> tools
    ) {
        RestClient client = restClientBuilder.baseUrl(properties.modelFallbackBaseUrl()).build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.modelFallbackChatModel());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("tools", tools.stream()
                .map(tool -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", parseJson(tool.parametersJson())
                        )
                ))
                .toList());
        FallbackChatResponse response = callFallbackWithRetry(() -> client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + secretCipher.resolve(properties.modelFallbackApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FallbackChatResponse.class));
        FallbackChatResponse.Message message = response.choices().get(0).message();
        List<AiModelGateway.ToolCall> toolCalls = null;
        if (message.tool_calls() != null && !message.tool_calls().isEmpty()) {
            toolCalls = message.tool_calls().stream()
                    .map(tc -> new AiModelGateway.ToolCall(
                            tc.id() == null ? "" : tc.id(),
                            tc.function() == null ? "" : tc.function().name(),
                            tc.function() == null || tc.function().arguments() == null ? "{}" : tc.function().arguments()
                    ))
                    .toList();
        }
        return new AiModelGateway.ChatResult(
                message == null || message.content() == null ? "" : message.content(),
                properties.modelFallbackChatModel(),
                null,
                null,
                toolCalls
        );
    }

    /**
     * 备用模型调用重试：免费模型常有瞬时限流（空 choices / 错误响应），
     * 退避重试最多 {@link #FALLBACK_MAX_ATTEMPTS} 次，全部失败才抛出。
     */
    private FallbackChatResponse callFallbackWithRetry(Supplier<FallbackChatResponse> action) {
        FallbackChatResponse response = null;
        Exception last = null;
        for (int attempt = 1; attempt <= FALLBACK_MAX_ATTEMPTS; attempt++) {
            try {
                response = action.get();
                if (response != null && response.choices() != null && !response.choices().isEmpty()) {
                    return response;
                }
            } catch (Exception ex) {
                last = ex;
                log.warn("备用模型调用失败 attempt={}: {}", attempt, ex.getMessage());
            }
            sleep(1000L * attempt);
        }
        throw new IllegalStateException("备用模型返回为空", last);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("null")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    public record FallbackChatResponse(List<Choice> choices) {
        public record Choice(Message message) {
        }

        public record Message(String content, List<ToolCallData> tool_calls) {
            public Message(String content) {
                this(content, null);
            }
        }

        public record ToolCallData(String id, String type, FunctionCall function) {
        }

        public record FunctionCall(String name, String arguments) {
        }
    }
}
