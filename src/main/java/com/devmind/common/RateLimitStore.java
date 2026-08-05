package com.devmind.common;

/**
 * 接口限流存储抽象：
 * - {@link InMemoryRateLimitStore}：JVM 本地（单机/测试默认）
 * - {@link RedisRateLimitStore}：Redis 原子固定窗口（多实例共享，避免限流被绕过）
 */
public interface RateLimitStore {

    /**
     * 固定窗口内是否允许本次请求通过（原子地计数 + 判断）。
     *
     * @param key      窗口维度 key（如 uri|userId）
     * @param limit    窗口内上限，&lt;=0 表示关闭限流恒放行
     * @param windowMs 窗口时长（毫秒）
     * @return true 放行 / false 拒绝
     */
    boolean allow(String key, int limit, long windowMs);
}
