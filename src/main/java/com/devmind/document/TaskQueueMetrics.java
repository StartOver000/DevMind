package com.devmind.document;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务队列可观测：定时抓取 Redis Stream 未消费消息数（XLEN）暴露为 Gauge，
 * 供 Grafana 展示任务积压。Redis 不可用时静默保持上次值，不影响主流程。
 */
@Component
public class TaskQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueMetrics.class);

    private static final String STREAM_KEY = "devmind:task:stream";

    private final StringRedisTemplate redis;
    private final AtomicLong queueLength = new AtomicLong(0);

    public TaskQueueMetrics(MeterRegistry registry, StringRedisTemplate redis) {
        this.redis = redis;
        Gauge.builder("devmind.task.queue.length", queueLength, AtomicLong::get)
                .description("文档任务队列积压（Redis Stream 未消费消息数）")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "30000", initialDelayString = "15000")
    public void refresh() {
        try {
            Long length = redis.execute((RedisCallback<Long>) connection ->
                    connection.xLen(STREAM_KEY.getBytes(StandardCharsets.UTF_8)));
            queueLength.set(length == null ? 0 : length);
        } catch (Exception ex) {
            log.debug("任务队列积压抓取失败（Redis 不可用）: {}", ex.getMessage());
        }
    }
}
