package com.devmind.config;

import com.devmind.common.CircuitStateStore;
import com.devmind.common.InMemoryCircuitStateStore;
import com.devmind.common.InMemoryRateLimitStore;
import com.devmind.common.RateLimitStore;
import com.devmind.common.RedisCircuitStateStore;
import com.devmind.common.RedisRateLimitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 本地状态（限流计数 / 熔断状态）存储装配：
 * - devmind.state-store=redis  → Redis 原子实现（多实例共享，生产推荐）
 * - devmind.state-store=memory → JVM 本地（单机/测试默认）
 */
@Configuration
public class StateStoreConfig {

    @Bean("rateLimitStore")
    @ConditionalOnProperty(name = "devmind.state-store", havingValue = "redis")
    public RateLimitStore redisRateLimitStore(StringRedisTemplate redisTemplate) {
        return new RedisRateLimitStore(redisTemplate);
    }

    @Bean("rateLimitStore")
    @ConditionalOnProperty(name = "devmind.state-store", havingValue = "memory", matchIfMissing = true)
    public RateLimitStore inMemoryRateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    @Bean("circuitStateStore")
    @ConditionalOnProperty(name = "devmind.state-store", havingValue = "redis")
    public CircuitStateStore redisCircuitStateStore(StringRedisTemplate redisTemplate) {
        return new RedisCircuitStateStore(redisTemplate);
    }

    @Bean("circuitStateStore")
    @ConditionalOnProperty(name = "devmind.state-store", havingValue = "memory", matchIfMissing = true)
    public CircuitStateStore inMemoryCircuitStateStore() {
        return new InMemoryCircuitStateStore();
    }
}
