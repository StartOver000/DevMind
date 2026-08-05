package com.devmind.common;

/**
 * 熔断状态存储抽象：
 * - {@link InMemoryCircuitStateStore}：JVM 本地（单机/测试默认）
 * - {@link RedisCircuitStateStore}：Redis 原子状态（多实例共享，A 熔断 B 同步感知）
 *
 * 状态：连续失败计数 + 熔断打开标记（带打开时长，到期自动半开放行）。
 */
public interface CircuitStateStore {

    /** 记录一次失败：2=熔断已打开（快速失败）；1=本次触发熔断；0=未熔断 */
    int recordFailure(String key, int failureThreshold, boolean rateLimited, long openMs);

    /** 熔断是否打开（打开到期自动重置并放行，即半开） */
    boolean isOpen(String key);

    /** 成功调用时重置熔断状态 */
    void reset(String key);
}
