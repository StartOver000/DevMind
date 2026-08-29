package com.devmind.mcp;

import com.devmind.ai.AiModelGateway;
import com.devmind.audit.AuditLogService;
import com.devmind.chat.ChatService;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.config.DevMindProperties;
import com.devmind.config.McpServerProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.mcp.DevMindMcpTools.KbChatInput;
import com.devmind.mcp.DevMindMcpTools.KbSearchInput;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP 工具行为测试：与 HTTP 端点同链路（访问校验 → 向量化降级 → 混合检索），
 * 并验证治理平权的审计落点（mcp.* 动作）。
 */
@ExtendWith(MockitoExtension.class)
class DevMindMcpToolsTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private AiModelGateway modelGateway;
    @Mock
    private ChatService chatService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private DevMindProperties properties;

    private DevMindMcpTools tools;

    private final McpServerProperties mcpServerProperties = new McpServerProperties(true, "kb_search,kb_chat", 7L);
    @Mock
    private com.devmind.workflow.WorkflowService workflowService;
    @Mock
    private com.devmind.workflow.WorkflowGenerationService workflowGenerationService;
    @Mock
    private com.devmind.skill.SkillService skillService;

    @BeforeEach
    void setUp() {
        tools = new DevMindMcpTools(
                knowledgeBaseService, retrievalService, modelGateway,
                chatService, auditLogService, properties, mcpServerProperties,
                workflowService, workflowGenerationService, skillService
        );
    }

    private void stubDefaults() {
        // 共享桩：部分用例在读取这些配置前就短路返回，统一用 lenient 避免严格桩误报
        org.mockito.Mockito.lenient().when(properties.retrievalTopK()).thenReturn(5);
        org.mockito.Mockito.lenient().when(properties.retrievalMaxTopK()).thenReturn(10);
        org.mockito.Mockito.lenient().when(properties.retrievalMinScore()).thenReturn(0.15);
        org.mockito.Mockito.lenient().when(properties.retrievalHybridEnabled()).thenReturn(true);
    }

    @Test
    void kbSearchReturnsHitsAndWritesMcpAudit() {
        stubDefaults();
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(0.25, 0.75)));
        when(retrievalService.searchHybrid(
                anyLong(), anyList(), anyString(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), any())
        ).thenReturn(List.of(new RetrievalResult(
                11L, 2L, "MySQL 索引专题.md", 0, "联合索引最左前缀……", Map.of("tags", List.of("mysql")), 0.87
        )));

        String json = String.valueOf(tools.kbSearch(new KbSearchInput(9L, "联合索引的最左前缀原则是什么", 3, List.of("mysql"))));

        assertThat(json).contains("hitCount=1");
        assertThat(json).contains("MySQL 索引专题.md");
        assertThat(json).contains("chunkId=11");
        verify(knowledgeBaseService).requireEnabledKnowledgeBaseAccess(9L, 7L);
        verify(retrievalService).searchHybrid(
                eq(9L), anyList(), eq("联合索引的最左前缀原则是什么"), eq(3),
                eq(0.15), anyDouble(), anyDouble(), eq(true), any()
        );
        verify(auditLogService).log(eq(7L), eq("mcp.kb_search"), eq("knowledge_base"), eq(9L), anyString());
    }

    @Test
    void kbSearchFallsBackToKeywordsWhenEmbeddingFails() {
        stubDefaults();
        when(modelGateway.embed(anyList())).thenThrow(new RuntimeException("embedding 不可用"));
        when(retrievalService.searchByKeywords(anyLong(), anyString(), anyInt(), any())).thenReturn(List.of());

        String json = String.valueOf(tools.kbSearch(new KbSearchInput(9L, "任意问题", null, null)));

        assertThat(json).contains("hitCount=0");
        verify(retrievalService).searchByKeywords(eq(9L), eq("任意问题"), eq(5), any());
        verify(retrievalService, org.mockito.Mockito.never()).searchHybrid(
                anyLong(), anyList(), anyString(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), any()
        );
    }

    @Test
    void kbSearchRejectsBlankQuestion() {
        stubDefaults();
        assertThatThrownBy(() -> tools.kbSearch(new KbSearchInput(9L, "   ", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
        verifyNoInteractions(retrievalService);
    }

    @Test
    void kbSearchRejectsInvalidKnowledgeBaseId() {
        stubDefaults();
        assertThatThrownBy(() -> tools.kbSearch(new KbSearchInput(null, "问题", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledgeBaseId");
        verifyNoInteractions(knowledgeBaseService, retrievalService);
    }

    @Test
    void kbSearchPropagatesAccessDenialBeforeRetrieval() {
        stubDefaults();
        org.mockito.Mockito.doThrow(new RuntimeException("FORBIDDEN"))
                .when(knowledgeBaseService).requireEnabledKnowledgeBaseAccess(9L, 7L);

        assertThatThrownBy(() -> tools.kbSearch(new KbSearchInput(9L, "问题", null, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("FORBIDDEN");
        verifyNoInteractions(retrievalService, modelGateway);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void kbChatReturnsAnswerAndWritesMcpAudit() {
        stubDefaults();
        when(chatService.chat(eq(9L), any(), eq(7L)))
                .thenReturn(new ChatResponse(3L, "最左前缀原则指……", List.of()));

        ChatResponse response = (ChatResponse) tools.kbChat(new KbChatInput(9L, "解释最左前缀", null));

        assertThat(response.conversationId()).isEqualTo(3L);
        assertThat(response.answer()).isEqualTo("最左前缀原则指……");
        verify(knowledgeBaseService).requireEnabledKnowledgeBaseAccess(9L, 7L);
        verify(auditLogService).log(eq(7L), eq("mcp.kb_chat"), eq("knowledge_base"), eq(9L), anyString());
    }

    @Test
    void kbChatRejectsBlankQuestion() {
        stubDefaults();
        assertThatThrownBy(() -> tools.kbChat(new KbChatInput(9L, "", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
        verifyNoInteractions(chatService);
    }

    // ─── W3 工具：workflow_run / workflow_generate / skill_invoke ───

    @Test
    void workflowRunDelegatesAndWritesMcpAudit() {
        stubDefaults();
        when(workflowService.run(eq(20L), eq(7L))).thenReturn(new com.devmind.workflow.WorkflowRun(
                5L, 20L, 1L, "manual", "SUCCESS", 0.0, "t0", "t1", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.workflowRun(
                new com.devmind.mcp.DevMindMcpTools.WorkflowRunInput(20L));

        assertThat(result.get("workflowId")).isEqualTo(20L);
        assertThat(result.get("runId")).isEqualTo(5L);
        assertThat(result.get("status")).isEqualTo("SUCCESS");
        verify(workflowService).run(20L, 7L);
        verify(auditLogService).log(eq(7L), eq("mcp.workflow_run"), eq("workflow"), eq(20L), anyString());
    }

    @Test
    void workflowRunRejectsInvalidId() {
        stubDefaults();
        assertThatThrownBy(() -> tools.workflowRun(new com.devmind.mcp.DevMindMcpTools.WorkflowRunInput(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowId");
        verifyNoInteractions(workflowService);
    }

    @Test
    void workflowGenerateReturnsStepsJsonAndAudits() {
        stubDefaults();
        when(workflowGenerationService.generate(eq(7L), eq("每日生成监控日报")))
                .thenReturn(new com.devmind.workflow.WorkflowGenerationService.GenerationResult(
                        java.util.List.of(), "[{\"tool\":\"a\"}]"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.workflowGenerate(
                new com.devmind.mcp.DevMindMcpTools.WorkflowGenerateInput("每日生成监控日报"));

        assertThat(result.get("stepsJson")).isEqualTo("[{\"tool\":\"a\"}]");
        assertThat(result.get("stepCount")).isEqualTo(0);
        verify(workflowGenerationService).generate(7L, "每日生成监控日报");
        verify(auditLogService).log(eq(7L), eq("mcp.workflow_generate"), eq("workflow"), eq(null), anyString());
    }

    @Test
    void workflowGenerateRejectsBlankDescription() {
        stubDefaults();
        assertThatThrownBy(() -> tools.workflowGenerate(
                new com.devmind.mcp.DevMindMcpTools.WorkflowGenerateInput("  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
        verifyNoInteractions(workflowGenerationService);
    }

    @Test
    void skillInvokeRunsLinkedWorkflow() {
        stubDefaults();
        when(skillService.get(eq(7L), eq(3L))).thenReturn(new com.devmind.skill.Skill(
                3L, 1L, "team", "监控日报", null, null, null,
                "[{\"type\":\"workflow\",\"id\":20,\"name\":\"监控日报\"}]",
                "from_workflow", 20L, true, 1L, 7L, "t0"));
        when(workflowService.run(eq(20L), eq(7L))).thenReturn(new com.devmind.workflow.WorkflowRun(
                8L, 20L, 1L, "manual", "SUCCESS", 0.0, "t0", "t1", null));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.skillInvoke(
                new com.devmind.mcp.DevMindMcpTools.SkillInvokeInput(3L));

        assertThat(result.get("skillId")).isEqualTo(3L);
        assertThat(result.get("workflowId")).isEqualTo(20L);
        assertThat(result.get("runId")).isEqualTo(8L);
        verify(workflowService).run(20L, 7L);
        verify(auditLogService).log(eq(7L), eq("mcp.skill_invoke"), eq("skill"), eq(3L), anyString());
    }

    @Test
    void skillInvokeWithoutLinkedWorkflowReturnsNoWorkflow() {
        stubDefaults();
        when(skillService.get(eq(7L), eq(4L))).thenReturn(new com.devmind.skill.Skill(
                4L, 1L, "personal", "规范", null, null, null, "[]",
                "manual", null, true, 0L, 7L, "t0"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tools.skillInvoke(
                new com.devmind.mcp.DevMindMcpTools.SkillInvokeInput(4L));

        assertThat(result.get("status")).isEqualTo("NO_WORKFLOW");
        verifyNoInteractions(workflowService);
    }
}
