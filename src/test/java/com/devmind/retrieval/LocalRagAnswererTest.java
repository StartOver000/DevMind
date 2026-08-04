package com.devmind.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalRagAnswererTest {

    @Test
    void emptyResultsReturnsNoContentMessage() {
        assertThat(LocalRagAnswerer.answer("q", List.of()))
                .isEqualTo("知识库中没有找到足够相关内容。");
    }

    @Test
    void nullResultsReturnsNoContentMessage() {
        assertThat(LocalRagAnswerer.answer("q", null))
                .isEqualTo("知识库中没有找到足够相关内容。");
    }

    @Test
    void buildsAnswerFromResultsWithDegradedNotice() {
        RetrievalResult result = new RetrievalResult(
                1L, 1L, "a.md", 0, "RAG 是把检索与生成结合的架构。",
                Map.of("heading", "什么是 RAG"), 0.9
        );

        String answer = LocalRagAnswerer.answer("什么是 RAG", List.of(result));

        assertThat(answer)
                .contains("本地降级模式")
                .contains("什么是 RAG")
                .contains("a.md")
                .contains("什么是 RAG")
                .contains("RAG 是把检索与生成结合的架构。")
                .contains("0.9000");
    }

    @Test
    void truncatesLongContent() {
        String longContent = "x".repeat(600);
        RetrievalResult result = new RetrievalResult(
                1L, 1L, "a.md", 0, longContent, Map.of(), 0.8
        );

        String answer = LocalRagAnswerer.answer("q", List.of(result));

        assertThat(answer).contains("…").doesNotContain("x".repeat(600));
    }
}
