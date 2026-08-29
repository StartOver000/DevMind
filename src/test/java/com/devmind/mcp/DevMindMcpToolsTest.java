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
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpServerProperties mcpServerProperties = new McpServerProperties(true, "kb_search,kb_chat", 7L);

    @BeforeEach
    void setUp() {
        tools = new DevMindMcpTools(
                knowledgeBaseService, retrievalService, modelGateway,
                chatService, auditLogService, properties, mcpServerProperties, objectMapper
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

        String json = tools.kbSearch(new KbSearchInput(9L, "联合索引的最左前缀原则是什么", 3, List.of("mysql")));

        assertThat(json).contains("\"hitCount\":1");
        assertThat(json).contains("MySQL 索引专题.md");
        assertThat(json).contains("\"chunkId\":11");
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

        String json = tools.kbSearch(new KbSearchInput(9L, "任意问题", null, null));

        assertThat(json).contains("\"hitCount\":0");
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

        String json = tools.kbChat(new KbChatInput(9L, "解释最左前缀", null));

        assertThat(json).contains("\"conversationId\":3");
        assertThat(json).contains("最左前缀原则指");
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
}
