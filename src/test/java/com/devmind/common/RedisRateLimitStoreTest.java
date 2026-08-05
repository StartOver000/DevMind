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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "null"})
class RedisRateLimitStoreTest {

    @Mock
    private StringRedisTemplate redis;

    private RedisRateLimitStore store;

    @BeforeEach
    void setUp() {
        store = new RedisRateLimitStore(redis);
    }

    @Test
    void allowsWhenRedisReturnsOne() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertThat(store.allow("uri|user", 10, 60_000)).isTrue();
    }

    @Test
    void blocksWhenRedisReturnsZero() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(store.allow("uri|user", 10, 60_000)).isFalse();
    }

    @Test
    void passesKeyLimitAndWindow() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        store.allow("/api/chat|1", 5, 60_000);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly("/api/chat|1");
        assertThat(args.getValue()).containsExactly("5", "60000");
    }

    @Test
    void limitZeroSkipsRedis() {
        assertThat(store.allow("uri|user", 0, 60_000)).isTrue();
        assertThat(store.allow("uri|user", -1, 60_000)).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
}
