package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseItem;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.modelusage.ModelUsageService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private ChatRouter chatRouter;

    @Mock
    private AgentConversationRepository conversationRepository;

    @Mock
    private AgentMemoryRepository memoryRepository;

    @Mock
    private UserService userService;

    @Mock
    private ModelUsageService modelUsageService;

    @Mock
    private AiModelGateway modelGateway;

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private DevMindProperties properties() {
        return new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
    }

    private AgentService service(ToolRegistry registry) {
        return new AgentService(
                chatRouter,
                registry,
                conversationRepository,
                memoryRepository,
                userService,
                modelUsageService,
                modelGateway,
                retrievalService,
                knowledgeBaseService,
                properties()
        );
    }

    private AgentTool kbTool() {
        AgentTool tool = org.mockito.Mockito.mock(AgentTool.class);
        when(tool.name()).thenReturn("kb_search");
        when(tool.description()).thenReturn("检索知识库");
        when(tool.parametersJsonSchema()).thenReturn("{}");
        return tool;
    }

    @Test
    void executesToolThenReturnsFinalAnswer() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any())).thenReturn(
                "[{\"documentName\":\"a.md\",\"content\":\"RAG 是检索增强生成架构\",\"similarityScore\":0.9}]"
        );
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("c1", "kb_search", "{\"question\":\"什么是 RAG\"}"))),
                        new AiModelGateway.ChatResult("RAG 是检索增强生成（Retrieval-Augmented Generation）。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "什么是 RAG？", null), 1L);

        assertThat(response.answer()).contains("检索增强生成");
        assertThat(response.conversationId()).isEqualTo(100L);
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo("kb_search");
        assertThat(response.toolTrace().get(0).ok()).isTrue();
        verify(tool).execute(anyString(), any());
        // 工具轨迹与消息均应持久化（记忆）
        verify(conversationRepository).saveTrace(eq(100L), eq("kb_search"), anyString(), eq(true), anyLong());
        verify(conversationRepository).saveMessage(eq(100L), eq("user"), anyString());
        verify(conversationRepository).saveMessage(eq(100L), eq("assistant"), anyString());
    }

    @Test
    void returnsDirectAnswerWithoutTools() {
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("这是直接回答，无需工具。", "m", 0, 0));

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "你好", null), 1L);

        assertThat(response.answer()).isEqualTo("这是直接回答，无需工具。");
        assertThat(response.toolTrace()).isEmpty();
    }

    @Test
    void toolFailureDoesNotInterruptFlow() {
        AgentTool failingTool = org.mockito.Mockito.mock(AgentTool.class);
        when(failingTool.name()).thenReturn("kb_search");
        when(failingTool.description()).thenReturn("检索知识库");
        when(failingTool.parametersJsonSchema()).thenReturn("{}");
        when(failingTool.execute(anyString(), any())).thenThrow(new IllegalStateException("工具挂了"));

        AgentService service = service(new ToolRegistry(List.of(failingTool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("c1", "kb_search", "{}"))),
                        new AiModelGateway.ChatResult("工具不可用，我无法检索。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "查一下", null), 1L);

        assertThat(response.answer()).isEqualTo("工具不可用，我无法检索。");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).ok()).isFalse();
    }

    @Test
    void degradesToLocalRagWhenModelFails() {
        AgentService service = service(new ToolRegistry(List.of(kbTool())));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenThrow(new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型调用失败"));
        when(knowledgeBaseService.list(1L)).thenReturn(new KnowledgeBaseListResponse(
                List.of(new KnowledgeBaseItem(1L, "kb", "ENABLED", 4L, null))
        ));
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(1.0, 0.0, 0.0)));
        RetrievalResult result = new RetrievalResult(
                1L, 1L, "a.md", 0, "RAG 是把检索与生成结合的架构。",
                Map.of("heading", "什么是 RAG"), 0.9
        );
        when(retrievalService.searchHybrid(
                any(), any(), any(), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyBoolean()
        )).thenReturn(List.of(result));

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "什么是 RAG？", null), 1L);

        assertThat(response.answer()).contains("RAG");
        assertThat(response.references()).isNotEmpty();
    }

    @Test
    void extractsMemoryAfterSuccessfulChat() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any())).thenReturn(
                "[{\"documentName\":\"a.md\",\"content\":\"RAG 是检索增强生成\",\"similarityScore\":0.9}]"
        );
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(101L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("c1", "kb_search", "{\"question\":\"x\"}"))),
                        new AiModelGateway.ChatResult("根据检索结果回答。", "m", 0, 0)
                );
        // 提取器返回两行用户偏好（自动提取长期记忆）
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("语言: 中文\n回答风格: 简洁直接", "m", 0, 0));

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "查一下 RAG", null), 1L);

        assertThat(response.answer()).contains("根据检索结果回答");
        // 偏好按 key-value 合并写入记忆（非全量覆盖）
        verify(memoryRepository).upsert(eq(1L), eq("语言"), eq("中文"));
        verify(memoryRepository).upsert(eq(1L), eq("回答风格"), eq("简洁直接"));
    }

    @Test
    void memoryExtractionFailureDoesNotBreakChat() {
        AgentService service = service(new ToolRegistry(List.of(kbTool())));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("直接回答。", "m", 0, 0));
        // 提取器失败（429/熔断），主流程不受影响
        when(chatRouter.chat(anyString(), anyString()))
                .thenThrow(new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型限流"));

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "你好", null), 1L);

        assertThat(response.answer()).isEqualTo("直接回答。");
    }
}
