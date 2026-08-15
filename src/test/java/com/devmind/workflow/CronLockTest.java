package com.devmind.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * cron 分布式锁（P0-1 多实例安全）：SETNX 决定触发权；Redis 异常回退进程内防重（单机不阻断）。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class CronLockTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Test
    void acquiresWhenRedisReturnsTrue() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        CronLock lock = new CronLock(redis);
        assertThat(lock.tryAcquire(1L, "2026-08-15T23:59")).isTrue();
    }

    @Test
    void skipsWhenLockAlreadyHeldByOtherInstance() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        CronLock lock = new CronLock(redis);
        assertThat(lock.tryAcquire(1L, "2026-08-15T23:59")).isFalse();
    }

    @Test
    void fallsBackToLocalGuardWhenRedisUnavailable() {
        when(redis.opsForValue()).thenThrow(new RuntimeException("Redis 连接失败"));

        CronLock lock = new CronLock(redis);
        // Redis 异常 → 回退 true（单机进程内防重兜底，不阻断调度）
        assertThat(lock.tryAcquire(1L, "2026-08-15T23:59")).isTrue();
    }
}
