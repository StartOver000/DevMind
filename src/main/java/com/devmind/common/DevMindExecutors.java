package com.devmind.common;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DevMind 统一线程池工厂（八股反推 P1-2）。
 *
 * 替代散落的 {@code Executors.newCachedThreadPool()}（无界，高并发可耗尽资源），
 * 统一提供"命名线程 + 有界队列 + 显式拒绝策略"的有界线程池。
 * 各场景按语义设参（工具执行 / webhook 异步 / 工作流并行 / 定时触发），但结构统一，
 * 便于面试讲"统一线程池治理 + 按场景设参"。
 */
public final class DevMindExecutors {

    private DevMindExecutors() {
    }

    /**
     * Agent 工具执行池：每次 Agent 一轮可并行多个工具，给足并发又设上限。
     * 拒绝策略：CallerRuns——队列满时由调用线程执行，避免任务丢失（工具调用可等）。
     */
    public static ExecutorService toolExecutor() {
        return newFixed("devmind-agent-tool", 8, 16, 128, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Webhook 异步触发池：外部回调并发可控，拒绝直接报错由调用方感知 */
    public static ExecutorService webhookAsync() {
        return newFixed("devmind-webhook", 4, 8, 64, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 工作流并行组执行池：并行步骤数有限（不会无限膨胀） */
    public static ExecutorService workflowParallel() {
        return newFixed("devmind-workflow-parallel", 4, 8, 64, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 定时触发执行池：调度触发频率有限，小池足够 */
    public static ExecutorService scheduler() {
        return newFixed("devmind-scheduler", 2, 4, 32, new ThreadPoolExecutor.AbortPolicy());
    }

    private static ExecutorService newFixed(
            String prefix,
            int core,
            int max,
            int queueCapacity,
            RejectedExecutionHandler handler
    ) {
        return new ThreadPoolExecutor(
                core,
                max,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(prefix),
                handler
        );
    }

    /** 命名线程工厂：便于线程转储排查（谁创建的线程一眼可见） */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger();

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
