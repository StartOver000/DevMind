package com.devmind.workflow;

import com.devmind.agent.AgentTool;
import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import com.devmind.workflow.dto.WorkflowCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private UserService userService;

    @Mock
    private ToolAccessService toolAccessService;

    private ToolRegistry registry;
    private WorkflowService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of());
        service = new WorkflowService(
                repository, runRepository, executor, registry, objectMapper,
                userService, toolAccessService
        );
        lenient().when(userService.tenantIdOf(1L)).thenReturn(1L);
        // 默认：当前注册的工具全部对用户 1 可见
        lenient().when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenAnswer(inv -> {
            Set<String> names = new HashSet<>();
            for (AgentTool tool : registry.all()) {
                names.add(tool.name());
            }
            return names;
        });
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
    void createRejectsUnauthorizedTool() {
        // 工具已注册但用户不可见（未授权）
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "internal_api"; }
            @Override public String description() { return "内部接口"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenReturn(Set.of());

        assertThatThrownBy(() -> service.create(
                req("[{\"tool\":\"internal_api\",\"params\":{}}]"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未授权");
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

    @Test
    void createRejectsInvalidCronForCronTrigger() {
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "a"; }
            @Override public String description() { return "x"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        assertThatThrownBy(() -> service.create(
                new WorkflowCreateRequest("定时流程", null,
                        "[{\"tool\":\"a\",\"params\":{}}]", "cron", "not-a-cron", "private", "ENABLED"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cron");
        verify(repository, never()).insert(any());
    }

    @Test
    void createAcceptsValidCronForCronTrigger() {
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "a"; }
            @Override public String description() { return "x"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        when(repository.insert(any())).thenReturn(20L);
        when(repository.findById(1L, 20L)).thenReturn(new Workflow(
                20L, 1L, "定时流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "cron", "0 0 9 * * *", "private", "ENABLED", 1L, null));

        Workflow created = service.create(
                new WorkflowCreateRequest("定时流程", null,
                        "[{\"tool\":\"a\",\"params\":{}}]", "cron", "0 0 9 * * *", "private", "ENABLED"), 1L);

        assertThat(created.id()).isEqualTo(20L);
        assertThat(created.triggerType()).isEqualTo("cron");
    }

    @Test
    void createWebhookWorkflowGeneratesToken() {
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "a"; }
            @Override public String description() { return "x"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        when(repository.insert(any())).thenReturn(30L);
        when(repository.findById(1L, 30L)).thenReturn(new Workflow(
                30L, 1L, "hook流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "webhook", null, "private", "ENABLED", 1L, null));
        when(repository.findWebhookToken(1L, 30L)).thenReturn(null);

        service.create(new WorkflowCreateRequest("hook流程", null,
                "[{\"tool\":\"a\",\"params\":{}}]", "webhook", null, "private", "ENABLED"), 1L);

        verify(repository).saveWebhookToken(eq(1L), eq(30L), anyString());
    }

    @Test
    void createWebhookKeepsExistingToken() {
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "a"; }
            @Override public String description() { return "x"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        when(repository.insert(any())).thenReturn(30L);
        when(repository.findById(1L, 30L)).thenReturn(new Workflow(
                30L, 1L, "hook流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "webhook", null, "private", "ENABLED", 1L, null));
        when(repository.findWebhookToken(1L, 30L)).thenReturn("existing-token");

        service.create(new WorkflowCreateRequest("hook流程", null,
                "[{\"tool\":\"a\",\"params\":{}}]", "webhook", null, "private", "ENABLED"), 1L);

        verify(repository, never()).saveWebhookToken(anyLong(), anyLong(), anyString());
    }

    @Test
    void webhookInfoReturnsUrlForWebhookWorkflow() {
        when(repository.findById(1L, 5L)).thenReturn(new Workflow(
                5L, 1L, "hook流程", null, "[]", "webhook", null, "private", "ENABLED", 1L, null));
        when(repository.findWebhookToken(1L, 5L)).thenReturn("abc123");

        java.util.Map<String, Object> info = service.webhookInfo(5L, 1L);

        assertThat(info.get("enabled")).isEqualTo(true);
        assertThat(info.get("url")).isEqualTo("/api/webhooks/abc123");
    }

    @Test
    void webhookInfoEmptyForManualWorkflow() {
        when(repository.findById(1L, 5L)).thenReturn(new Workflow(
                5L, 1L, "手动流程", null, "[]", "manual", null, "private", "ENABLED", 1L, null));

        java.util.Map<String, Object> info = service.webhookInfo(5L, 1L);

        assertThat(info.get("enabled")).isEqualTo(false);
        assertThat(info.get("url")).isEqualTo("");
    }
}
