package com.devmind.agent;

import com.devmind.audit.ToolCallLogRepository;
import com.devmind.guard.TaskMode;
import com.devmind.guard.ToolAccessDeniedException;
import com.devmind.guard.ToolGuard;
import com.devmind.tool.ToolDefinitionRepository;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行期授权收口在 {@link ToolRegistry#execute}：
 * 拒绝时工具不得被调用、审计落 denied 并带策略号；放行时保持原有行为。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolRegistryGuardTest {

    @Mock
    private ToolCallLogRepository auditRepository;

    @Mock
    private ToolDefinitionRepository toolDefinitionRepository;

    @Mock
    private UserService userService;

    private final AtomicInteger invoked = new AtomicInteger();

    private AgentTool tool(String name, boolean write) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "t"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public boolean writeOperation() { return write; }
            @Override public String execute(String argumentsJson, Long userId) {
                invoked.incrementAndGet();
                return "written";
            }
        };
    }

    private ToolRegistry registry(ToolGuard guard, AgentTool... tools) {
        return new ToolRegistry(List.of(tools), auditRepository, toolDefinitionRepository, userService, guard);
    }

    private ToolGuard guard() {
        return new ToolGuard(true, Set.of("ADMIN"), Set.of());
    }

    @Test
    void readOnlyTaskBlocksWriteBeforeItReachesTheTool() {
        when(userService.isAdmin(2L)).thenReturn(true);
        when(userService.tenantIdOf(2L)).thenReturn(1L);

        ToolRegistry registry = registry(guard(), tool("reimburse_submit", true));

        assertThatThrownBy(() -> registry.execute("reimburse_submit", "{\"amount\":4800}", 2L, "agent", null, TaskMode.READ_ONLY))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("TASK_SCOPE_READ_ONLY");

        assertThat(invoked.get()).isZero();
        verify(auditRepository).insert(argThat(l ->
                l.toolName().equals("reimburse_submit")
                        && l.status().equals("denied")
                        && l.decision().equals("DENY")
                        && l.policyId().equals("TASK_SCOPE_READ_ONLY")
                        && l.argsHash() != null && !l.argsHash().isBlank()
        ));
    }

    @Test
    void writeByNonWriteRoleIsBlocked() {
        when(userService.isAdmin(3L)).thenReturn(false);
        when(userService.tenantIdOf(3L)).thenReturn(1L);

        ToolRegistry registry = registry(guard(), tool("reimburse_submit", true));

        assertThatThrownBy(() -> registry.execute("reimburse_submit", "{}", 3L, "agent", null, TaskMode.WRITE))
                .isInstanceOf(ToolAccessDeniedException.class)
                .hasMessageContaining("WRITE_REQUIRES_ROLE");

        assertThat(invoked.get()).isZero();
        verify(auditRepository).insert(argThat(l -> l.status().equals("denied")
                && l.policyId().equals("WRITE_REQUIRES_ROLE")));
    }

    @Test
    void allowedReadToolExecutesAndIsAuditedWithAllowDecision() {
        when(userService.tenantIdOf(1L)).thenReturn(1L);

        ToolRegistry registry = registry(guard(), tool("kb_search", false));

        String out = registry.execute("kb_search", "{}", 1L, "agent", null, TaskMode.READ_ONLY);

        assertThat(out).isEqualTo("written");
        assertThat(invoked.get()).isEqualTo(1);
        verify(auditRepository).insert(argThat(l -> l.status().equals("success")
                && l.decision().equals("ALLOW")
                && l.policyId().equals("DEFAULT_ALLOW")));
        verify(userService, never()).isAdmin(1L);
    }

    @Test
    void unknownUserDenialStillLeavesAuditRowWithDefaultTenant() {
        when(userService.isAdmin(404L)).thenReturn(false);
        when(userService.tenantIdOf(404L)).thenThrow(new IllegalStateException("USER_NOT_FOUND"));

        ToolRegistry registry = registry(guard(), tool("reimburse_submit", true));

        assertThatThrownBy(() -> registry.execute("reimburse_submit", "{}", 404L, "agent", null, TaskMode.WRITE))
                .isInstanceOf(ToolAccessDeniedException.class);

        // 租户解析失败也必须留痕：回落到默认租户 1
        verify(auditRepository).insert(argThat(l -> Long.valueOf(1L).equals(l.tenantId())
                && l.status().equals("denied")
                && l.decision().equals("DENY")));
    }

    @Test
    void legacyFiveArgExecuteDefaultsToWriteMode() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(userService.tenantIdOf(1L)).thenReturn(1L);

        ToolRegistry registry = registry(guard(), tool("reimburse_submit", true));

        String out = registry.execute("reimburse_submit", "{}", 1L, "workflow", 9L);

        assertThat(out).isEqualTo("written");
        assertThat(invoked.get()).isEqualTo(1);
    }
}
