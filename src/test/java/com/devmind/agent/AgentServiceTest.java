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
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private ToolAccessService toolAccessService;

    @Mock
    private ChatFileStore chatFileStore;

    private DevMindProperties properties() {
        return new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
    }

    private AgentService service(ToolRegistry registry) {
        // 用户 1 属于租户 1，可见当前注册的全部工具
        lenient().when(userService.tenantIdOf(eq(1L))).thenReturn(1L);
        lenient().when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenAnswer(inv -> {
            Set<String> names = new HashSet<>();
            for (AgentTool t : registry.all()) {
                names.add(t.name());
            }
            return names;
        });
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
                properties(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new ToolCallValidator(registry),
                toolAccessService,
                chatFileStore
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
    void rejectsUnknownToolCallAndContinues() {
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        // 模型幻觉：返回不存在的工具名 → 校验拦截，不执行、回填错误、继续到最终回答
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("c1", "not_exist_tool", "{\"q\":1}"))),
                        new AiModelGateway.ChatResult("正常回答", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "问题", null), 1L);

        assertThat(response.answer()).isEqualTo("正常回答");
        // 未知工具不执行
        verify(tool, never()).execute(anyString(), any());
        // 轨迹记录为失败，链路不中断
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo("not_exist_tool");
        assertThat(response.toolTrace().get(0).ok()).isFalse();
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
    @SuppressWarnings("unchecked")
    void uploadedFileContentInjectedIntoQuestion() {
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatFileStore.get("f1", 1L))
                .thenReturn(new ChatFileStore.ChatFile(1L, "report.md", "本月销售额 100 万"));
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("已分析。", "m", 0, 0));

        service.chat(new AgentChatRequest(0L, "分析这份报告", null, java.util.List.of("f1")), 1L);

        // 发给模型的最后一条 user 消息应包含文件文本 + 用户问题
        ArgumentCaptor<java.util.List<Map<String, Object>>> messagesCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(chatRouter).chatWithTools(anyString(), messagesCaptor.capture(), anyList());
        java.util.List<Map<String, Object>> messages = messagesCaptor.getValue();
        Map<String, Object> last = messages.get(messages.size() - 1);
        String content = (String) last.get("content");
        assertThat(content).contains("本月销售额 100 万").contains("分析这份报告");
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

    // ---------- Plan-Execute 评估用例 ----------

    @Test
    void injectsPlanToolAndExecutesMultiStepPlanSequentially() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any())).thenReturn("[{\"documentName\":\"a.md\",\"content\":\"RAG 结果\"}]");
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        // 第 1 轮：模型提交 2 步计划；第 2 轮：综合回答
        String planArgs = """
                {"goal":"SQL 性能诊断","steps":[
                  {"tool":"kb_search","args":{"question":"SQL 慢查询"},"goal":"检索慢查询知识"},
                  {"tool":"kb_search","args":{"question":"索引优化"},"goal":"检索索引优化方案"}
                ]}""";
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("p1", AgentService.PLAN_TOOL_NAME, planArgs))),
                        new AiModelGateway.ChatResult("根据检索结果给出优化建议。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "SQL 慢怎么办", null), 1L);

        assertThat(response.answer()).isEqualTo("根据检索结果给出优化建议。");
        // 计划 1 条 + 步骤 2 条
        assertThat(response.toolTrace()).hasSize(3);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo(AgentService.PLAN_TOOL_NAME);
        assertThat(response.toolTrace().get(0).ok()).isTrue();
        assertThat(response.toolTrace().get(1).tool()).isEqualTo("kb_search");
        assertThat(response.toolTrace().get(2).tool()).isEqualTo("kb_search");
        // 两步顺序执行
        verify(tool, org.mockito.Mockito.times(2)).execute(anyString(), any());
    }

    @Test
    void planStepFailureTriggersReplanHintOnceAndStillAnswers() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any()))
                .thenReturn("[{\"documentName\":\"a.md\",\"content\":\"第一步成功\"}]")
                .thenThrow(new IllegalStateException("第二步工具挂了"));
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        String planArgs = """
                {"goal":"多步任务","steps":[
                  {"tool":"kb_search","args":{"question":"A"},"goal":"第一步"},
                  {"tool":"kb_search","args":{"question":"B"},"goal":"第二步"}
                ]}""";
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("p1", AgentService.PLAN_TOOL_NAME, planArgs))),
                        new AiModelGateway.ChatResult("部分步骤失败，我基于已有信息回答。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "多步任务", null), 1L);

        assertThat(response.answer()).isEqualTo("部分步骤失败，我基于已有信息回答。");
        // 轨迹：plan + step1(成功) + step2(失败)
        assertThat(response.toolTrace()).hasSize(3);
        assertThat(response.toolTrace().get(1).ok()).isTrue();
        assertThat(response.toolTrace().get(2).ok()).isFalse();
        // 失败后触发了重规划提示（限 1 次），链路不中断
        verify(tool, org.mockito.Mockito.times(2)).execute(anyString(), any());
    }

    @Test
    void invalidPlanJsonReturnsErrorAndContinuesWithoutExecutingSteps() {
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        // 模型提交的 plan 参数 steps 为空数组 → 解析失败，回填错误，不执行任何步骤
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("p1", AgentService.PLAN_TOOL_NAME, "{\"goal\":\"x\",\"steps\":[]}"))),
                        new AiModelGateway.ChatResult("计划无效，我直接回答。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "任务", null), 1L);

        assertThat(response.answer()).isEqualTo("计划无效，我直接回答。");
        verify(tool, never()).execute(anyString(), any());
        assertThat(response.toolTrace()).isEmpty();
    }

    @Test
    void normalToolPathStillWorksWhenPlanIsNotUsed() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any())).thenReturn("[{\"documentName\":\"a.md\",\"content\":\"结果\"}]");
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("c1", "kb_search", "{}"))),
                        new AiModelGateway.ChatResult("单步回答", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "单步", null), 1L);

        assertThat(response.answer()).isEqualTo("单步回答");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo("kb_search");
        // 未使用计划 → 不产生 plan 轨迹、不触发重规划
        assertThat(response.toolTrace().stream().noneMatch(t -> t.tool().equals(AgentService.PLAN_TOOL_NAME))).isTrue();
    }

    @Test
    void executesMultipleToolCallsInParallelWithOrderedResults() {
        AgentTool tool = kbTool();
        when(tool.execute(anyString(), any())).thenReturn("[{\"documentName\":\"a.md\",\"content\":\"结果\"}]");
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        // 模型一轮同时返回 2 个工具调用 → 并发执行，按原顺序回填并记录轨迹
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(
                                        new AiModelGateway.ToolCall("c1", "kb_search", "{\"question\":\"A\"}"),
                                        new AiModelGateway.ToolCall("c2", "kb_search", "{\"question\":\"B\"}")
                                )),
                        new AiModelGateway.ChatResult("并行任务回答", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "并行任务", null), 1L);

        assertThat(response.answer()).isEqualTo("并行任务回答");
        // 2 个工具都执行且轨迹按原顺序
        assertThat(response.toolTrace()).hasSize(2);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo("kb_search");
        assertThat(response.toolTrace().get(0).ok()).isTrue();
        assertThat(response.toolTrace().get(1).tool()).isEqualTo("kb_search");
        assertThat(response.toolTrace().get(1).ok()).isTrue();
        verify(tool, org.mockito.Mockito.times(2)).execute(anyString(), any());
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
