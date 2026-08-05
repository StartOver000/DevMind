package com.devmind.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes", "null"})
class RedisStreamTaskQueueTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private StreamOperations ops;

    private RedisStreamTaskQueue queue;

    @BeforeEach
    void setUp() {
        when(redis.opsForStream()).thenReturn(ops);
        queue = new RedisStreamTaskQueue(redis);
    }

    private MapRecord<String, String, String> record(String body, String attempt, String id) {
        return StreamRecords.mapBacked(Map.of("body", body, "attempt", attempt))
                .withStreamKey(RedisStreamTaskQueue.STREAM_KEY)
                .withId(RecordId.of(id));
    }

    @Test
    void enqueueAddsMessageToStream() {
        queue.enqueue(42L);

        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(ops).add(captor.capture());
        MapRecord<String, String, String> sent = captor.getValue();
        assertThat(sent.getValue().get("body")).isEqualTo("42");
        assertThat(sent.getValue().get("attempt")).isEqualTo("1");
        assertThat(sent.getStream()).isEqualTo(RedisStreamTaskQueue.STREAM_KEY);
    }

    @Test
    void pollOnceHandlesAndAcksOnSuccess() throws Exception {
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record("42", "1", "1690000000000-0")));
        TaskQueue.TaskHandler handler = mock(TaskQueue.TaskHandler.class);

        queue.pollOnce(handler);

        verify(handler).handle(42L);
        verify(ops).acknowledge(eq(RedisStreamTaskQueue.STREAM_KEY), eq(RedisStreamTaskQueue.GROUP), eq("1690000000000-0"));
        // 成功不重投
        verify(ops, never()).add(any(MapRecord.class));
    }

    @Test
    void pollOnceRequeuesOnFailureWithIncrementedAttempt() throws Exception {
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record("42", "1", "1690000000000-0")));
        TaskQueue.TaskHandler handler = mock(TaskQueue.TaskHandler.class);
        doThrow(new RuntimeException("boom")).when(handler).handle(any());

        queue.pollOnce(handler);

        // 失败仍 ACK（避免 Pending 堆积）
        verify(ops).acknowledge(eq(RedisStreamTaskQueue.STREAM_KEY), eq(RedisStreamTaskQueue.GROUP), eq("1690000000000-0"));
        // 回流重投：attempt=2
        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(ops, times(1)).add(captor.capture());
        Map<String, String> resent = (Map<String, String>) captor.getValue().getValue();
        assertThat(resent.get("attempt")).isEqualTo("2");
    }

    @Test
    void pollOnceSendsToDlqWhenAttemptExceeded() throws Exception {
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record("42", "3", "1690000000000-0")));
        TaskQueue.TaskHandler handler = mock(TaskQueue.TaskHandler.class);
        doThrow(new RuntimeException("boom")).when(handler).handle(any());

        queue.pollOnce(handler);

        verify(ops).acknowledge(eq(RedisStreamTaskQueue.STREAM_KEY), eq(RedisStreamTaskQueue.GROUP), eq("1690000000000-0"));
        // attempt 已达上限：不再回流主 stream，改为写入死信队列
        ArgumentCaptor<MapRecord> captor = ArgumentCaptor.forClass(MapRecord.class);
        verify(ops, times(1)).add(captor.capture());
        assertThat(captor.getValue().getStream()).isEqualTo(RedisStreamTaskQueue.DLQ_KEY);
        Map<String, String> sent = (Map<String, String>) captor.getValue().getValue();
        assertThat(sent.get("body")).isEqualTo("42");
    }

    @Test
    void drainDeadReturnsAndDeletesDlqMessages() {
        MapRecord<String, String, String> dlq1 = StreamRecords.mapBacked(Map.of("body", "7"))
                .withStreamKey(RedisStreamTaskQueue.DLQ_KEY).withId(RecordId.of("1-0"));
        MapRecord<String, String, String> dlq2 = StreamRecords.mapBacked(Map.of("body", "9"))
                .withStreamKey(RedisStreamTaskQueue.DLQ_KEY).withId(RecordId.of("2-0"));
        when(ops.range(eq(RedisStreamTaskQueue.DLQ_KEY), any(Range.class))).thenReturn(List.of(dlq1, dlq2));

        List<Long> ids = queue.drainDead();

        assertThat(ids).containsExactly(7L, 9L);
        verify(ops).delete(RedisStreamTaskQueue.DLQ_KEY, RecordId.of("1-0"));
        verify(ops).delete(RedisStreamTaskQueue.DLQ_KEY, RecordId.of("2-0"));
    }

    @Test
    void drainDeadReturnsEmptyWhenDlqEmpty() {
        when(ops.range(any(), any())).thenReturn(List.of());

        assertThat(queue.drainDead()).isEmpty();
    }

    @Test
    void drainDeadSkipsInvalidBodyButStillDeletes() {
        MapRecord<String, String, String> bad = StreamRecords.mapBacked(Map.of("body", "not-a-number"))
                .withStreamKey(RedisStreamTaskQueue.DLQ_KEY).withId(RecordId.of("1-0"));
        when(ops.range(eq(RedisStreamTaskQueue.DLQ_KEY), any(Range.class))).thenReturn(List.of(bad));

        assertThat(queue.drainDead()).isEmpty();
        verify(ops).delete(RedisStreamTaskQueue.DLQ_KEY, RecordId.of("1-0"));
    }

    @Test
    void pollOnceSkipsRecordWithInvalidBody() throws Exception {
        when(ops.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record("not-a-number", "1", "1690000000000-0")));
        TaskQueue.TaskHandler handler = mock(TaskQueue.TaskHandler.class);

        queue.pollOnce(handler);

        verify(handler, never()).handle(any());
        // 无效 body 也 ACK，避免卡死队列
        verify(ops).acknowledge(eq(RedisStreamTaskQueue.STREAM_KEY), eq(RedisStreamTaskQueue.GROUP), eq("1690000000000-0"));
    }

    @Test
    void startIsIdempotentAndDoesNotThrow() {
        TaskQueue.TaskHandler handler = mock(TaskQueue.TaskHandler.class);
        // 连续两次 start 不抛异常（第二次不会重复启动消费线程）
        queue.start(handler);
        queue.start(handler);
        queue.stop();
    }
}
