package com.devmind.document;

/**
 * 文档处理任务队列抽象：
 * - {@link RedisStreamTaskQueue}：基于 Redis Stream（消费者组，多实例负载均衡/故障转移）
 * - {@link InMemoryTaskQueue}：单机内存 + 线程池（无 Redis 环境的兜底，也是测试默认模式）
 *
 * 可靠性分层：MQ 只负责异步投递与分发，任务的最终一致性由
 * document_task 状态机 + 启动恢复 + 定时扫描兜底（见 DocumentTaskService）。
 */
public interface TaskQueue {

    /**
     * 提交任务（可重复提交，消费端按任务 ID 幂等处理）。
     *
     * @param taskId 文档处理任务 ID
     */
    void enqueue(Long taskId);

    /**
     * 启动消费。幂等：重复调用只启动一次。
     *
     * @param handler 任务处理回调（收到 taskId 后执行）
     */
    void start(TaskHandler handler);

    /** 任务处理回调 */
    @FunctionalInterface
    interface TaskHandler {
        void handle(Long taskId) throws Exception;
    }
}
