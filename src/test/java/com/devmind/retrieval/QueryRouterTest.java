package com.devmind.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRouterTest {

    @Test
    void allQuestionsAreVectorDominant() {
        // 2026-08-15：统一向量主导（0.9/0.1）。旧 keyword-first 会让仅关键词命中的
        // 噪声 chunk 压过向量高度相关文档，把真相关文档挤出 top-K。
        for (String q : new String[]{"EXPLAIN 看哪些字段", "order by 为什么慢", "什么是 RAG",
                "深分页为什么慢", "LLM 网关是什么？", ""}) {
            QueryRouter.Route route = QueryRouter.route(q);
            assertThat(route.mode()).isEqualTo("hybrid");
            assertThat(route.vectorWeight()).isEqualTo(0.9);
            assertThat(route.keywordWeight()).isEqualTo(0.1);
            assertThat(route.vectorWeight()).isGreaterThan(route.keywordWeight());
        }
    }

    @Test
    void handlesBlankQuestion() {
        assertThat(QueryRouter.route("").mode()).isEqualTo("hybrid");
        assertThat(QueryRouter.route(null).mode()).isEqualTo("hybrid");
    }
}
