package com.devmind.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriterTest {

    @Test
    void rewritesPronounQuestionWithHistory() {
        assertThat(QueryRewriter.rewrite("它为什么慢", List.of("深分页怎么优化")))
                .isEqualTo("深分页 为什么慢");
    }

    @Test
    void rewritesThisWithHistory() {
        assertThat(QueryRewriter.rewrite("这个怎么处理", List.of("索引失效有哪些场景")))
                .isEqualTo("索引失效 怎么处理");
    }

    @Test
    void keepsPlainQuestionUnchanged() {
        assertThat(QueryRewriter.rewrite("联合索引怎么用", List.of("深分页怎么优化")))
                .isEqualTo("联合索引怎么用");
    }

    @Test
    void keepsQuestionWhenNoHistory() {
        assertThat(QueryRewriter.rewrite("它为什么慢", List.of()))
                .isEqualTo("它为什么慢");
        assertThat(QueryRewriter.rewrite("它为什么慢", null))
                .isEqualTo("它为什么慢");
    }

    @Test
    void usesLatestHistoryQuestion() {
        assertThat(QueryRewriter.rewrite("它怎么解决", List.of("什么是RAG", "Using filesort 怎么解决")))
                .isEqualTo("Using filesort 怎么解决");
    }
}
