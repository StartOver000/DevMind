package com.devmind.common;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;

/**
 * SSE 推送辅助组件：
 * - 统一创建 {@link SseEmitter}（长超时，覆盖模型 30s 超时 + 分片推送耗时）；
 * - 把耗时业务逻辑放到独立线程执行（不能阻塞 servlet 线程）；
 * - 统一事件发送（delta 为纯文本，meta/trace/done/error 为 JSON）。
 */
@Component
public class SsePusher {

    /** 连接超时：120s，覆盖模型读取 30s 超时 + 多轮工具调用 + 分片推送 */
    private static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            8, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
            new ThreadPoolExecutor.AbortPolicy());

    public SseEmitter createEmitter() {
        return new SseEmitter(DEFAULT_TIMEOUT_MS);
    }

    /** 在独立线程执行流式任务；异常统一转为 error 事件后结束连接 */
    @SuppressWarnings("null")
    public void async(SseEmitter emitter, Runnable task) {
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        Runnable cancel = () -> {
            Future<?> future = futureRef.get();
            if (future != null) {
                future.cancel(true);
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception ex) {
                    try {
                        emitter.send(SseEmitter.event().name("error").data(Map.of("message", safeMessage(ex))));
                    } catch (IOException ignored) {
                        // 连接已断开，无需再发
                    }
                    emitter.completeWithError(ex);
                }
            });
            futureRef.set(future);
        } catch (RejectedExecutionException ex) {
            emitter.completeWithError(ex);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 发送纯文本增量块 */
    @SuppressWarnings("null")
    public void sendDelta(SseEmitter emitter, String chunk) throws IOException {
        emitter.send(SseEmitter.event().name("delta").data(chunk));
    }

    /** 发送 JSON 事件（meta / trace / done / error） */
    @SuppressWarnings("null")
    public void sendJson(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }
}
