package com.devmind.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRouterTest {

    @Test
    void routesCodeLikeQuestionToKeywordFirst() {
        QueryRouter.Route route = QueryRouter.route("EXPLAIN 看哪些字段");
        assertThat(route.mode()).isEqualTo("keyword-first");
        assertThat(route.keywordWeight()).isGreaterThan(route.vectorWeight());
    }

    @Test
    void routesSqlTermToKeywordFirst() {
        QueryRouter.Route route = QueryRouter.route("order by 为什么慢");
        assertThat(route.mode()).isEqualTo("keyword-first");
    }

    @Test
    void routesAcronymToKeywordFirst() {
        assertThat(QueryRouter.route("什么是 RAG").mode()).isEqualTo("keyword-first");
    }

    @Test
    void routesPlainQuestionToHybrid() {
        QueryRouter.Route route = QueryRouter.route("深分页为什么慢");
        assertThat(route.mode()).isEqualTo("hybrid");
        assertThat(route.vectorWeight()).isGreaterThan(route.keywordWeight());
    }

    @Test
    void handlesBlankQuestion() {
        assertThat(QueryRouter.route("").mode()).isEqualTo("hybrid");
        assertThat(QueryRouter.route(null).mode()).isEqualTo("hybrid");
    }
}
