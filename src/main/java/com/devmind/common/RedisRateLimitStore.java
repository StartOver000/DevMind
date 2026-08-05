package com.devmind.common;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * Redis 限流存储：固定窗口 + Lua 脚本原子执行（INCR + PEXPIRE + 判断），
 * 多实例共享同一窗口，避免多实例下限流上限被绕过。
 * 窗口从该 key 首次请求起算（固定窗口近似，与内存实现语义一致）。
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final String LUA_ALLOW = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if count > tonumber(ARGV[1]) then
              return 0
            end
            return 1
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> allowScript = new DefaultRedisScript<>(LUA_ALLOW, Long.class);

    public RedisRateLimitStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean allow(String key, int limit, long windowMs) {
        if (limit <= 0) {
            return true;
        }
        Long result = redis.execute(allowScript, List.of(key), String.valueOf(limit), String.valueOf(windowMs));
        return result != null && result == 1L;
    }
}
