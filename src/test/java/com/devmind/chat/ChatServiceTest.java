package com.devmind.chat;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.audit.AuditLogService;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBase;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private ChatRepository chatRepository;

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private RerankService reranker;

    @Mock
    private AiModelGateway modelGateway;

    @Mock
    private ChatRouter chatRouter;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ModelUsageService modelUsageService;

    @Mock
    private UserService userService;

    @Test
    void returnsFallbackAnswerWhenNoRelevantChunksFound() {
        DevMindProperties properties = new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        ChatService service = new ChatService(
                knowledgeBaseService,
                chatRepository,
                retrievalService,
                reranker,
                modelGateway,
                chatRouter,
                auditLogService,
                modelUsageService,
                userService,
                properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.create()
        );
        KnowledgeBase knowledgeBase = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        when(knowledgeBaseService.requireEnabledKnowledgeBaseAccess(1L, 1L)).thenReturn(knowledgeBase);
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)));
        when(retrievalService.searchHybrid(
                any(), any(), any(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), any()
        )).thenReturn(List.of());
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of());
        when(chatRepository.createConversation(eq(1L), anyString(), eq(1L))).thenReturn(100L);

        ChatResponse response = service.chat(1L, new ChatRequest("知识库没有这个问题", null, null, null), 1L);

        assertThat(response.answer()).isEqualTo("知识库中没有找到足够相关内容。");
        assertThat(response.references()).isEmpty();
        verify(chatRepository, times(2)).insertMessage(eq(100L), anyString(), anyString(), isNull(), isNull());
    }

    @Test
    void degradesToLocalRagWhenModelFails() {
        DevMindProperties properties = new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        ChatService service = new ChatService(
                knowledgeBaseService,
                chatRepository,
                retrievalService,
                reranker,
                modelGateway,
                chatRouter,
                auditLogService,
                modelUsageService,
                userService,
                properties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.create()
        );
        RetrievalResult result = new RetrievalResult(
                1L, 1L, "a.md", 0, "RAG 是把检索与生成结合的架构。",
                Map.of("heading", "什么是 RAG"), 0.9
        );
        KnowledgeBase knowledgeBase = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        when(knowledgeBaseService.requireEnabledKnowledgeBaseAccess(1L, 1L)).thenReturn(knowledgeBase);
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(1.0, 0.0, 0.0)));
        when(retrievalService.searchHybrid(
                any(), any(), any(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), any()
        )).thenReturn(List.of(result));
        when(reranker.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(result));
        when(chatRouter.chat(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.MODEL_CALL_FAILED, "智谱接口调用失败"));
        when(chatRepository.createConversation(eq(1L), anyString(), eq(1L))).thenReturn(100L);

        ChatResponse response = service.chat(1L, new ChatRequest("什么是 RAG", null, null, null), 1L);

        assertThat(response.answer()).contains("本地降级模式").contains("a.md");
        assertThat(response.references()).hasSize(1);
        verify(chatRouter).chat(anyString(), anyString());
    }
}
