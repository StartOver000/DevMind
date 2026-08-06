package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.workflow.dto.WorkflowCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository repository;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowExecutor executor;

    private ToolRegistry registry;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of());
        service = new WorkflowService(repository, runRepository, executor, registry, objectMapper);
    }

    private WorkflowCreateRequest req(String stepsJson) {
        return new WorkflowCreateRequest("测试流程", null, stepsJson, "manual", null, "private", "ENABLED");
    }

    @Test
    void createAcceptsRegisteredTools() {
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "prom_buildinfo"; }
            @Override public String description() { return "查版本"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        when(repository.insert(any())).thenReturn(10L);
        when(repository.findById(1L, 10L)).thenReturn(new Workflow(
                10L, 1L, "测试流程", null, "[{\"tool\":\"prom_buildinfo\",\"params\":{}}]",
                "manual", null, "private", "ENABLED", 1L, null));

        Workflow created = service.create(req("[{\"tool\":\"prom_buildinfo\",\"params\":{}}]"), 1L);

        assertThat(created.id()).isEqualTo(10L);
        verify(repository).insert(any());
    }

    @Test
    void createRejectsUnregisteredTool() {
        assertThatThrownBy(() -> service.create(
                req("[{\"tool\":\"not_exist\",\"params\":{}}]"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未登记");
        verify(repository, never()).insert(any());
    }

    @Test
    void createRejectsEmptySteps() {
        assertThatThrownBy(() -> service.create(req("[]"), 1L))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).insert(any());
    }

    @Test
    void runOnlyWhenEnabled() {
        when(repository.findById(1L, 1L)).thenReturn(new Workflow(
                1L, 1L, "停用的流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "manual", null, "private", "DISABLED", 1L, null));

        assertThatThrownBy(() -> service.run(1L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("停用");
        verify(executor, never()).execute(any(), anyLong(), anyString());
    }

    @Test
    void runDelegatesToExecutorWhenEnabled() {
        Workflow enabled = new Workflow(
                1L, 1L, "启用流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "manual", null, "private", "ENABLED", 1L, null);
        when(repository.findById(1L, 1L)).thenReturn(enabled);
        WorkflowRun run = new WorkflowRun(5L, 1L, 1L, "manual", "SUCCESS", 0.0, null, null, null);
        when(executor.execute(enabled, 1L, "manual")).thenReturn(run);

        assertThat(service.run(1L, 1L).status()).isEqualTo("SUCCESS");
    }
}
