package com.devmind.security;

import com.devmind.config.DevMindSecurityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口限流（固定窗口，按「路径 + 用户」维度，60 秒窗口）。
 * 登录/注册接口使用更严格的独立配额；limit <= 0 表示关闭限流。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final int loginLimitPerMinute;
    private final Counter limitedCounter;

    public RateLimitInterceptor(DevMindSecurityProperties security, MeterRegistry meterRegistry) {
        this.limitPerMinute = security.rateLimitPerMinute();
        this.loginLimitPerMinute = security.rateLimitLoginPerMinute();
        this.limitedCounter = Counter.builder("devmind.rate.limited")
                .description("被限流拦截的请求次数")
                .register(meterRegistry);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        boolean loginPath = uri.startsWith("/api/auth/login") || uri.startsWith("/api/auth/register");
        int limit = loginPath ? loginLimitPerMinute : limitPerMinute;
        if (limit <= 0) {
            return true;
        }
        String userKey = request.getHeader("X-User-Id");
        if (userKey == null || userKey.isBlank()) {
            userKey = request.getRemoteAddr();
        }
        String windowKey = uri + "|" + userKey;
        long now = System.currentTimeMillis();
        Window window = windows.compute(windowKey, (k, existing) -> {
            if (existing == null || now - existing.start >= WINDOW_MS) {
                return new Window(now, 1);
            }
            existing.count++;
            return existing;
        });
        if (window.count > limit) {
            limitedCounter.increment();
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("""
                    {"code":"RATE_LIMITED","message":"请求过于频繁，请稍后再试","traceId":null,"timestamp":"%s"}
                    """.formatted(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
            return false;
        }
        return true;
    }

    private static class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }
}
