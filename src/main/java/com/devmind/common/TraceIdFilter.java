package com.devmind.common;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId：优先复用可观测链路（Micrometer Tracing）的 traceId，保证
 * 日志（Loki）与链路追踪（Tempo）可关联；无可观测上下文时生成 UUID 兜底。
 * 同时写入响应头 {@code X-Trace-Id} 供前端排查。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Span current = tracer.currentSpan();
        String traceId = (current != null && current.context().traceId() != null)
                ? current.context().traceId()
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        request.setAttribute("traceId", traceId);
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
