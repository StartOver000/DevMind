package com.devmind.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 定时工作流分布式锁（P0-1 多实例安全）：
 * cron 调度在 app/app2 双实例下各自扫描，进程内 lastTriggerMinute 防重不跨实例共享，
 * 同一 cron 分钟两个实例可能都判定"该触发"（毫秒级竞态窗口 → 双 run）。
 * 本锁用 Redis SETNX（key=devmind:cron:{workflowId}:{minuteKey}，TTL 2 分钟）保证同一触发分钟只有一个实例提交执行。
 * Redis 不可用/异常时回退 true（单机部署进程内防重兜底，不阻断调度）。
 */
@Component
@SuppressWarnings("null")
public class CronLock {

    private static final Logger log = LoggerFactory.getLogger(CronLock.class);
    /** 锁 TTL：2 分钟（一次 cron run 通常远小于此；到期自动释放防死锁） */
    private static final Duration LOCK_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final String instanceId;

    public CronLock(StringRedisTemplate redis) {
        this.redis = redis;
        this.instanceId = "scheduler-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 尝试获取某工作流在指定触发分钟的分布式锁。
     *
     * @return true 表示本实例应触发（拿到锁或 Redis 不可用回退）；false 表示已有实例触发
     */
    public boolean tryAcquire(Long workflowId, String minuteKey) {
        try {
            String key = "devmind:cron:" + workflowId + ":" + minuteKey;
            Boolean acquired = redis.opsForValue().setIfAbsent(key, instanceId, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            log.info("cron 触发锁已被其他实例持有（workflow={}, minute={}），本实例跳过", workflowId, minuteKey);
            return false;
        } catch (Exception ex) {
            // Redis 不可用（单机/降级）：回退进程内防重（调用方 lastTriggerMinute 兜底）
            log.warn("cron 分布式锁不可用（回退进程内防重）: {}", ex.getMessage());
            return true;
        }
    }
}
