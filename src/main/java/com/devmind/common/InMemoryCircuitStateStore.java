package com.devmind.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存熔断状态：JVM 本地实现，单机/测试默认。
 * 多实例下熔断不共享（A 熔断 B 仍会打主模型），生产多实例请用 {@link RedisCircuitStateStore}。
 */
public class InMemoryCircuitStateStore implements CircuitStateStore {

    private final ConcurrentHashMap<String, Entry> states = new ConcurrentHashMap<>();

    @Override
    public int recordFailure(String key, int failureThreshold, boolean rateLimited, long openMs) {
        Entry entry = states.computeIfAbsent(key, k -> new Entry());
        if (entry.openUntil > 0 && System.currentTimeMillis() < entry.openUntil) {
            return 2;
        }
        if (rateLimited || entry.failures.incrementAndGet() >= failureThreshold) {
            entry.openUntil = System.currentTimeMillis() + openMs;
            return 1;
        }
        return 0;
    }

    @Override
    public boolean isOpen(String key) {
        Entry entry = states.get(key);
        if (entry == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (entry.openUntil > 0 && now < entry.openUntil) {
            return true;
        }
        if (entry.openUntil > 0) {
            // 打开到期：半开放行并重置
            reset(key);
        }
        return false;
    }

    @Override
    public void reset(String key) {
        states.remove(key);
    }

    private static class Entry {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;
    }
}
