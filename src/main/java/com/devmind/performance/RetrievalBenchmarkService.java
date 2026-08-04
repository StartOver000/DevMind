package com.devmind.performance;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.performance.dto.RetrievalBenchmarkResponse;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RetrievalBenchmarkService {

    private final UserService userService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AiModelGateway modelGateway;
    private final RetrievalService retrievalService;
    private final RerankService rerankService;
    private final DevMindProperties properties;

    public RetrievalBenchmarkService(
            UserService userService,
            KnowledgeBaseService knowledgeBaseService,
            AiModelGateway modelGateway,
            RetrievalService retrievalService,
            RerankService rerankService,
            DevMindProperties properties
    ) {
        this.userService = userService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.modelGateway = modelGateway;
        this.retrievalService = retrievalService;
        this.rerankService = rerankService;
        this.properties = properties;
    }

    public RetrievalBenchmarkResponse benchmark(Long knowledgeBaseId, String question, Integer iterations, Long userId) {
        userService.requireUser(userId);
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        int count = Math.min(Math.max(iterations == null ? 5 : iterations, 1), 20);
        long total = 0;
        int returned = 0;
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            List<RetrievalResult> results = retrievalService.searchHybrid(
                    knowledgeBaseId,
                    vector,
                    question,
                    properties.retrievalTopK(),
                    properties.retrievalMinScore(),
                    properties.retrievalVectorWeight(),
                    properties.retrievalKeywordWeight(),
                    properties.retrievalHybridEnabled()
            );
            rerankService.rerank(question, results, properties.retrievalTopK());
            total += System.nanoTime() - start;
            returned = results.size();
        }
        long totalMs = total / 1_000_000;
        return new RetrievalBenchmarkResponse(
                knowledgeBaseId,
                question,
                count,
                totalMs,
                Math.round(totalMs * 10.0 / count) / 10.0,
                returned
        );
    }
}
