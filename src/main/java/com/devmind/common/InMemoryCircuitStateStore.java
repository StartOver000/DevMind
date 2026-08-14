package com.devmind.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存熔断状态：JVM 本地实现，单机/测试默认。
 * 多实例下熔断不共享（A 熔断 B 仍会打主模型），生产多实例请用 {@link RedisCircuitStateStore}。
 * 半开探测（G7）：冷却到期后不放行全部流量，只放行一个试探请求；
 * 试探成功（reset）关闭熔断，试探失败（recordFailure）重新打开完整冷却——比"到期全放行"更稳。
 */
public class InMemoryCircuitStateStore implements CircuitStateStore {

    private final ConcurrentHashMap<String, Entry> states = new ConcurrentHashMap<>();

    @Override
    public int recordFailure(String key, int failureThreshold, boolean rateLimited, long openMs) {
        AtomicInteger result = new AtomicInteger(0);
        states.compute(key, (k, existing) -> {
            Entry entry = existing != null ? existing : new Entry();
            long now = System.currentTimeMillis();
            if (entry.openUntil > 0 && now < entry.openUntil) {
                result.set(2); // 打开中（含半开探测期）：快速失败
            } else if (entry.halfOpen) {
                // 半开试探失败：重新打开完整冷却
                entry.openUntil = now + openMs;
                entry.halfOpen = false;
                result.set(1);
            } else if (rateLimited || entry.failures.incrementAndGet() >= failureThreshold) {
                entry.openUntil = now + openMs;
                result.set(1);
            } else {
                result.set(0);
            }
            return entry;
        });
        return result.get();
    }

    @Override
    public boolean isOpen(String key) {
        AtomicBoolean open = new AtomicBoolean(false);
        states.computeIfPresent(key, (k, entry) -> {
            long now = System.currentTimeMillis();
            if (entry.openUntil > 0) {
                if (now < entry.openUntil) {
                    open.set(true); // 打开中
                } else if (!entry.halfOpen) {
                    // 冷却到期：首次进入半开，放行这一个试探请求
                    entry.openUntil = 0;
                    entry.halfOpen = true;
                    open.set(false);
                } else {
                    open.set(true); // 已有试探在途，其余请求继续挡
                }
            } else if (entry.halfOpen) {
                open.set(true); // 探测期
            }
            return entry;
        });
        return open.get();
    }

    @Override
    public void reset(String key) {
        states.remove(key);
    }

    private static class Entry {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;
        private volatile boolean halfOpen;
    }
}
