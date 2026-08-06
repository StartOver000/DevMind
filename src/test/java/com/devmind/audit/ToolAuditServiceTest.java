package com.devmind.audit;

import com.devmind.common.ApiException;
import com.devmind.user.UserService;
import com.devmind.workflow.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolAuditServiceTest {

    @Mock
    private ToolCallLogRepository callLogRepository;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private UserService userService;

    private ToolAuditService service;

    @BeforeEach
    void setUp() {
        service = new ToolAuditService(callLogRepository, runRepository, userService);
    }

    @Test
    void toolStatsUsesCurrentUserTenantAndId() {
        when(userService.tenantIdOf(2L)).thenReturn(1L);
        when(callLogRepository.stats(1L, 2L, 7)).thenReturn(List.of(Map.of("tool_name", "kb_search")));

        List<Map<String, Object>> stats = service.toolStats(2L, 7);

        assertThat(stats).hasSize(1);
    }

    @Test
    void adminToolStatsRejectsNonAdmin() {
        when(userService.isAdmin(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.adminToolStats(2L, null, 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("管理员");
    }

    @Test
    void adminToolStatsQueriesTenantScope() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(userService.tenantIdOf(1L)).thenReturn(1L);
        when(callLogRepository.stats(1L, null, 7)).thenReturn(List.of());

        assertThat(service.adminToolStats(1L, null, 7)).isEmpty();
        verify(callLogRepository).stats(1L, null, 7);
    }

    @Test
    void workflowStatsDelegatesToRunRepository() {
        when(userService.tenantIdOf(2L)).thenReturn(1L);
        when(runRepository.statsByWorkflow(1L, 7)).thenReturn(List.of());

        assertThat(service.workflowStats(2L, 7)).isEmpty();
        verify(runRepository).statsByWorkflow(1L, 7);
    }
}
