package com.devmind.retrieval;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class RetrievalService {

    private final ChunkRepository chunkRepository;

    public RetrievalService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public List<RetrievalResult> search(Long knowledgeBaseId, List<Double> queryVector, int topK, double minScore) {
        return chunkRepository.search(knowledgeBaseId, queryVector, topK, minScore, Map.of());
    }

    public List<RetrievalResult> search(
            Long knowledgeBaseId,
            List<Double> queryVector,
            int topK,
            double minScore,
            Map<String, Object> metadataFilter
    ) {
        return chunkRepository.search(knowledgeBaseId, queryVector, topK, minScore, metadataFilter);
    }

    public List<RetrievalResult> searchHybrid(
            Long knowledgeBaseId,
            List<Double> queryVector,
            String question,
            int topK,
            double minScore,
            double vectorWeight,
            double keywordWeight,
            boolean hybridEnabled
    ) {
        return searchHybrid(
                knowledgeBaseId,
                queryVector,
                question,
                topK,
                minScore,
                vectorWeight,
                keywordWeight,
                hybridEnabled,
                Map.of()
        );
    }

    public List<RetrievalResult> searchHybrid(
            Long knowledgeBaseId,
            List<Double> queryVector,
            String question,
            int topK,
            double minScore,
            double vectorWeight,
            double keywordWeight,
            boolean hybridEnabled,
            Map<String, Object> metadataFilter
    ) {
        return chunkRepository.searchHybrid(
                knowledgeBaseId,
                queryVector,
                question,
                topK,
                minScore,
                vectorWeight,
                keywordWeight,
                hybridEnabled,
                metadataFilter
        );
    }

    /** 纯关键词检索：向量模型不可用（限流/故障）时的降级路径。 */
    public List<RetrievalResult> searchByKeywords(
            Long knowledgeBaseId,
            String question,
            int topK,
            Map<String, Object> metadataFilter
    ) {
        return chunkRepository.searchByKeywords(knowledgeBaseId, KeywordExtractor.extract(question, 10), topK, metadataFilter);
    }
}
