package com.devmind.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存任务队列（无 Redis 兜底）：基于线程池直接执行 + in-flight 防重入。
 * 与原 {@code submitTask} 行为一致，用于单机部署/测试环境。
 */
public class InMemoryTaskQueue implements TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskQueue.class);

    private final TaskExecutor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private volatile TaskHandler handler;

    public InMemoryTaskQueue(TaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void enqueue(Long taskId) {
        if (taskId == null || !inFlight.add(taskId)) {
            return;
        }
        executor.execute(() -> {
            try {
                TaskHandler current = handler;
                if (current != null) {
                    current.handle(taskId);
                }
            } catch (Exception ex) {
                log.warn("in-memory task {} failed: {}", taskId, ex.getMessage());
            } finally {
                inFlight.remove(taskId);
            }
        });
    }

    @Override
    public void start(TaskHandler handler) {
        this.handler = handler;
    }

    @Override
    public java.util.List<Long> drainDead() {
        // 内存队列无死信概念，恒为空
        return java.util.List.of();
    }
}
