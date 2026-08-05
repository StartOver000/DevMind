package com.devmind.config;

import com.devmind.document.InMemoryTaskQueue;
import com.devmind.document.RedisStreamTaskQueue;
import com.devmind.document.TaskQueue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 任务队列装配：
 * - devmind.task-queue.mode=redis   → Redis Stream（多实例负载均衡/故障转移）
 * - devmind.task-queue.mode=memory  → 内存线程池（默认，无 Redis 环境的兜底）
 */
@Configuration
public class TaskQueueConfig {

    @Bean("taskQueue")
    @ConditionalOnProperty(name = "devmind.task-queue.mode", havingValue = "redis")
    public TaskQueue redisTaskQueue(StringRedisTemplate redisTemplate) {
        return new RedisStreamTaskQueue(redisTemplate);
    }

    @Bean("taskQueue")
    @ConditionalOnProperty(name = "devmind.task-queue.mode", havingValue = "memory", matchIfMissing = true)
    public TaskQueue inMemoryTaskQueue(@Qualifier("documentTaskExecutor") TaskExecutor taskExecutor) {
        return new InMemoryTaskQueue(taskExecutor);
    }
}
