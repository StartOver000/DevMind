package com.devmind.workflow;

import com.devmind.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class WebhookControllerTest {

    @Mock
    private WorkflowRepository repository;

    @Mock
    private WorkflowExecutor executor;

    private WebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WebhookController(repository, executor, new ObjectMapper(), RestClient.builder());
    }

    private Workflow hookWorkflow() {
        return new Workflow(5L, 1L, "hook流程", null, "[{\"tool\":\"a\",\"params\":{}}]",
                "webhook", null, "private", "ENABLED", 1L, null);
    }

    private WorkflowRun run(Long id, String status) {
        return new WorkflowRun(id, 5L, 1L, "webhook", status, 0.0, null, null, null);
    }

    @Test
    void validTokenExecutesWorkflowAndReturnsResult() {
        when(repository.findByWebhookToken("tok123")).thenReturn(hookWorkflow());
        when(executor.execute(any(), eq(1L), eq("webhook"), any())).thenReturn(run(10L, "SUCCESS"));

        Map<String, Object> res = controller.trigger("tok123", false, null, "{}");

        assertThat(res.get("status")).isEqualTo("SUCCESS");
        assertThat(res.get("runId")).isEqualTo(10L);
        assertThat(res.get("workflow")).isEqualTo("hook流程");
    }

    @Test
    void invalidTokenRejected() {
        when(repository.findByWebhookToken("bad")).thenReturn(null);

        assertThatThrownBy(() -> controller.trigger("bad", false, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无效");
        verify(executor, never()).execute(any(), anyLong(), anyString(), any());
    }

    @Test
    void disabledWorkflowRejected() {
        Workflow disabled = new Workflow(5L, 1L, "w", null, "[]", "webhook", null, "private", "DISABLED", 1L, null);
        when(repository.findByWebhookToken("tok")).thenReturn(disabled);

        assertThatThrownBy(() -> controller.trigger("tok", false, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("停用");
        verify(executor, never()).execute(any(), anyLong(), anyString(), any());
    }

    @Test
    void bodyInjectedAsInitialVars() {
        when(repository.findByWebhookToken("tok")).thenReturn(hookWorkflow());
        when(executor.execute(eq(hookWorkflow()), eq(1L), eq("webhook"),
                argThat(m -> "hi".equals(m.get("message"))))).thenReturn(run(10L, "SUCCESS"));

        controller.trigger("tok", false, null, "{\"message\":\"hi\"}");

        verify(executor).execute(eq(hookWorkflow()), eq(1L), eq("webhook"),
                argThat(m -> "hi".equals(m.get("message"))));
    }

    @Test
    void nonJsonBodyIgnoredButStillExecutes() {
        when(repository.findByWebhookToken("tok")).thenReturn(hookWorkflow());
        when(executor.execute(eq(hookWorkflow()), eq(1L), eq("webhook"), any())).thenReturn(run(10L, "SUCCESS"));

        Map<String, Object> res = controller.trigger("tok", false, null, "not json at all");

        assertThat(res.get("status")).isEqualTo("SUCCESS");
    }

    @Test
    void asyncModeReturnsAcceptedAndExecutesInBackground() {
        when(repository.findByWebhookToken("tok")).thenReturn(hookWorkflow());
        when(executor.execute(any(), eq(1L), eq("webhook"), any())).thenReturn(run(10L, "SUCCESS"));

        Map<String, Object> res = controller.trigger("tok", true, null, "{}");

        assertThat(res.get("accepted")).isEqualTo(true);
        assertThat(res.get("status")).isEqualTo("ACCEPTED");
        // 后台线程最终执行了工作流
        verify(executor, timeout(3000)).execute(any(), eq(1L), eq("webhook"), any());
    }

    @Test
    void callbackUrlMustBeHttp() {
        when(repository.findByWebhookToken("tok")).thenReturn(hookWorkflow());

        assertThatThrownBy(() -> controller.trigger("tok", false, "javascript:alert(1)", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http/https");
        verify(executor, never()).execute(any(), anyLong(), anyString(), any());
    }

    @Test
    void asyncModeWithoutCallbackStillExecutes() {
        when(repository.findByWebhookToken("tok")).thenReturn(hookWorkflow());
        when(executor.execute(any(), eq(1L), eq("webhook"), any())).thenReturn(run(10L, "SUCCESS"));

        Map<String, Object> res = controller.trigger("tok", true, null, null);

        assertThat(res.get("accepted")).isEqualTo(true);
        verify(executor, timeout(3000)).execute(any(), eq(1L), eq("webhook"), any());
    }
}
