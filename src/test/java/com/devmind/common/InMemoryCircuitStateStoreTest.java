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

    // ---- G7 半开探测：冷却到期只放行一个试探请求 ----

    @Test
    void halfOpenProbeAllowsOnlyOneRequestAfterTimeout() throws Exception {
        store.recordFailure("primary", 1, false, 20);
        assertThat(store.isOpen("primary")).isTrue();
        Thread.sleep(40);
        // 冷却到期：第一个请求放行（试探）
        assertThat(store.isOpen("primary")).isFalse();
        // 试探在途：其余请求仍被挡
        assertThat(store.isOpen("primary")).isTrue();
        assertThat(store.isOpen("primary")).isTrue();
    }

    @Test
    void halfOpenProbeFailureReopensCircuit() throws Exception {
        store.recordFailure("primary", 1, false, 20);
        Thread.sleep(40);
        // 放行试探
        assertThat(store.isOpen("primary")).isFalse();
        // 试探失败：重新打开完整冷却
        assertThat(store.recordFailure("primary", 1, false, 20)).isEqualTo(1);
        assertThat(store.isOpen("primary")).isTrue();
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() throws Exception {
        store.recordFailure("primary", 1, false, 20);
        Thread.sleep(40);
        assertThat(store.isOpen("primary")).isFalse(); // 放行试探
        store.reset("primary"); // 试探成功
        assertThat(store.isOpen("primary")).isFalse();
        // 关闭后失败重新计数（需 3 次才触发）
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(0);
    }
}
