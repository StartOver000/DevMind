package com.devmind.ai;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChatRouter {

    /** 连续失败多少次后触发熔断 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** 熔断打开后多久尝试恢复 */
    private static final long CIRCUIT_OPEN_MS = 60_000L;

    private final AiModelGateway primaryGateway;
    private final RestClient.Builder restClientBuilder;
    private final DevMindProperties properties;
    private final SecretCipher secretCipher;
    private final Timer chatTimer;
    private final Counter failedCounter;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long circuitOpenUntil = 0L;

    public ChatRouter(
            AiModelGateway primaryGateway,
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            MeterRegistry meterRegistry
    ) {
        this.primaryGateway = primaryGateway;
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.secretCipher = secretCipher;
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
        FallbackChatResponse response = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + secretCipher.resolve(properties.modelFallbackApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(FallbackChatResponse.class);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("备用模型返回为空");
        }
        return new AiModelGateway.ChatResult(
                response.choices().get(0).message().content(),
                properties.modelFallbackChatModel(),
                null,
                null
        );
    }

    public record FallbackChatResponse(List<Choice> choices) {
        public record Choice(Message message) {
        }

        public record Message(String content) {
        }
    }
}
