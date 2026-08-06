package com.devmind.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerTest {

    @Mock
    private WorkflowRepository repository;

    @Mock
    private WorkflowExecutor executor;

    @Mock
    private WorkflowRunRepository runRepository;

    private WorkflowScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WorkflowScheduler(repository, executor, runRepository);
    }

    private Workflow cronWorkflow(Long id, String cron, Long createdBy) {
        return new Workflow(id, 1L, "定时流程" + id, null, "[{\"tool\":\"a\",\"params\":{}}]",
                "cron", cron, "private", "ENABLED", createdBy, null);
    }

    @Test
    void triggersWhenCronMatchesCurrentMinute() {
        // 每分钟触发的 cron，必然命中当前分钟窗口
        when(repository.listEnabledByTrigger(1L, "cron"))
                .thenReturn(List.of(cronWorkflow(1L, "0 * * * * *", 7L)));

        scheduler.scanCronWorkflows();

        verify(executor, timeout(5000)).execute(any(), eq(7L), eq("cron"));
    }

    @Test
    void doesNotTriggerWhenCronDoesNotMatch() {
        // 每天 23:59 触发的 cron，通常不命中当前分钟（除非恰好那一刻）
        when(repository.listEnabledByTrigger(1L, "cron"))
                .thenReturn(List.of(cronWorkflow(1L, "59 23 * * * *", 7L)));

        scheduler.scanCronWorkflows();

        verify(executor, never()).execute(any(), any(), eq("cron"));
    }

    @Test
    void doesNotTriggerTwiceForSameMinute() {
        when(repository.listEnabledByTrigger(1L, "cron"))
                .thenReturn(List.of(cronWorkflow(1L, "0 * * * * *", 7L)));

        scheduler.scanCronWorkflows();
        scheduler.scanCronWorkflows(); // 同分钟再次扫描

        verify(executor, timeout(5000).times(1)).execute(any(), eq(7L), eq("cron"));
    }

    @Test
    void skipsWorkflowsWithBlankCron() {
        when(repository.listEnabledByTrigger(1L, "cron"))
                .thenReturn(List.of(cronWorkflow(1L, "  ", 7L)));

        scheduler.scanCronWorkflows();

        verify(executor, never()).execute(any(), any(), eq("cron"));
    }
}
