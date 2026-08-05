package com.devmind.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis Stream 的任务队列（消费者组模式）：
 * - 生产：XADD 到 {@code devmind:task:stream}（消息体 = taskId + 尝试次数）
 * - 消费：XREADGROUP 消费者组阻塞读取，多实例天然负载均衡；处理成功 XACK
 * - 失败重投：消费异常时 XACK 原消息 + XADD 回流（attempt+1，上限 3 次），超限进死信队列 {@code devmind:task:dlq}
 * - 可靠性兜底：Pending 未 ACK 的消息由 document_task 状态机 + 定时扫描重新入队（见 DocumentTaskService）；
 *   死信队列由状态机扫描消费并标记任务 DEAD 终态
 */
public class RedisStreamTaskQueue implements TaskQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamTaskQueue.class);

    static final String STREAM_KEY = "devmind:task:stream";
    static final String DLQ_KEY = "devmind:task:dlq";
    static final String GROUP = "devmind-task-group";
    /** 单条消息最大尝试次数 */
    static final int MAX_ATTEMPT = 3;
    /** 每次批量拉取条数 */
    private static final int BATCH_SIZE = 10;
    /** 阻塞读取超时（毫秒） */
    private static final long POLL_BLOCK_MS = 2000L;
    /** 消费出错后重试间隔（毫秒） */
    private static final long POLL_ERROR_BACKOFF_MS = 500L;
    static final String FIELD_BODY = "body";
    static final String FIELD_ATTEMPT = "attempt";

    private final StringRedisTemplate redis;
    private final String consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread workerThread;
    private volatile boolean groupReady;

    public RedisStreamTaskQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("null")
    public void enqueue(Long taskId) {
        if (taskId == null) {
            return;
        }
        add(taskId, 1);
    }

    @SuppressWarnings("null")
    private void add(Long taskId, int attempt) {
        MapRecord<String, String, String> record = StreamRecords.mapBacked(
                        Map.of(FIELD_BODY, String.valueOf(taskId), FIELD_ATTEMPT, String.valueOf(attempt)))
                .withStreamKey(STREAM_KEY)
                .withId(RecordId.autoGenerate());
        ops().add(record);
    }

    @Override
    public void start(TaskHandler handler) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            while (running.get()) {
                try {
                    pollOnce(handler);
                } catch (Exception ex) {
                    log.warn("redis stream 消费异常: {}", ex.getMessage());
                    sleep(POLL_ERROR_BACKOFF_MS);
                }
            }
        }, "redis-task-consumer");
        thread.setDaemon(true);
        workerThread = thread;
        thread.start();
    }

    /** 停止消费（Spring 关闭时由容器调用） */
    public void stop() {
        running.set(false);
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 单批消费：XREADGROUP 拉取一批 → 逐条处理 → 成功 ACK / 失败重投。
     * package-private 便于单元测试。
     */
    @SuppressWarnings({"unchecked", "null"})
    void pollOnce(TaskHandler handler) {
        ensureGroup();
        List<MapRecord<String, String, String>> records = ops().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(BATCH_SIZE).block(Duration.ofMillis(POLL_BLOCK_MS)),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return;
        }
        for (MapRecord<String, String, String> record : records) {
            String recordId = record.getId().getValue();
            Map<String, String> value = record.getValue();
            Long taskId = parseLong(value == null ? null : value.get(FIELD_BODY));
            boolean ok = false;
            if (taskId != null) {
                try {
                    handler.handle(taskId);
                    ok = true;
                } catch (Exception ex) {
                    log.warn("任务 {} 消费执行失败: {}", taskId, ex.getMessage());
                }
            }
            // 无论如何先 ACK（失败通过重投实现，避免 Pending 无限堆积；任务不会丢，DB 状态机兜底）
            ops().acknowledge(STREAM_KEY, GROUP, recordId);
            if (!ok) {
                retry(taskId, value);
            }
        }
    }

    /** 失败重投：尝试次数 < 上限则回流 +1，否则写入死信队列（任务最终由 DB 状态机扫描标记 DEAD） */
    private void retry(Long taskId, Map<String, String> value) {
        if (taskId == null) {
            return;
        }
        int attempt = parseLong(value == null ? null : value.get(FIELD_ATTEMPT)).intValue();
        if (attempt >= MAX_ATTEMPT) {
            addToDlq(taskId);
            return;
        }
        add(taskId, attempt + 1);
    }

    /** 消息级重试超限：写入死信队列；Redis 不可用时记录错误（DB 状态机仍会继续兜底） */
    @SuppressWarnings("null")
    private void addToDlq(Long taskId) {
        try {
            MapRecord<String, String, String> record = StreamRecords.mapBacked(Map.of(FIELD_BODY, String.valueOf(taskId)))
                    .withStreamKey(DLQ_KEY)
                    .withId(RecordId.autoGenerate());
            ops().add(record);
            log.error("任务 {} 消息级重试超限（attempt={}），已进入死信队列 {}", taskId, MAX_ATTEMPT, DLQ_KEY);
        } catch (Exception ex) {
            log.error("任务 {} 写入死信队列失败: {}", taskId, ex.getMessage());
        }
    }

    /** 消费死信队列：返回并清除其中任务 ID；Redis 不可用时返回空（由 DB 状态机继续兜底） */
    @Override
    @SuppressWarnings("null")
    public List<Long> drainDead() {
        try {
            List<MapRecord<String, String, String>> records = ops().range(DLQ_KEY, Range.unbounded());
            if (records == null || records.isEmpty()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>(records.size());
            for (MapRecord<String, String, String> record : records) {
                Long taskId = parseLong(record.getValue() == null ? null : record.getValue().get(FIELD_BODY));
                if (taskId != null) {
                    ids.add(taskId);
                }
                ops().delete(DLQ_KEY, record.getId());
            }
            return ids;
        } catch (Exception ex) {
            log.warn("死信队列消费失败（Redis 不可用）: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 确保消费者组存在；stream 未创建时静默等待（消息入队 XADD 会自动建 stream） */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            ops().createGroup(STREAM_KEY, GROUP);
            groupReady = true;
        } catch (Exception ex) {
            // 已存在 或 stream 尚未创建，稍后重试
        }
    }

    /** StringRedisTemplate 的 StreamOperations（泛型固定为 String 键/字段） */
    @SuppressWarnings("unchecked")
    private StreamOperations<String, String, String> ops() {
        return (StreamOperations<String, String, String>) (StreamOperations<?, ?, ?>) redis.opsForStream();
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
