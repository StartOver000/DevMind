package com.devmind.security;

import com.devmind.common.InMemoryRateLimitStore;
import com.devmind.config.DevMindSecurityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Test
    void blocksRequestsOverGeneralLimit() throws Exception {
        // 一般接口每用户每分钟最多 3 次
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new DevMindSecurityProperties(5, 10, 3, 2, 7, 2, ""),
                new InMemoryRateLimitStore(),
                new SimpleMeterRegistry()
        );
        when(request.getRequestURI()).thenReturn("/api/knowledge-bases");
        when(request.getHeader("X-User-Id")).thenReturn("1");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        // 第 4 次超限
        assertThat(interceptor.preHandle(request, response, null)).isFalse();
    }

    @Test
    void loginEndpointHasStricterLimit() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new DevMindSecurityProperties(5, 10, 100, 2, 7, 2, ""),
                new InMemoryRateLimitStore(),
                new SimpleMeterRegistry()
        );
        when(request.getRequestURI()).thenReturn("/api/auth/login");
        when(request.getHeader("X-User-Id")).thenReturn("1");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        assertThat(interceptor.preHandle(request, response, null)).isTrue();
        assertThat(interceptor.preHandle(request, response, null)).isFalse();
    }

    @Test
    void limitZeroDisablesRateLimit() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new DevMindSecurityProperties(5, 10, 0, 0, 7, 2, ""),
                new InMemoryRateLimitStore(),
                new SimpleMeterRegistry()
        );
        when(request.getRequestURI()).thenReturn("/api/knowledge-bases");
        for (int i = 0; i < 10; i++) {
            assertThat(interceptor.preHandle(request, response, null)).isTrue();
        }
    }
}
