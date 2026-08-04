package com.devmind.retrieval;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRerankerTest {

    @Test
    void reordersByModelOutput() {
        ChatRouter router = mock(ChatRouter.class);
        when(router.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("3,1,2", "mock", 0, 0));
        ModelReranker reranker = new ModelReranker(router);
        List<RetrievalResult> results = List.of(
                new RetrievalResult(1L, 1L, "a.md", 1, "内容1", Map.of(), 0.7),
                new RetrievalResult(2L, 1L, "a.md", 2, "内容2", Map.of(), 0.8),
                new RetrievalResult(3L, 1L, "a.md", 3, "内容3", Map.of(), 0.9)
        );

        List<RetrievalResult> reordered = reranker.rerank("问题", results, 2);

        assertThat(reordered).extracting(RetrievalResult::chunkId).containsExactly(3L, 1L);
    }
}
