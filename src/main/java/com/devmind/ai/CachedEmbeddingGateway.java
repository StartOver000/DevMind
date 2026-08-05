package com.devmind.ai;

import com.devmind.common.HashUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * 模型网关装饰器：
 * 1. Embedding 结果按内容哈希缓存（相同文本不重复调用嵌入模型）；
 * 2. 并发信号量限制同时进行的模型调用，缓解上游 429 限流。
 */
public class CachedEmbeddingGateway implements AiModelGateway {

    private static final int MAX_CONCURRENCY = 2;

    private final AiModelGateway delegate;
    private final EmbeddingCacheRepository cache;
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENCY);

    public CachedEmbeddingGateway(AiModelGateway delegate, EmbeddingCacheRepository cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        List<List<Double>> result = new ArrayList<>(texts.size());
        List<String> missing = new ArrayList<>();
        List<Integer> missingIndexes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            Optional<List<Double>> cached = cache.find(HashUtils.sha256(text));
            if (cached.isPresent()) {
                result.add(cached.get());
            } else {
                result.add(null);
                missing.add(text);
                missingIndexes.add(i);
            }
        }
        if (!missing.isEmpty()) {
            List<List<Double>> computed = acquire(() -> delegate.embed(missing));
            for (int j = 0; j < missing.size(); j++) {
                List<Double> vector = computed.get(j);
                result.set(missingIndexes.get(j), vector);
                cache.put(HashUtils.sha256(missing.get(j)), vector);
            }
        }
        return result;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        return acquire(() -> delegate.chat(systemPrompt, userPrompt));
    }

    @Override
    public ChatResult chatWithTools(String systemPrompt, java.util.List<java.util.Map<String, Object>> messages, java.util.List<ToolSpec> tools) {
        return acquire(() -> delegate.chatWithTools(systemPrompt, messages, tools));
    }

    private <T> T acquire(java.util.function.Supplier<T> action) {
        try {
            semaphore.acquire();
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用被中断", ex);
        } finally {
            semaphore.release();
        }
    }
}
