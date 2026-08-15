package com.devmind.performance;

import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.performance.dto.RetrievalBenchmarkResponse;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RetrievalBenchmarkService.class);

    public RetrievalBenchmarkResponse benchmark(Long knowledgeBaseId, String question, Integer iterations, Long userId) {
        userService.requireUser(userId);
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        int count = Math.min(Math.max(iterations == null ? 5 : iterations, 1), 20);
        long total = 0;
        int returned = 0;
        for (int i = 0; i < count; i++) {
            long start = System.nanoTime();
            long t0 = System.nanoTime();
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            long t1 = System.nanoTime();
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
            long t2 = System.nanoTime();
            rerankService.rerank(question, results, properties.retrievalTopK());
            long t3 = System.nanoTime();
            total += t3 - start;
            if (i == 0) {
                log.info("benchmark breakdown: embed={}ms searchHybrid={}ms rerank={}ms total={}ms",
                        (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, (t3 - start) / 1_000_000);
            }
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
