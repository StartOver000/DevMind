package com.devmind.retrieval;

import com.devmind.config.DevMindProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final int CACHE_MAX = 500;

    private final HeuristicReranker heuristicReranker;
    private final ModelReranker modelReranker;
    private final DevMindProperties properties;
    private final Map<String, List<RetrievalResult>> modelCache = new ConcurrentHashMap<>();

    public RerankService(
            HeuristicReranker heuristicReranker,
            ModelReranker modelReranker,
            DevMindProperties properties
    ) {
        this.heuristicReranker = heuristicReranker;
        this.modelReranker = modelReranker;
        this.properties = properties;
    }

    public List<RetrievalResult> rerank(String question, List<RetrievalResult> results, int topK) {
        return rerank(question, results, topK, properties.rerankMode());
    }

    public List<RetrievalResult> rerank(String question, List<RetrievalResult> results, int topK, String mode) {
        if ("model".equalsIgnoreCase(mode == null ? properties.rerankMode() : mode) && results.size() > 1) {
            String key = cacheKey(question, results);
            List<RetrievalResult> cached = modelCache.get(key);
            if (cached != null) {
                return cached.stream().limit(topK).toList();
            }
            try {
                List<RetrievalResult> ranked = modelReranker.rerank(question, results, topK);
                putCache(key, ranked);
                return ranked;
            } catch (Exception ex) {
                log.warn("model rerank failed, fallback to heuristic: {}", ex.getMessage());
            }
        }
        return heuristicReranker.rerank(results, question).stream().limit(topK).toList();
    }

    private String cacheKey(String question, List<RetrievalResult> results) {
        return question + "|" + results.stream()
                .map(result -> result.chunkId().toString())
                .collect(Collectors.joining(","));
    }

    private void putCache(String key, List<RetrievalResult> value) {
        if (modelCache.size() >= CACHE_MAX) {
            modelCache.clear();
        }
        modelCache.put(key, value);
    }
}
