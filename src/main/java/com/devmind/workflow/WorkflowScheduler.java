package com.devmind.workflow;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 定时工作流调度：每分钟扫描启用且 trigger_type=cron 的工作流，
 * 用 cron 表达式判定当前分钟窗口是否应触发；防重复（同一触发分钟只执行一次）。
 * 触发执行提交到独立线程池，不阻塞扫描线程。
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);
    private static final Long DEFAULT_TENANT = 1L;
    private static final DateTimeFormatter MINUTE_KEY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final WorkflowRepository repository;
    private final WorkflowExecutor executor;
    /** 每个工作流最近一次触发的分钟键（防重复触发） */
    private final Map<Long, String> lastTriggerMinute = new ConcurrentHashMap<>();
    private final ExecutorService executionPool = Executors.newCachedThreadPool();

    public WorkflowScheduler(WorkflowRepository repository, WorkflowExecutor executor) {
        this.repository = repository;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "30000", initialDelayString = "15000")
    public void scanCronWorkflows() {
        List<Workflow> cronWorkflows = repository.listEnabledByTrigger(DEFAULT_TENANT, "cron");
        if (cronWorkflows.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastMinute = now.minusMinutes(1);
        for (Workflow workflow : cronWorkflows) {
            String expr = workflow.cronExpr();
            if (expr == null || expr.isBlank()) {
                continue;
            }
            try {
                CronExpression cron = CronExpression.parse(expr);
                LocalDateTime next = cron.next(lastMinute);
                if (next == null || next.isAfter(now)) {
                    continue; // 本分钟窗口内无触发点
                }
                String minuteKey = next.format(MINUTE_KEY);
                if (minuteKey.equals(lastTriggerMinute.get(workflow.id()))) {
                    continue; // 该触发分钟已执行过
                }
                lastTriggerMinute.put(workflow.id(), minuteKey);
                executionPool.submit(() -> runScheduled(workflow));
            } catch (Exception ex) {
                log.warn("工作流 {} cron 解析失败 ({}): {}", workflow.id(), expr, ex.getMessage());
            }
        }
    }

    private void runScheduled(Workflow workflow) {
        try {
            log.info("定时触发工作流 {} (id={})", workflow.name(), workflow.id());
            executor.execute(workflow, workflow.createdBy(), "cron");
        } catch (Exception ex) {
            log.warn("定时工作流 {} 执行失败: {}", workflow.name(), ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executionPool.shutdownNow();
    }
}
