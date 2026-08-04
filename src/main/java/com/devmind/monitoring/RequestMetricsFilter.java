package com.devmind.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class RequestMetricsFilter extends OncePerRequestFilter {

    private final MeterRegistry meterRegistry;

    public RequestMetricsFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            meterRegistry.timer(
                    "devmind.http.requests",
                    "method", request.getMethod(),
                    "uri", request.getRequestURI(),
                    "status", String.valueOf(status)
            ).record(Duration.ofMillis(elapsedMs));
            if (status >= 500) {
                meterRegistry.counter(
                        "devmind.http.errors",
                        "method", request.getMethod(),
                        "uri", request.getRequestURI()
                ).increment();
            }
        }
    }
}
