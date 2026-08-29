package com.devmind.workflow.spi;

import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowExecutor;
import com.devmind.workflow.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkflowEngine 契约测试（v1）：builtin 适配器必须把执行委托给执行器、
 * 正确映射句柄字段（runId/终态/错误），并按 engine 标记承接。
 */
@ExtendWith(MockitoExtension.class)
class BuiltinWorkflowEngineContractTest {

    @Mock
    private WorkflowExecutor executor;
    @Mock
    private Workflow workflow;

    private BuiltinWorkflowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BuiltinWorkflowEngine(executor);
    }

    @Test
    void idAndCapabilitySemantics() {
        assertThat(engine.id()).isEqualTo("builtin");
        assertThat(engine.supports(null)).isTrue();
        assertThat(engine.supports("  ")).isTrue();
        assertThat(engine.supports("builtin")).isTrue();
        assertThat(engine.supports("n8n")).isFalse();
        assertThat(engine.supports(" builtin ")).isTrue();
    }

    @Test
    void runWithoutInitialVarsDelegatesToPlainExecute() {
        when(executor.execute(workflow, 7L, "manual")).thenReturn(
                new WorkflowRun(1L, 9L, 1L, "manual", "SUCCESS", 0.0, "t0", "t1", null));

        WorkflowHandle handle = engine.run(workflow, 7L, "manual", null);

        verify(executor).execute(workflow, 7L, "manual");
        assertThat(handle.engineId()).isEqualTo("builtin");
        assertThat(handle.runId()).isEqualTo(1L);
        assertThat(handle.status()).isEqualTo("SUCCESS");
        assertThat(handle.error()).isNull();
        assertThat(handle.terminal()).isTrue();
        assertThat(handle.externalRef()).isNull();
    }

    @Test
    void runWithInitialVarsDelegatesToVarsExecute() {
        when(executor.execute(eq(workflow), eq(7L), eq("webhook"), any()))
                .thenReturn(new WorkflowRun(2L, 9L, 1L, "webhook", "FAILED", 0.01, "t0", "t1", "工具输出包含 error"));

        WorkflowHandle handle = engine.run(workflow, 7L, "webhook", Map.of("order_id", 42));

        verify(executor).execute(eq(workflow), eq(7L), eq("webhook"), any());
        assertThat(handle.status()).isEqualTo("FAILED");
        assertThat(handle.error()).isEqualTo("工具输出包含 error");
        assertThat(handle.terminal()).isTrue();
    }

    @Test
    void handleForRunningRunIsNotTerminal() {
        when(executor.execute(any(Workflow.class), anyLong(), anyString()))
                .thenReturn(new WorkflowRun(3L, 9L, 1L, "manual", "RUNNING", null, "t0", null, null));

        WorkflowHandle handle = engine.run(workflow, 7L, "manual", null);

        assertThat(handle.status()).isEqualTo("RUNNING");
        assertThat(handle.terminal()).isFalse();
    }
}
