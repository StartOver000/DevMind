package com.devmind.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCircuitStateStoreTest {

    private final InMemoryCircuitStateStore store = new InMemoryCircuitStateStore();

    @Test
    void opensAfterRepeatedFailures() {
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(0);
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(0);
        // 第 3 次触发熔断
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(1);
        assertThat(store.isOpen("primary")).isTrue();
        // 已打开：快速失败
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(2);
    }

    @Test
    void rateLimitedOpensImmediately() {
        assertThat(store.recordFailure("primary", 3, true, 60_000)).isEqualTo(1);
        assertThat(store.isOpen("primary")).isTrue();
    }

    @Test
    void successResetsState() {
        store.recordFailure("primary", 3, false, 60_000);
        store.recordFailure("primary", 3, false, 60_000);
        store.reset("primary");
        assertThat(store.isOpen("primary")).isFalse();
        // 重置后重新计数
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(0);
    }

    @Test
    void differentKeysAreIsolated() {
        store.recordFailure("a", 3, true, 60_000);
        assertThat(store.isOpen("a")).isTrue();
        assertThat(store.isOpen("b")).isFalse();
    }

    @Test
    void opensHalfAfterTimeout() throws Exception {
        // 短熔断窗口：打开后到期自动半开放行
        store.recordFailure("primary", 1, false, 20);
        assertThat(store.isOpen("primary")).isTrue();
        Thread.sleep(40);
        assertThat(store.isOpen("primary")).isFalse();
        // 半开放行后可再次计数
        assertThat(store.recordFailure("primary", 1, false, 20)).isEqualTo(1);
    }
}
