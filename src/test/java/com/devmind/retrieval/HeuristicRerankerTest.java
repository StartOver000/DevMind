package com.devmind.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicRerankerTest {

    @Test
    void boostsChunksWithMoreKeywordOverlap() {
        HeuristicReranker reranker = new HeuristicReranker();
        List<RetrievalResult> results = List.of(
                new RetrievalResult(1L, 1L, "a.md", 1, "MySQL 深分页优化", Map.of(), 0.82),
                new RetrievalResult(2L, 1L, "a.md", 2, "Redis 缓存雪崩", Map.of(), 0.80)
        );

        List<RetrievalResult> reranked = reranker.rerank(results, "MySQL 深分页为什么慢");

        assertThat(reranked.get(0).chunkId()).isEqualTo(1L);
    }

    @Test
    void keywordExtractorKeepsChineseBigramsAndEnglishWords() {
        List<String> terms = KeywordExtractor.extract("MySQL 深分页 slow query", 10);

        assertThat(terms).contains("mysql", "slow", "query", "深分", "分页");
    }
}
