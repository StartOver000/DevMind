package com.devmind.ai;

import com.devmind.common.ApiException;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatRouterCircuitBreakerTest {

    @Mock
    private AiModelGateway primaryGateway;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private SecretCipher secretCipher;

    private ChatRouter router;

    @BeforeEach
    void setUp() {
        DevMindProperties properties = new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        router = new ChatRouter(
                primaryGateway,
                restClientBuilder,
                properties,
                secretCipher,
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
}
