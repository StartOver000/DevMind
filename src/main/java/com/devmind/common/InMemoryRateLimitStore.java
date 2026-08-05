package com.devmind.common;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存限流存储（固定窗口，按 key 维度）：JVM 本地实现，
 * 用于单机部署 / 测试环境 / 无 Redis 兜底。
 * 多实例下不共享（限流会被绕过），生产多实例请用 {@link RedisRateLimitStore}。
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean allow(String key, int limit, long windowMs) {
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.start >= windowMs) {
                return new Window(now, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= limit;
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
