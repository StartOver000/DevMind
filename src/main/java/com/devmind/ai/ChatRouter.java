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
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
    /** 便宜档模型（P2-4b）：简单任务走便宜档省成本；未配置则为 null（全部走主链） */
    private final AiModelGateway cheapGateway;
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
        this.cheapGateway = buildCheapGateway(restClientBuilder, properties, secretCipher);
        this.meterRegistry = meterRegistry;
        this.chatTimer = Timer.builder("devmind.model.calls.duration")
                .description("模型聊天调用耗时")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("devmind.model.calls.failed")
                .description("模型聊天调用失败次数")
                .register(meterRegistry);
    }

    /** 便宜档模型（未配置返回 null）：简单任务走便宜档，降低整体成本 */
    private AiModelGateway buildCheapGateway(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher
    ) {
        if (properties.modelCheapBaseUrl() == null || properties.modelCheapBaseUrl().isBlank()) {
            return null;
        }
        return new OpenAiCompatibleGateway(
                restClientBuilder,
                properties.modelCheapBaseUrl(),
                secretCipher.resolve(properties.modelCheapApiKey()),
                properties.modelCheapChatModel(),
                new ObjectMapper()
        );
    }

    /**
     * 便宜档调用（P2-4b 模型分级路由）：简单任务（意图分类/摘要/标题生成）走便宜模型省成本。
     * 降级策略：便宜档未配置或调用失败时，自动回退主链——不牺牲可用性，只省成本。
     */
    public AiModelGateway.ChatResult chatCheap(String systemPrompt, String userPrompt) {
        if (cheapGateway == null) {
            return chat(systemPrompt, userPrompt);
        }
        try {
            AiModelGateway.ChatResult result = cheapGateway.chat(systemPrompt, userPrompt);
            Counter.builder("devmind.model.tier").tag("tier", "cheap").register(meterRegistry).increment();
            return result;
        } catch (Exception ex) {
            log.warn("便宜档模型调用失败，回退主链: {}", ex.getMessage());
            return chat(systemPrompt, userPrompt);
        }
    }

    /**
     * 自动选档（G8 按任务复杂度分级路由）：根据系统提示特征判断任务复杂度，
     * 简单任务（意图分类/标题生成/短摘要）走便宜档，复杂任务（Agent/编排/工具调用）走主链。
     * 便宜档未配置或调用失败自动回退主链——只省成本，不牺牲可用性。
     * 调用方也可显式走 {@link #chatCheap} 或 {@link #chat} 覆盖自动决策。
     */
    public AiModelGateway.ChatResult chatAuto(String systemPrompt, String userPrompt) {
        if (isSimpleTask(systemPrompt)) {
            return chatCheap(systemPrompt, userPrompt);
        }
        return chat(systemPrompt, userPrompt);
    }

    /**
     * 简单任务启发式：系统提示短（<300 字符）且不含工具/JSON/编排指令特征。
     * 面向"意图分类/标题生成/短摘要"这类输入输出短、效果不敏感的任务；
     * 复杂任务（Agent 编排/工具 schema/严格输出格式）必须走主链。
     */
    private boolean isSimpleTask(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.length() > 300) {
            return false;
        }
        String lower = systemPrompt.toLowerCase();
        return !lower.contains("工具")
                && !lower.contains("json")
                && !lower.contains("输出要求")
                && !lower.contains("step")
                && !lower.contains("步骤")
                && !lower.contains("schema");
    }

    /**
     * 从配置构建备用 Provider 列表。
     * 顺序即降级顺序：硅基流动(主) → DeepSeek 官方（国内直连）→ OpenRouter（海外兜底）。
     */
    private List<ChatProvider> buildFallbackProviders(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher
    ) {
        List<ChatProvider> providers = new ArrayList<>();
        // 第一备用：DeepSeek 官方（国内直连，质量与主渠道同源；主渠道硅基流动超时/限流时接管）
        if (properties.modelFallback2BaseUrl() != null && !properties.modelFallback2BaseUrl().isBlank()) {
            OpenAiCompatibleGateway deepseek = new OpenAiCompatibleGateway(
                    restClientBuilder,
                    properties.modelFallback2BaseUrl(),
                    secretCipher.resolve(properties.modelFallback2ApiKey()),
                    properties.modelFallback2ChatModel(),
                    new ObjectMapper()
            );
            providers.add(new ChatProvider("deepseek", deepseek, true));
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
            // 主模型熔断中（如持续 429）：跳过主模型直接走备用，避免每个请求都白等一次失败
            AiModelGateway.ChatResult fallback = chatViaFallbacks(
                    provider -> provider.gateway().chat(systemPrompt, userPrompt));
            if (fallback != null) {
                return fallback;
            }
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
            boolean rateLimited = isRateLimited(primaryError);
            if (rateLimited) {
                // 429 是账户级持续状态（短时重试大概率仍 429）：立即熔断主模型，
                // 冷却期内的请求直接走备用，而不是每次都在智谱上白等。
                circuitStateStore.recordFailure(PRIMARY_CIRCUIT_KEY, 1, true, CIRCUIT_OPEN_MS);
            }
            AiModelGateway.ChatResult result = chatViaFallbacks(
                    provider -> provider.gateway().chat(systemPrompt, userPrompt));
            if (result != null) {
                // 备用接管成功：主模型若非限流（偶发失败）则清零计数恢复；限流熔断保留到冷却期结束
                if (!rateLimited) {
                    circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
                }
                return result;
            }
            return fail(primaryError);
        }
    }

    /**
     * 流式聊天（token 级）：主网关真流式（ZhipuRestModelGateway 覆写），失败按序降级到备用 Provider
     * （OpenAiCompatibleGateway 等用默认拆块模拟）；全部失败抛 MODEL_CALL_FAILED 交上层本地 RAG 降级。
     */
    public void streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        if (isCircuitOpen(PRIMARY_CIRCUIT_KEY)) {
            streamViaFallbacks(systemPrompt, userPrompt, onToken);
            return;
        }
        try {
            primaryGateway.streamChat(systemPrompt, userPrompt, onToken);
            circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
            return;
        } catch (Exception primaryError) {
            failedCounter.increment();
            boolean rateLimited = isRateLimited(primaryError);
            if (rateLimited) {
                circuitStateStore.recordFailure(PRIMARY_CIRCUIT_KEY, 1, true, CIRCUIT_OPEN_MS);
            }
            log.warn("主模型流式调用失败，切换备用 Provider: {}", primaryError.getMessage());
            streamViaFallbacks(systemPrompt, userPrompt, onToken);
        }
    }

    private void streamViaFallbacks(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        for (ChatProvider provider : fallbackProviders) {
            String key = providerKey(provider.name());
            if (circuitStateStore.isOpen(key)) {
                log.warn("备用 Provider {} 熔断中，跳过（流式）", provider.name());
                continue;
            }
            try {
                provider.gateway().streamChat(systemPrompt, userPrompt, onToken);
                circuitStateStore.reset(key);
                Counter.builder("devmind.model.fallback")
                        .tag("provider", provider.name())
                        .register(meterRegistry)
                        .increment();
                log.info("主模型失败，备用 Provider {} 流式接管成功", provider.name());
                return;
            } catch (Exception error) {
                circuitStateStore.recordFailure(key, CIRCUIT_FAILURE_THRESHOLD, isRateLimited(error), CIRCUIT_OPEN_MS);
                failedCounter.increment();
                log.warn("备用 Provider {} 流式调用失败: {}", provider.name(), error.getMessage());
            }
        }
        throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型流式调用失败（主备均不可用）");
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
            AiModelGateway.ChatResult fallback = chatViaFallbacks(
                    provider -> provider.gateway().chatWithTools(systemPrompt, messages, tools));
            if (fallback != null) {
                return fallback;
            }
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
            boolean rateLimited = isRateLimited(primaryError);
            if (rateLimited) {
                circuitStateStore.recordFailure(PRIMARY_CIRCUIT_KEY, 1, true, CIRCUIT_OPEN_MS);
            }
            AiModelGateway.ChatResult result = chatViaFallbacks(
                    provider -> provider.gateway().chatWithTools(systemPrompt, messages, tools));
            if (result != null) {
                if (!rateLimited) {
                    circuitStateStore.reset(PRIMARY_CIRCUIT_KEY);
                }
                return result;
            }
            return fail(primaryError);
        }
    }

    /**
     * 依次尝试备用 Provider，返回第一个成功的；全部失败返回 null。
     */
    private AiModelGateway.ChatResult chatViaFallbacks(
            java.util.function.Function<ChatProvider, AiModelGateway.ChatResult> action
    ) {
        for (ChatProvider provider : fallbackProviders) {
            AiModelGateway.ChatResult result = tryProvider(provider, () -> action.apply(provider));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 尝试一个备用 Provider：熔断跳过、退避重试 {@link #FALLBACK_MAX_ATTEMPTS} 次；
     * 成功则重置自身计数并返回（主模型熔断由调用方控制：429 熔断保留，偶发失败才清零）；
     * 失败则记录该 Provider 独立熔断并返回 null。
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
                // 429 限流 / 超时是 Provider 当前的持续状态，短时重试大概率仍失败：
                // 立即放弃让下一个 Provider 接管，避免每次请求在不可用的 Provider 上吃满退避。
                if (isRateLimited(ex) || isTimeout(ex)) {
                    break;
                }
            }
            sleep(1000L * attempt);
        }
        throw new IllegalStateException("备用 Provider 调用失败: " + name, last);
    }

    /**
     * 限流识别（结构化优先，字符串兜底）：
     * 优先按 HTTP 状态码（{@link RestClientResponseException} 429）判断，并穿透 cause 链
     * （上游网关可能把原始异常包装进自定义异常，如 IllegalStateException(cause=429)）；
     * 字符串匹配作为最后兜底，避免不同网关错误封装时漏判。
     */
    private static boolean isRateLimited(Exception error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException httpError) {
                if (httpError.getStatusCode().value() == 429) {
                    return true;
                }
            }
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (message.contains("429") || message.contains("Too Many")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 超时识别（结构化优先，字符串兜底）：
     * JDK HttpClient 超时（{@link java.net.http.HttpTimeoutException} / SocketTimeoutException /
     * TimeoutException）按异常类型直接判定；网关侧超时常见 504（网关超时）也视作超时，快速放弃不重试；
     * 穿透 cause 链 + 字符串兜底，避免不同错误封装漏判。
     */
    private static boolean isTimeout(Exception error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            if (t instanceof RestClientResponseException httpError
                    && httpError.getStatusCode().value() == 504) {
                return true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (message.contains("timed out")
                    || message.contains("Timeout")
                    || message.contains("Read timed out")
                    || message.contains("connect timed out")) {
                return true;
            }
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
