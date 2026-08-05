package com.devmind.ai;

import com.devmind.common.ApiException;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ChatRouterCircuitBreakerTest {

    @Mock
    private AiModelGateway primaryGateway;

    private RestClient.Builder restClientBuilder;

    @Mock
    private SecretCipher secretCipher;

    private ChatRouter router;

    private DevMindProperties baseProperties(String fallbackBaseUrl) {
        return new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, fallbackBaseUrl, "fallback-key", "fallback-model", "", "", "glm-4.7-flash",
                "embedding-2", 2000, false, true
        );
    }

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        router = new ChatRouter(
                primaryGateway,
                restClientBuilder,
                baseProperties(""),
                secretCipher,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void opensCircuitAfterRepeatedFailuresAndSkipsPrimary() {
        doThrow(new RuntimeException("boom"))
                .when(primaryGateway).chat(anyString(), anyString());

        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);

        // 第 4 次：熔断打开，快速失败，不再调用主模型
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);

        verify(primaryGateway, times(3)).chat(anyString(), anyString());
    }

    @Test
    void rateLimitedOpensCircuitImmediately() {
        doThrow(new RuntimeException("HTTP 429 Too Many Requests"))
                .when(primaryGateway).chat(anyString(), anyString());

        // 第一次失败即触发熔断（429 限流是持续状态）
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);
        // 熔断打开：不再调用主模型，快速失败交由上层降级
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);

        verify(primaryGateway, times(1)).chat(anyString(), anyString());
    }

    @Test
    void successResetsFailureCount() {
        doThrow(new RuntimeException("fail"))
                .doThrow(new RuntimeException("fail"))
                .doReturn(new AiModelGateway.ChatResult("ok", "mock", 0, 0))
                .doThrow(new RuntimeException("fail"))
                .when(primaryGateway).chat(anyString(), anyString());

        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);
        router.chat("s", "u"); // 成功 → 重置计数
        // 重置后再失败 1 次，不应触发熔断（仍调用主模型）
        assertThatThrownBy(() -> router.chat("s", "u")).isInstanceOf(ApiException.class);

        verify(primaryGateway, times(4)).chat(anyString(), anyString());
    }

    @Test
    void chatWithToolsFallsBackToSecondaryWhenPrimaryFails() {
        router = new ChatRouter(
                primaryGateway,
                restClientBuilder,
                baseProperties("https://fallback.example.com"),
                secretCipher,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );
        when(secretCipher.resolve(anyString())).thenReturn("fb-secret");
        doThrow(new RuntimeException("HTTP 429"))
                .when(primaryGateway).chatWithTools(anyString(), anyList(), anyList());

        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        server.expect(requestTo("https://fallback.example.com/chat/completions"))
                .andRespond(withSuccess(
                        """
                        {"choices":[{"message":{"content":"","tool_calls":[
                          {"id":"c1","type":"function","function":{"name":"kb_search","arguments":"{\\"question\\":\\"x\\"}"}}
                        ]}}]}
                        """,
                        MediaType.APPLICATION_JSON
                ));

        AiModelGateway.ChatResult result = router.chatWithTools(
                "s",
                List.of(),
                List.of(new AiModelGateway.ToolSpec("kb_search", "检索", "{}"))
        );

        // 主 429 → 备用模型返回工具调用，Agent 链路不中断
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).name()).isEqualTo("kb_search");
        server.verify();
    }
}
