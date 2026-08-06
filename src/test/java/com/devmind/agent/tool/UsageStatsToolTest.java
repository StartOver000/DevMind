package com.devmind.agent.tool;

import com.devmind.audit.ToolAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageStatsToolTest {

    @Test
    void returnsToolAndWorkflowStats() throws Exception {
        ToolAuditService auditService = mock(ToolAuditService.class);
        when(auditService.toolStats(1L, 7)).thenReturn(List.of(
                Map.of("tool_name", "kb_search", "total", 5, "success_count", 4, "fail_count", 1)
        ));
        when(auditService.workflowStats(1L, 7)).thenReturn(List.of(
                Map.of("workflow_name", "日报", "total", 3, "success_count", 2)
        ));

        UsageStatsTool tool = new UsageStatsTool(auditService, new ObjectMapper());
        String output = tool.execute("{}", 1L);

        assertThat(output).contains("kb_search").contains("workflow_name").contains("\"days\":7");
        assertThat(output).doesNotContain("error");
    }

    @Test
    void passesDaysParameter() throws Exception {
        ToolAuditService auditService = mock(ToolAuditService.class);
        when(auditService.toolStats(1L, 30)).thenReturn(List.of());
        when(auditService.workflowStats(1L, 30)).thenReturn(List.of());

        UsageStatsTool tool = new UsageStatsTool(auditService, new ObjectMapper());
        String output = tool.execute("{\"days\":30}", 1L);

        assertThat(output).contains("\"days\":30");
        verify(auditService).toolStats(eq(1L), eq(30));
    }

    @Test
    void clampsDaysTo90() throws Exception {
        ToolAuditService auditService = mock(ToolAuditService.class);
        when(auditService.toolStats(1L, 90)).thenReturn(List.of());
        when(auditService.workflowStats(1L, 90)).thenReturn(List.of());

        UsageStatsTool tool = new UsageStatsTool(auditService, new ObjectMapper());
        String output = tool.execute("{\"days\":999}", 1L);

        assertThat(output).contains("\"days\":90");
        verify(auditService).toolStats(eq(1L), eq(90));
    }
}
