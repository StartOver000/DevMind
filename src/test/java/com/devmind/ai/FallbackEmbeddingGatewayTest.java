package com.devmind.ai;

import com.devmind.ai.FallbackEmbeddingGateway.FallbackEmbeddingCaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FallbackEmbeddingGatewayTest {

    @Mock
    private AiModelGateway delegate;

    @Mock
    private FallbackEmbeddingCaller fallbackCaller;

    private FallbackEmbeddingGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new FallbackEmbeddingGateway(delegate, fallbackCaller);
    }

    @Test
    void usesPrimaryWhenItSucceeds() {
        when(delegate.embed(List.of("a"))).thenReturn(List.of(List.of(1.0, 2.0)));

        List<List<Double>> result = gateway.embed(List.of("a"));

        assertThat(result.get(0)).containsExactly(1.0, 2.0);
        // 备用链路不被调用
        verify(fallbackCaller, never()).call(anyList());
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryFails() {
        when(delegate.embed(List.of("a"))).thenThrow(new RuntimeException("HTTP 429"));
        when(fallbackCaller.call(anyList())).thenReturn(List.of(List.of(0.5, 0.6, 0.7, 0.8)));

        List<List<Double>> result = gateway.embed(List.of("a"));

        assertThat(result.get(0)).containsExactly(0.5, 0.6, 0.7, 0.8);
        verify(fallbackCaller).call(List.of("a"));
    }

    @Test
    void propagatesErrorWhenBothFail() {
        when(delegate.embed(List.of("a"))).thenThrow(new RuntimeException("HTTP 429"));
        when(fallbackCaller.call(anyList())).thenThrow(new RuntimeException("备用接口也失败"));

        assertThatThrownBy(() -> gateway.embed(List.of("a")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("备用接口也失败");
    }

    @Test
    void chatIsPassedThroughToDelegate() {
        AiModelGateway.ChatResult expected = new AiModelGateway.ChatResult("ok", "m", 0, 0);
        when(delegate.chat("s", "u")).thenReturn(expected);

        assertThat(gateway.chat("s", "u")).isSameAs(expected);
    }

    @Test
    void chatWithToolsIsPassedThroughToDelegate() {
        AiModelGateway.ChatResult expected = new AiModelGateway.ChatResult("ok", "m", 0, 0);
        when(delegate.chatWithTools(anyString(), anyList(), anyList())).thenReturn(expected);

        assertThat(gateway.chatWithTools("s", List.of(Map.of("role", "user")), List.of())).isSameAs(expected);
    }
}
