package com.devmind.agent;

import com.devmind.audit.ToolCallLogRepository;
import com.devmind.tool.ToolDefinition;
import com.devmind.tool.ToolDefinitionRepository;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ToolRegistry 统一审计：成功/失败/类型判定/审计失败不阻塞 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolRegistryAuditTest {

    @Mock
    private ToolCallLogRepository auditRepository;

    @Mock
    private ToolDefinitionRepository toolDefinitionRepository;

    @Mock
    private UserService userService;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of(
                tool("kb_search", "ok"),
                tool("crm_query", "ok"),
                tool("boom", "explode")
        ), auditRepository, toolDefinitionRepository, userService);
    }

    private AgentTool tool(String name, String behavior) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "t"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) {
                if ("explode".equals(behavior)) {
                    throw new IllegalStateException("boom!");
                }
                return "ok";
            }
        };
    }

    private ToolDefinition def(String name) {
        return new ToolDefinition(1L, 1L, name, "d", "interface", "http://x",
                "GET", "{}", null, "none", null, null, "READY", 1L, "2026-08-06");
    }

    @Test
    void executeSuccessAuditsWithInterfaceTypeAndSource() {
        when(userService.tenantIdOf(2L)).thenReturn(1L);
        when(toolDefinitionRepository.findByName("crm_query")).thenReturn(def("crm_query"));

        String out = registry.execute("crm_query", "{}", 2L, "workflow", 5L);

        assertThat(out).isEqualTo("ok");
        verify(auditRepository).insert(argThat(l ->
                l.toolName().equals("crm_query")
                        && l.toolType().equals("interface")
                        && l.source().equals("workflow")
                        && l.workflowRunId() == 5L
                        && l.status().equals("success")
                        && l.tenantId() == 1L
                        && l.userId() == 2L
        ));
    }

    @Test
    void builtinToolIsAuditedAsBuiltin() {
        when(userService.tenantIdOf(1L)).thenReturn(1L);
        when(toolDefinitionRepository.findByName("kb_search")).thenReturn(null);

        registry.execute("kb_search", "{}", 1L);

        verify(auditRepository).insert(argThat(l ->
                l.toolType().equals("builtin") && l.status().equals("success") && l.source().equals("agent")
        ));
    }

    @Test
    void executeFailureAuditsFailAndRethrows() {
        when(userService.tenantIdOf(1L)).thenReturn(1L);

        assertThatThrownBy(() -> registry.execute("boom", "{}", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        verify(auditRepository).insert(argThat(l ->
                l.toolName().equals("boom") && l.status().equals("fail") && l.error().contains("boom")
        ));
    }

    @Test
    void auditFailureDoesNotBreakMainFlow() {
        when(userService.tenantIdOf(1L)).thenThrow(new IllegalStateException("db down"));

        String out = registry.execute("kb_search", "{}", 1L);

        assertThat(out).isEqualTo("ok");
    }

    @Test
    void legacyConstructorSkipsAudit() {
        ToolRegistry legacy = new ToolRegistry(List.of(tool("kb_search", "ok")));
        assertThat(legacy.execute("kb_search", "{}", 1L)).isEqualTo("ok");
        verify(auditRepository, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
