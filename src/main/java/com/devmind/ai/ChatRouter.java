package com.devmind.ai;

import com.devmind.common.ApiException;
import com.devmind.common.CircuitStateStore;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 多 Provider 模型路由：
 * - 主网关（primaryGateway，按 model-mode 装配）失败后，按序尝试备用 Provider 列表，
 *   每个 Provider 独立熔断状态（互不影响、故障域隔离），全部失败才抛异常交给上层降级。
 * - 备用 Provider 常为免费模型（OpenRouter 等，有瞬时限流），调用带退避重试。
 * - 增加新 Provider：在 {@link #buildFallbackProviders()} 加一个 {@link OpenAiCompatibleGateway} 即可（配置驱动）。
 */
@Component
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

    /** 连续失败多少次后触发熔断 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** 熔断打开后多久尝试恢复 */
    private static final long CIRCUIT_OPEN_MS = 60_000L;
    /** 备用 Provider（免费模型，有瞬时限流）最大调用尝试次数 */
    private static final int FALLBACK_MAX_ATTEMPTS = 3;
    /** 主模型熔断状态 key */
    private static final String PRIMARY_CIRCUIT_KEY = "primary";

    /** 备用 Provider：name 用于熔断隔离与日志；supportsTools 用于 Agent 工具链路 */
    public record ChatProvider(String name, AiModelGateway gateway, boolean supportsTools) {
    }

    private final AiModelGateway primaryGateway;
    private final List<ChatProvider> fallbackProviders;
    private final CircuitStateStore circuitStateStore;
    private final MeterRegistry meterRegistry;
    private final Timer chatTimer;
    private final Counter failedCounter;

    public ChatRouter(
            AiModelGateway primaryGateway,
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            CircuitStateStore circuitStateStore,
            MeterRegistry meterRegistry
    ) {
        this.primaryGateway = primaryGateway;
        this.circuitStateStore = circuitStateStore;
        this.fallbackProviders = buildFallbackProviders(restClientBuilder, properties, secretCipher);
        this.meterRegistry = meterRegistry;
        this.chatTimer = Timer.builder("devmind.model.calls.duration")
                .description("模型聊天调用耗时")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("devmind.model.calls.failed")
                .description("模型聊天调用失败次数")
                .register(meterRegistry);
    }

    /**
     * 从配置构建备用 Provider 列表。
     * 顺序即降级顺序：先国内直连（硅基流动，上线无需梯子），再海外（OpenRouter，最后兜底）。
     */
    private List<ChatProvider> buildFallbackProviders(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher
    ) {
        List<ChatProvider> providers = new ArrayList<>();
        // 第一备用：硅基流动等国内直连（上线场景用户无需挂梯子）
        if (properties.modelFallback2BaseUrl() != null && !properties.modelFallback2BaseUrl().isBlank()) {
            OpenAiCompatibleGateway siliconflow = new OpenAiCompatibleGateway(
                    restClientBuilder,
                    properties.modelFallback2BaseUrl(),
                    secretCipher.resolve(properties.modelFallback2ApiKey()),
                    properties.modelFallback2ChatModel(),
                    new ObjectMapper()
            );
            providers.add(new ChatProvider("siliconflow", siliconflow, true));
        }
        // 第二备用：OpenRouter（海外，需梯子，仅作最后兜底）
        if (properties.modelFallbackBaseUrl() != null && !properties.modelFallbackBaseUrl().isBlank()) {
            OpenAiCompatibleGateway openrouter = new OpenAiCompatibleGateway(
                    restClientBuilder,
                    properties.modelFallbackBaseUrl(),
                    secretCipher.resolve(properties.modelFallbackApiKey()),
                    properties.modelFallbackChatModel(),
                    new ObjectMapper()
            );
            providers.add(new ChatProvider("openrouter", openrouter, true));
        }
        return providers;
    }

    public AiModelGateway.ChatResult chat(String systemPrompt, String userPrompt) {
        if (isCircuitOpen(PRIMARY_CIRCUIT_KEY)) {
            // 熔断打开：不再重试任何模型，快速失败，让上层立即走本地 RAG 降级
            throw new ApiException(
                    ErrorCode.MODEL_CALL_FAILED,
                    "模型服务连续失败，已进入熔断降级，请稍后重试"
            );
        }
        try {
            AiModelGateway.ChatResult result = chatTimer.record(() -> primaryGateway.chat(systemPrompt, userPrompt));
            circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
            return result;
        } catch (Exception primaryError) {
            failedCounter.increment();
            for (ChatProvider provider : fallbackProviders) {
                AiModelGateway.ChatResult result = tryProvider(provider, () -> provider.gateway().chat(systemPrompt, userPrompt));
                if (result != null) {
                    return result;
                }
            }
            return fail(primaryError);
        }
    }

    /**
     * Agent 工具调用：主网关失败后按序尝试支持 tools 的备用 Provider，
     * 每个 Provider 独立熔断；全部失败走 fail() 统一熔断策略。
     */
    public AiModelGateway.ChatResult chatWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<AiModelGateway.ToolSpec> tools
    ) {
        if (isCircuitOpen(PRIMARY_CIRCUIT_KEY)) {
            throw new ApiException(
                    ErrorCode.MODEL_CALL_FAILED,
                    "模型服务连续失败，已进入熔断降级，请稍后重试"
            );
        }
        try {
            AiModelGateway.ChatResult result = chatTimer.record(() -> primaryGateway.chatWithTools(systemPrompt, messages, tools));
            circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
            return result;
        } catch (Exception primaryError) {
            failedCounter.increment();
            for (ChatProvider provider : fallbackProviders) {
                if (!provider.supportsTools()) {
                    continue;
                }
                AiModelGateway.ChatResult result = tryProvider(
                        provider,
                        () -> provider.gateway().chatWithTools(systemPrompt, messages, tools)
                );
                if (result != null) {
                    return result;
                }
            }
            return fail(primaryError);
        }
    }

    /**
     * 尝试一个备用 Provider：熔断跳过、退避重试 {@link #FALLBACK_MAX_ATTEMPTS} 次；
     * 成功则重置自身与主模型计数并返回，失败则记录该 Provider 独立熔断并返回 null。
     */
    private AiModelGateway.ChatResult tryProvider(
            ChatProvider provider,
            Supplier<AiModelGateway.ChatResult> action
    ) {
        String key = providerKey(provider.name());
        if (circuitStateStore.isOpen(key)) {
            log.warn("备用 Provider {} 熔断中，跳过", provider.name());
            return null;
        }
        try {
            AiModelGateway.ChatResult result = invokeWithRetry(action, provider.name());
            circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
            circuitStateStore.reset(key);
            // 备用 Provider 接管计数（按 provider 归因）
            Counter.builder("devmind.model.fallback")
                    .tag("provider", provider.name())
                    .register(meterRegistry)
                    .increment();
            log.info("主模型失败，备用 Provider {} 接管成功", provider.name());
            return result;
        } catch (Exception error) {
            circuitStateStore.recordFailure(key, CIRCUIT_FAILURE_THRESHOLD, isRateLimited(error), CIRCUIT_OPEN_MS);
            failedCounter.increment();
            log.warn("备用 Provider {} 调用失败: {}", provider.name(), error.getMessage());
            return null;
        }
    }

    /** 主备均失败：429 限流立即熔断（持续状态，重试无意义）；其他错误累计达到阈值后熔断。 */
    private AiModelGateway.ChatResult fail(Exception error) {
        circuitStateStore.recordFailure(PRIMARY_CIRCUIT_KEY, CIRCUIT_FAILURE_THRESHOLD, isRateLimited(error), CIRCUIT_OPEN_MS);
        if (error instanceof ApiException apiError) {
            throw apiError;
        }
        throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型调用失败: " + error.getMessage());
    }

    private boolean isCircuitOpen(String key) {
        return circuitStateStore.isOpen(key);
    }

    private static String providerKey(String name) {
        return "provider:" + name;
    }

    /** Provider 调用退避重试（免费模型常有瞬时限流）；限流（429）为持续状态，快速放弃不重试 */
    private AiModelGateway.ChatResult invokeWithRetry(Supplier<AiModelGateway.ChatResult> action, String name) {
        Exception last = null;
        for (int attempt = 1; attempt <= FALLBACK_MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (Exception ex) {
                last = ex;
                log.warn("备用 Provider {} 调用失败 attempt={}: {}", name, attempt, ex.getMessage());
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.contains("429") || message.contains("Too Many")) {
                    break;
                }
            }
            sleep(1000L * attempt);
        }
        throw new IllegalStateException("备用 Provider 调用失败: " + name, last);
    }

    private static boolean isRateLimited(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage();
        return message.contains("429") || message.contains("Too Many");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
