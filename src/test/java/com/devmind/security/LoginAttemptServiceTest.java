package com.devmind.security;

import com.devmind.config.DevMindSecurityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        // 3 次失败锁定 10 分钟
        service = new LoginAttemptService(
                new DevMindSecurityProperties(3, 10, 120, 10, 7, 2, ""),
                new SimpleMeterRegistry()
        );
    }

    @Test
    void unlocksAfterFailures() {
        assertThat(service.isLocked("demo")).isFalse();
        service.recordFailure("demo");
        service.recordFailure("demo");
        assertThat(service.isLocked("demo")).isFalse();
        service.recordFailure("demo");
        assertThat(service.isLocked("demo")).isTrue();
        assertThat(service.remainingLockSeconds("demo")).isGreaterThan(0);
    }

    @Test
    void resetClearsLock() {
        service.recordFailure("demo");
        service.recordFailure("demo");
        service.recordFailure("demo");
        assertThat(service.isLocked("demo")).isTrue();
        service.reset("demo");
        assertThat(service.isLocked("demo")).isFalse();
    }

    @Test
    void lockIsPerUsername() {
        service.recordFailure("alice");
        service.recordFailure("alice");
        service.recordFailure("alice");
        assertThat(service.isLocked("alice")).isTrue();
        assertThat(service.isLocked("bob")).isFalse();
    }
}
