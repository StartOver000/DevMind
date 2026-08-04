package com.devmind.retrieval;

import com.devmind.config.DevMindProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RerankServiceCacheTest {

    @Mock
    private HeuristicReranker heuristicReranker;

    @Mock
    private ModelReranker modelReranker;

    @Mock
    private DevMindProperties properties;

    private RerankService service() {
        return new RerankService(heuristicReranker, modelReranker, properties);
    }

    private RetrievalResult result(long chunkId) {
        return new RetrievalResult(chunkId, 1L, "doc", 1, "content", Map.of(), 0.5);
    }

    @Test
    void modelRerankResultIsCached() {
        RerankService service = service();
        List<RetrievalResult> results = List.of(result(1L), result(2L));
        when(modelReranker.rerank("q", results, 2)).thenReturn(results);

        service.rerank("q", results, 2, "model");
        service.rerank("q", results, 2, "model");

        verify(modelReranker, times(1)).rerank("q", results, 2);
    }

    @Test
    void differentQuestionIsNotCached() {
        RerankService service = service();
        List<RetrievalResult> results = List.of(result(1L), result(2L));
        when(modelReranker.rerank(anyString(), anyList(), anyInt())).thenReturn(results);

        service.rerank("q1", results, 2, "model");
        service.rerank("q2", results, 2, "model");

        verify(modelReranker, times(2)).rerank(anyString(), anyList(), anyInt());
    }

    @Test
    void modelFailureFallsBackToHeuristic() {
        RerankService service = service();
        List<RetrievalResult> results = List.of(result(1L), result(2L));
        when(modelReranker.rerank(anyString(), anyList(), anyInt())).thenThrow(new RuntimeException("fail"));
        when(heuristicReranker.rerank(anyList(), anyString())).thenReturn(results);

        List<RetrievalResult> out = service.rerank("q", results, 2, "model");

        assertThat(out).isNotEmpty();
        verify(heuristicReranker).rerank(anyList(), anyString());
    }
}
