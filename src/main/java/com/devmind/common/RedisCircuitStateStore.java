package com.devmind.common;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Redis 熔断状态：Lua 脚本原子实现，多实例共享熔断状态——
 * A 实例熔断打开，B 实例同步快速失败；打开到期 GET 返回 nil 即半开放行。
 */
@SuppressWarnings("null")
public class RedisCircuitStateStore implements CircuitStateStore {

    /** 记录失败：已打开→2；达到阈值或 429→打开并返回 1；否则 0 */
    private static final String LUA_RECORD = """
            if redis.call('GET', KEYS[1]) then
              return 2
            end
            local c = redis.call('INCR', KEYS[2])
            redis.call('EXPIRE', KEYS[2], 600)
            if ARGV[2] == '1' or c >= tonumber(ARGV[1]) then
              redis.call('SET', KEYS[1], '1', 'PX', ARGV[3])
              return 1
            end
            return 0
            """;

    /** 是否打开：GET openKey；到期自动 DEL failures（半开放行） */
    private static final String LUA_OPEN = """
            if redis.call('GET', KEYS[1]) then
              return 1
            end
            redis.call('DEL', KEYS[2])
            return 0
            """;

    private static final String LUA_RESET = """
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            return 0
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> recordScript = new DefaultRedisScript<>(LUA_RECORD, Long.class);
    private final DefaultRedisScript<Long> openScript = new DefaultRedisScript<>(LUA_OPEN, Long.class);
    private final DefaultRedisScript<Long> resetScript = new DefaultRedisScript<>(LUA_RESET, Long.class);

    public RedisCircuitStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public int recordFailure(String key, int failureThreshold, boolean rateLimited, long openMs) {
        Long result = redis.execute(
                recordScript,
                List.of(openKey(key), failKey(key)),
                String.valueOf(failureThreshold),
                rateLimited ? "1" : "0",
                String.valueOf(openMs)
        );
        return result == null ? 0 : result.intValue();
    }

    @Override
    public boolean isOpen(String key) {
        Long result = redis.execute(openScript, List.of(openKey(key), failKey(key)));
        return result != null && result == 1L;
    }

    @Override
    public void reset(String key) {
        redis.execute(resetScript, List.of(openKey(key), failKey(key)));
    }

    private String openKey(String key) {
        return "devmind:circuit:" + key + ":open";
    }

    private String failKey(String key) {
        return "devmind:circuit:" + key + ":failures";
    }
}
