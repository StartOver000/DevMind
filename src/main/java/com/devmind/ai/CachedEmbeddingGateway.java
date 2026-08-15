package com.devmind.ai;

import com.devmind.common.HashUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 模型网关装饰器：
 * 1. Embedding 结果按内容哈希缓存（相同文本不重复调用嵌入模型）——两级缓存：
 *    L1 进程内内存缓存（高频同问题直接命中，省 DB 往返）+ L2 embedding_cache 表（跨实例共享）；
 * 2. 并发信号量限制同时进行的模型调用，缓解上游 429 限流。
 */
public class CachedEmbeddingGateway implements AiModelGateway {

    private static final int MAX_CONCURRENCY = 2;
    /** L1 内存缓存上限：超过后整体清空（向量集合通常很小，简单可靠；重启即失效） */
    private static final int MAX_MEMORY_CACHE = 2000;

    private final AiModelGateway delegate;
    private final EmbeddingCacheRepository cache;
    /** 模型标识：缓存 key 必须带上，否则切换 embedding 模型（如 embedding-2 → bge-m3）后旧向量污染检索 */
    private final String modelKey;
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENCY);
    /** L1 内存缓存：sha256(modelKey+text) → vector。命中则跳过 DB 查询（检索/问答高频同问题场景显著降低延迟与连接竞争） */
    private final Map<String, List<Double>> memoryCache = new ConcurrentHashMap<>();

    public CachedEmbeddingGateway(AiModelGateway delegate, EmbeddingCacheRepository cache, String modelKey) {
        this.delegate = delegate;
        this.cache = cache;
        this.modelKey = modelKey;
    }

    private String cacheKey(String text) {
        return HashUtils.sha256(modelKey + "\n" + text);
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        List<List<Double>> result = new ArrayList<>(texts.size());
        List<String> missing = new ArrayList<>();
        List<Integer> missingIndexes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String key = cacheKey(text);
            // L1：进程内内存命中（高频同问题，零 DB 往返）
            List<Double> mem = memoryCache.get(key);
            if (mem != null) {
                result.add(mem);
                continue;
            }
            // L2：embedding_cache 表（跨实例共享）
            Optional<List<Double>> cached = cache.find(key);
            if (cached.isPresent()) {
                memoryCache.put(key, cached.get());
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
                String key = cacheKey(missing.get(j));
                cache.put(key, vector);
                memoryCache.put(key, vector);
            }
            if (memoryCache.size() > MAX_MEMORY_CACHE) {
                memoryCache.clear();
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
