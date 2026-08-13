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
import com.devmind.tool.ToolDefinition;
import com.devmind.tool.ToolSemanticRepository;
import com.devmind.tool.OpenApiImportService;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
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
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true, "", "", "", "", "", ""
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
        lenient().when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(List.of());
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
                chatFileStore,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class)
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
    void injectsMatchedSkillIntoSystemPrompt() {
        // 技能匹配器命中 → 规范注入 system prompt（Guide-51 P1：关键词命中注入全文）
        com.devmind.skill.SkillMatcher matcher = org.mockito.Mockito.mock(com.devmind.skill.SkillMatcher.class);
        when(matcher.match(eq("写一份月度经营分析报告"), eq(1L), eq(1L)))
                .thenReturn(new com.devmind.skill.SkillMatcher.MatchResult(
                        List.of("【技能 ID 1：月报规范】\n生成月报必须包含同比环比。"), List.of()));

        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setSkillMatcher(matcher);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("按规范生成。", "m", 0, 0));

        service.chat(new AgentChatRequest(0L, "写一份月度经营分析报告", null), 1L);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatRouter).chatWithTools(anyString(), captor.capture(), anyList());
        List<Map<String, Object>> messages = captor.getValue();
        boolean found = messages.stream().anyMatch(m ->
                "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("相关技能规范")
                        && String.valueOf(m.get("content")).contains("同比环比"));
        assertThat(found).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillDeclaredInterfaceToolIsInjectedIntoTools() {
        // M4 沉淀复用：命中技能 references 声明的接口工具 → 直接注入工具列表（不依赖语义检索）
        com.devmind.skill.SkillMatcher matcher = org.mockito.Mockito.mock(com.devmind.skill.SkillMatcher.class);
        when(matcher.match(eq("库存不足发预警"), eq(1L), eq(1L)))
                .thenReturn(new com.devmind.skill.SkillMatcher.MatchResult(
                        List.of("【技能 ID 1：库存预警规范】检查库存，不足发预警。"),
                        List.of(),
                        Set.of("declared_api")));

        AgentTool tool = kbTool();
        AgentTool declared = org.mockito.Mockito.mock(AgentTool.class);
        lenient().when(declared.name()).thenReturn("declared_api");
        lenient().when(declared.description()).thenReturn("库存查询接口");
        lenient().when(declared.parametersJsonSchema()).thenReturn("{}");
        AgentService service = service(new ToolRegistry(List.of(tool, declared)));
        service.setSkillMatcher(matcher);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("已按规范检查。", "m", 0, 0));

        service.chat(new AgentChatRequest(0L, "库存不足发预警", null), 1L);

        // 技能声明的接口工具进入 tools（模型可直接调用）
        ArgumentCaptor<List<AiModelGateway.ToolSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatRouter).chatWithTools(anyString(), anyList(), captor.capture());
        assertThat(captor.getValue()).anyMatch(s -> s.name().equals("declared_api"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void injectsSkillCatalogWithoutFullContent() {
        // 渐进披露：未命中关键词的技能只进清单（名称+描述），不含全文
        com.devmind.skill.SkillMatcher matcher = org.mockito.Mockito.mock(com.devmind.skill.SkillMatcher.class);
        when(matcher.match(eq("帮我看看代码规范"), eq(1L), eq(1L)))
                .thenReturn(new com.devmind.skill.SkillMatcher.MatchResult(
                        List.of(),
                        List.of("【技能 ID 2】监控版本检查：检查各服务版本是否符合规范")));
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setSkillMatcher(matcher);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("清单已收到。", "m", 0, 0));

        service.chat(new AgentChatRequest(0L, "帮我看看代码规范", null), 1L);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatRouter).chatWithTools(anyString(), captor.capture(), anyList());
        List<Map<String, Object>> messages = captor.getValue();
        boolean foundCatalog = messages.stream().anyMatch(m ->
                "system".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("可参考技能清单")
                        && String.valueOf(m.get("content")).contains("监控版本检查"));
        assertThat(foundCatalog).isTrue();
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
    @SuppressWarnings("unchecked")
    void updateSkillInternalToolRewritesSkillAndReports() {
        // 用户指出技能不对 → 模型调用 update_skill → SkillService 重写内容 → 模型展示前后对比
        AgentTool tool = kbTool();
        com.devmind.skill.SkillService skillService = org.mockito.Mockito.mock(com.devmind.skill.SkillService.class);
        com.devmind.skill.Skill updated = new com.devmind.skill.Skill(3L, 1L, "team", "月报规范", "d",
                "月报", "新规范：必须含利润归因。", "[]", "manual", null, true, 1L, 1L, null);
        com.devmind.skill.SkillService.UpdateResult updateResult =
                new com.devmind.skill.SkillService.UpdateResult(updated, "旧规范", "新规范：必须含利润归因。");
        when(skillService.updateByInstruction(eq(1L), eq(3L), anyString())).thenReturn(updateResult);

        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setSkillService(skillService);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        String updateArgs = "{\"skillId\":3,\"instruction\":\"把第 2 步改成先查利润再查费用\"}";
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("u1", AgentService.UPDATE_SKILL_TOOL_NAME, updateArgs))),
                        new AiModelGateway.ChatResult("已按你的要求更新技能「月报规范」。修改前后对比如下，请确认。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "这个月报技能不对，第2步应该先查利润", null), 1L);

        assertThat(response.answer()).contains("月报规范");
        verify(skillService).updateByInstruction(eq(1L), eq(3L), anyString());
        // 轨迹含 update_skill 且成功，回填内容带修改前后对比（供模型展示）
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo(AgentService.UPDATE_SKILL_TOOL_NAME);
        assertThat(response.toolTrace().get(0).ok()).isTrue();
        // 工具结果消息（回填给模型）含【修改前】【修改后】
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        // chatWithTools 调用两次：update_skill 轮 + 最终回答轮
        verify(chatRouter, org.mockito.Mockito.times(2)).chatWithTools(anyString(), captor.capture(), anyList());
        boolean toolMsgHasCompare = captor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(m -> "tool".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("【修改前】")
                        && String.valueOf(m.get("content")).contains("【修改后】"));
        assertThat(toolMsgHasCompare).isTrue();
    }

    @Test
    void updateSkillWithoutServiceReturnsErrorButContinues() {
        // 未注入 SkillService（如测试默认）时，update_skill 返回错误但不中断链路
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("u1", AgentService.UPDATE_SKILL_TOOL_NAME,
                                        "{\"skillId\":3,\"instruction\":\"改一下\"}"))),
                        new AiModelGateway.ChatResult("技能服务暂不可用，请稍后再试。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "改技能", null), 1L);

        assertThat(response.answer()).isEqualTo("技能服务暂不可用，请稍后再试。");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).ok()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadSkillInternalToolReturnsFullContentForModel() {
        // 渐进披露：模型调 load_skill 获取清单中技能的全文 → 回填工具结果供其遵循
        AgentTool tool = kbTool();
        com.devmind.skill.SkillService skillService = org.mockito.Mockito.mock(com.devmind.skill.SkillService.class);
        com.devmind.skill.SkillMatcher matcher = org.mockito.Mockito.mock(com.devmind.skill.SkillMatcher.class);
        com.devmind.skill.Skill skill = new com.devmind.skill.Skill(2L, 1L, "team", "监控版本检查", "d",
                "监控版本", "必须包含构建用户，且不超过 3 句话。", "[]", "manual", null, true, 0L, 1L, null);
        when(skillService.get(eq(1L), eq(2L))).thenReturn(skill);

        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setSkillService(skillService);
        service.setSkillMatcher(matcher);
        when(matcher.match(anyString(), eq(1L), eq(1L)))
                .thenReturn(new com.devmind.skill.SkillMatcher.MatchResult(List.of(), List.of()));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        String loadArgs = "{\"skillId\":2}";
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("l1", AgentService.LOAD_SKILL_TOOL_NAME, loadArgs))),
                        new AiModelGateway.ChatResult("已加载技能并按其规范执行。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "查一下监控版本信息并总结", null), 1L);

        assertThat(response.answer()).isEqualTo("已加载技能并按其规范执行。");
        verify(skillService).get(eq(1L), eq(2L));
        // 命中即自增
        verify(matcher).recordLoad(eq(1L), eq(2L));
        // 轨迹含 load_skill 且成功
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo(AgentService.LOAD_SKILL_TOOL_NAME);
        assertThat(response.toolTrace().get(0).ok()).isTrue();
        // 工具结果消息（回填给模型）含完整规范文本
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatRouter, org.mockito.Mockito.times(2)).chatWithTools(anyString(), captor.capture(), anyList());
        boolean toolMsgHasContent = captor.getAllValues().stream()
                .flatMap(List::stream)
                .anyMatch(m -> "tool".equals(m.get("role"))
                        && String.valueOf(m.get("content")).contains("必须包含构建用户"));
        assertThat(toolMsgHasContent).isTrue();
    }

    @Test
    void loadSkillWithoutServiceReturnsErrorButContinues() {
        // 未注入 SkillService 时 load_skill 返回错误但不中断
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("l1", AgentService.LOAD_SKILL_TOOL_NAME,
                                        "{\"skillId\":2}"))),
                        new AiModelGateway.ChatResult("技能服务暂不可用，我基于已有信息回答。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "查监控", null), 1L);

        assertThat(response.answer()).isEqualTo("技能服务暂不可用，我基于已有信息回答。");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).ok()).isFalse();
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
    void deleteMemoryInternalToolRemovesEntryAndReports() {
        // 用户要求忘记某条记忆 → 模型调用 delete_memory → 删除该条并告知
        AgentTool tool = kbTool();
        when(memoryRepository.deleteById(eq(1L), eq(5L))).thenReturn(1);
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("d1", AgentService.DELETE_MEMORY_TOOL_NAME,
                                        "{\"memoryId\":5}"))),
                        new AiModelGateway.ChatResult("已删除该条记忆。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "忘掉刚才那个偏好", null), 1L);

        assertThat(response.answer()).isEqualTo("已删除该条记忆。");
        verify(memoryRepository).deleteById(eq(1L), eq(5L));
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo(AgentService.DELETE_MEMORY_TOOL_NAME);
        assertThat(response.toolTrace().get(0).ok()).isTrue();
    }

    @Test
    void deleteMemoryToolMissingIdReturnsErrorButContinues() {
        // delete_memory 参数缺少 memoryId → 返回错误但不中断链路
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("d1", AgentService.DELETE_MEMORY_TOOL_NAME, "{}"))),
                        new AiModelGateway.ChatResult("缺少记忆 ID，无法删除。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "删记忆", null), 1L);

        assertThat(response.answer()).isEqualTo("缺少记忆 ID，无法删除。");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).ok()).isFalse();
        verify(memoryRepository, never()).deleteById(any(), any());
    }

    @Test
    void deleteMemoryNonexistentReturnsErrorButContinues() {
        // 记忆不存在（deleteById 返回 0）→ 错误回填，链路继续
        AgentTool tool = kbTool();
        when(memoryRepository.deleteById(eq(1L), eq(99L))).thenReturn(0);
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("d1", AgentService.DELETE_MEMORY_TOOL_NAME,
                                        "{\"memoryId\":99}"))),
                        new AiModelGateway.ChatResult("该记忆不存在。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "删记忆", null), 1L);

        assertThat(response.answer()).isEqualTo("该记忆不存在。");
        assertThat(response.toolTrace().get(0).ok()).isFalse();
    }

    @Test
    void runWorkflowInternalToolExecutesLinkedWorkflowAndReports() {
        // 技能引用工作流 → 模型调 run_workflow → WorkflowService.run 执行并回填结果
        AgentTool tool = kbTool();
        com.devmind.workflow.WorkflowService workflowService =
                org.mockito.Mockito.mock(com.devmind.workflow.WorkflowService.class);
        com.devmind.workflow.WorkflowRun run = new com.devmind.workflow.WorkflowRun(
                50L, 3L, 1L, "manual", "SUCCESS", 0.12, "t0", "t1", null);
        when(workflowService.run(eq(3L), eq(1L))).thenReturn(run);

        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setWorkflowService(workflowService);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("w1", AgentService.RUN_WORKFLOW_TOOL_NAME,
                                        "{\"workflowId\":3}"))),
                        new AiModelGateway.ChatResult("监控日报已生成。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "按规范生成监控日报", null), 1L);

        assertThat(response.answer()).isEqualTo("监控日报已生成。");
        verify(workflowService).run(eq(3L), eq(1L));
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).tool()).isEqualTo(AgentService.RUN_WORKFLOW_TOOL_NAME);
        assertThat(response.toolTrace().get(0).ok()).isTrue();
    }

    @Test
    void runWorkflowWithoutServiceReturnsErrorButContinues() {
        // 未注入 WorkflowService 时 run_workflow 返回错误但不中断
        AgentTool tool = kbTool();
        AgentService service = service(new ToolRegistry(List.of(tool)));
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("w1", AgentService.RUN_WORKFLOW_TOOL_NAME,
                                        "{\"workflowId\":3}"))),
                        new AiModelGateway.ChatResult("工作流服务暂不可用，我直接回答。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "生成日报", null), 1L);

        assertThat(response.answer()).isEqualTo("工作流服务暂不可用，我直接回答。");
        assertThat(response.toolTrace()).hasSize(1);
        assertThat(response.toolTrace().get(0).ok()).isFalse();
    }

    @Test
    void runWorkflowToolMissingIdReturnsErrorButContinues() {
        // run_workflow 缺 workflowId → 错误回填，链路继续
        AgentTool tool = kbTool();
        com.devmind.workflow.WorkflowService workflowService =
                org.mockito.Mockito.mock(com.devmind.workflow.WorkflowService.class);
        AgentService service = service(new ToolRegistry(List.of(tool)));
        service.setWorkflowService(workflowService);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(
                        new AiModelGateway.ChatResult("", "m", 0, 0,
                                List.of(new AiModelGateway.ToolCall("w1", AgentService.RUN_WORKFLOW_TOOL_NAME, "{}"))),
                        new AiModelGateway.ChatResult("缺少工作流 ID。", "m", 0, 0)
                );

        AgentChatResponse response = service.chat(new AgentChatRequest(0L, "跑流程", null), 1L);

        assertThat(response.answer()).isEqualTo("缺少工作流 ID。");
        assertThat(response.toolTrace().get(0).ok()).isFalse();
        verify(workflowService, never()).run(any(), any());
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

    // ---------- 接口工具语义发现（P1 闭环 / M2）----------

    private List<AgentTool> manyInterfaceTools(int count) {
        List<AgentTool> tools = new ArrayList<>();
        tools.add(kbTool()); // 内置工具
        for (int i = 0; i < count; i++) {
            AgentTool t = org.mockito.Mockito.mock(AgentTool.class);
            // lenient：接口工具可能未命中注入，其 description/schema stub 不一定被调用
            lenient().when(t.name()).thenReturn("api_tool_" + i);
            lenient().when(t.description()).thenReturn("接口 " + i);
            lenient().when(t.parametersJsonSchema()).thenReturn("{}");
            tools.add(t);
        }
        return tools;
    }

    private List<ToolDefinition> interfaceDefinitions(int count) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            defs.add(new ToolDefinition((long) (i + 1), 1L, "api_tool_" + i, "接口 " + i, "interface",
                    "http://x/api/" + i, "GET", null, null, "none", null, null, "READY", 1L, null));
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private List<AiModelGateway.ToolSpec> capturedTools(AgentService service, OpenApiImportService openApi) {
        service.setOpenApiImportService(openApi);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(new AiModelGateway.ChatResult("回答", "m", 0, 0));
        service.chat(new AgentChatRequest(0L, "查询客户订单", null), 1L);
        ArgumentCaptor<List<AiModelGateway.ToolSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatRouter).chatWithTools(anyString(), anyList(), captor.capture());
        return captor.getValue();
    }

    @Test
    void manyInterfaceToolsInjectOnlySemanticHits() {
        AgentService service = service(new ToolRegistry(manyInterfaceTools(25)));
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        OpenApiImportService openApi = org.mockito.Mockito.mock(OpenApiImportService.class);
        when(openApi.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of(
                new ToolSemanticRepository.SemanticHit(4L, "api_tool_3", "查询客户", "http://x/3", "GET", 0.91),
                new ToolSemanticRepository.SemanticHit(18L, "api_tool_17", "客户订单", "http://x/17", "GET", 0.82)
        ));

        List<AiModelGateway.ToolSpec> injected = capturedTools(service, openApi);

        // 命中的接口注入；未命中接口不注入；内置工具不受影响
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_3"));
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_17"));
        assertThat(injected).noneMatch(t -> t.name().equals("api_tool_0"));
        assertThat(injected).anyMatch(t -> t.name().equals("kb_search"));
        verify(openApi).semanticSearch(eq("查询客户订单"), eq(1L), anyInt());
    }

    @Test
    void manyInterfaceToolsFallBackToAllWhenNoSemanticHit() {
        AgentService service = service(new ToolRegistry(manyInterfaceTools(25)));
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        OpenApiImportService openApi = org.mockito.Mockito.mock(OpenApiImportService.class);
        when(openApi.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of());

        List<AiModelGateway.ToolSpec> injected = capturedTools(service, openApi);

        // 命中为空 → 回退全量注入（接口可用优先）
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_0"));
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_24"));
    }

    @Test
    void fewInterfaceToolsInjectAllWithoutSemanticSearch() {
        AgentService service = service(new ToolRegistry(manyInterfaceTools(3)));
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(3));
        OpenApiImportService openApi = org.mockito.Mockito.mock(OpenApiImportService.class);

        List<AiModelGateway.ToolSpec> injected = capturedTools(service, openApi);

        // 接口数 ≤ 阈值 → 全量注入且不触发语义检索
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_0"));
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_2"));
        verify(openApi, never()).semanticSearch(anyString(), any(), anyInt());
    }

    @Test
    void semanticSearchFailureFallsBackToAllInterfaceTools() {
        AgentService service = service(new ToolRegistry(manyInterfaceTools(25)));
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        OpenApiImportService openApi = org.mockito.Mockito.mock(OpenApiImportService.class);
        when(openApi.semanticSearch(anyString(), eq(1L), anyInt()))
                .thenThrow(new RuntimeException("embedding 服务不可用"));

        List<AiModelGateway.ToolSpec> injected = capturedTools(service, openApi);

        // 语义检索失败 → 回退全量（接口能力不丢失）
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_0"));
        assertThat(injected).anyMatch(t -> t.name().equals("api_tool_24"));
    }
}
