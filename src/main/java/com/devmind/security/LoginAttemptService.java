package com.devmind.security;

import com.devmind.config.DevMindSecurityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败次数限制：同一用户名连续失败达到阈值后锁定一段时间，防暴力破解。
 */
@Component
public class LoginAttemptService {

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration lockDuration;
    private final Counter lockedCounter;

    public LoginAttemptService(DevMindSecurityProperties security, MeterRegistry meterRegistry) {
        this.maxFailures = Math.max(security.loginMaxFailures(), 1);
        this.lockDuration = Duration.ofMinutes(Math.max(security.loginLockMinutes(), 1));
        this.lockedCounter = Counter.builder("devmind.login.locked")
                .description("账号被锁定的次数")
                .register(meterRegistry);
    }

    public boolean isLocked(String username) {
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return false;
        }
        if (attempt.lockedUntil.isAfter(Instant.now())) {
            return true;
        }
        // 仅在锁定确实已过期时清理（未锁定过则保留失败计数）
        if (attempt.lockedUntil != Instant.MIN) {
            attempts.remove(username);
        }
        return false;
    }

    public long remainingLockSeconds(String username) {
        Attempt attempt = attempts.get(username);
        if (attempt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(Instant.now(), attempt.lockedUntil).getSeconds());
    }

    public void recordFailure(String username) {
        attempts.compute(username, (k, existing) -> {
            Attempt current = existing == null ? new Attempt() : existing;
            current.failures++;
            if (current.failures >= maxFailures) {
                current.lockedUntil = Instant.now().plus(lockDuration);
                lockedCounter.increment();
            }
            return current;
        });
    }

    public void reset(String username) {
        attempts.remove(username);
    }

    private static class Attempt {
        private int failures;
        private Instant lockedUntil = Instant.MIN;
    }
}
