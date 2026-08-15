package com.devmind.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：embedding 缓存 key 必须带上模型维度（modelKey）。
 * 背景：曾因缓存 key 不含模型标识，切换 embedding 模型（embedding-2 → bge-m3）后
 * 相同文本命中旧模型向量，污染检索向量空间（KB19 召回失败的根因）。
 */
@ExtendWith(MockitoExtension.class)
class CachedEmbeddingGatewayTest {

    @Mock
    private AiModelGateway delegate;

    @Mock
    private EmbeddingCacheRepository cache;

    private List<Double> vecA = List.of(0.1, 0.2, 0.3);
    private List<Double> vecB = List.of(0.9, 0.8, 0.7);

    @Test
    void sameTextDifferentModelKeyDoesNotShareCache() {
        // 不同模型的缓存不能互相命中：同一文本在两个模型下必须各自调用底层模型
        when(cache.find(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(delegate.embed(anyList())).thenReturn(List.of(vecA)).thenReturn(List.of(vecB));

        CachedEmbeddingGateway gatewayA = new CachedEmbeddingGateway(delegate, cache, "zhipu:bge-m3@sf");
        CachedEmbeddingGateway gatewayB = new CachedEmbeddingGateway(delegate, cache, "old:embedding-2@mock");

        List<List<Double>> r1 = gatewayA.embed(List.of("RAG 是什么"));
        List<List<Double>> r2 = gatewayB.embed(List.of("RAG 是什么"));

        assertThat(r1.get(0)).isEqualTo(vecA);
        assertThat(r2.get(0)).isEqualTo(vecB);
        // 两个 modelKey 各自独立调用底层模型，未因共享缓存而互相命中
        verify(delegate, times(2)).embed(anyList());
    }

    @Test
    void sameTextSameModelHitsMemoryCache() {
        when(cache.find(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(delegate.embed(anyList())).thenReturn(List.of(vecA));

        CachedEmbeddingGateway gateway = new CachedEmbeddingGateway(delegate, cache, "zhipu:bge-m3@sf");

        gateway.embed(List.of("RAG 是什么"));
        List<List<Double>> second = gateway.embed(List.of("RAG 是什么"));

        assertThat(second.get(0)).isEqualTo(vecA);
        verify(delegate, times(1)).embed(anyList());
    }
}
