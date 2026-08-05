package com.devmind.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "null"})
class RedisCircuitStateStoreTest {

    @Mock
    private StringRedisTemplate redis;

    private RedisCircuitStateStore store;

    @BeforeEach
    void setUp() {
        store = new RedisCircuitStateStore(redis);
    }

    @Test
    void recordFailureMapsRedisResult() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(2L);
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(2);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(1);

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(store.recordFailure("primary", 3, false, 60_000)).isEqualTo(0);
    }

    @Test
    void recordFailurePassesKeysAndArgs() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        store.recordFailure("primary", 3, true, 60_000);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly(
                "devmind:circuit:primary:open",
                "devmind:circuit:primary:failures"
        );
        assertThat(args.getValue()).containsExactly("3", "1", "60000");
    }

    @Test
    void isOpenMapsRedisResult() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertThat(store.isOpen("primary")).isTrue();

        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(store.isOpen("primary")).isFalse();
    }

    @Test
    void resetInvokesScript() {
        store.reset("primary");
        verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
