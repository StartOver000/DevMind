package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.config.DevMindProperties;
import com.devmind.security.LlmInputGuard;
import com.devmind.tool.ToolAccessService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G5：Agent 行为级离线评测集。
 * 用 Golden Set（评测用例）跑 Agent 全链路（ReAct），量化两个行为指标：
 * - 工具选择准确率：实际成功执行的工具序列 == 期望工具序列 的用例占比
 * - 任务完成率：最终产出非空回答的用例占比
 * 与检索评测（MRR/Recall@K/NDCG/Faithfulness）互补：检索评估"召回对不对"，这里评估"Agent 会不会干活"。
 * 运行：mvn -o test -Dtest=AgentBehaviorEvalTest
 */
class AgentBehaviorEvalTest {

    /** 评测用例：名称 + 用户问题 + 期望成功执行的工具序列 + 模型脚本（按序返回） */
    private record EvalCase(
            String name,
            String query,
            List<String> expectedTools,
            List<AiModelGateway.ChatResult> modelScript
    ) {
    }

    private static final String ANSWER = "完成，这是最终回答。";

    private DevMindProperties properties() {
        return new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true, "", "", "", "", "", ""
        );
    }

    private AgentTool mockTool(String name, String description, String result) {
        AgentTool tool = mock(AgentTool.class);
        lenient().when(tool.name()).thenReturn(name);
        lenient().when(tool.description()).thenReturn(description);
        lenient().when(tool.parametersJsonSchema()).thenReturn("{}");
        if (result != null) {
            lenient().when(tool.execute(anyString(), any())).thenReturn(result);
        }
        return tool;
    }

    private AiModelGateway.ChatResult toolCall(String id, String toolName, String args) {
        return new AiModelGateway.ChatResult("", "m", 0, 0,
                List.of(new AiModelGateway.ToolCall(id, toolName, args)));
    }

    private AiModelGateway.ChatResult finalAnswer() {
        return new AiModelGateway.ChatResult(ANSWER, "m", 0, 0);
    }

    /** Golden Set：覆盖知识检索 / 接口调用 / 多步编排 / 幻觉拒绝 / 直接回答 五类行为 */
    private List<EvalCase> goldenSet() {
        return List.of(
                new EvalCase("知识检索", "什么是 RAG？",
                        List.of("kb_search"),
                        List.of(toolCall("c1", "kb_search", "{\"question\":\"什么是 RAG\"}"), finalAnswer())),
                new EvalCase("接口调用", "查一下库存",
                        List.of("declared_api"),
                        List.of(toolCall("c1", "declared_api", "{\"sku\":\"A1\"}"), finalAnswer())),
                new EvalCase("多步编排", "先查知识库再诊断慢 SQL",
                        List.of("kb_search", "sql_diagnose"),
                        List.of(toolCall("c1", "kb_search", "{\"question\":\"慢 SQL\"}"),
                                toolCall("c2", "sql_diagnose", "{\"sql\":\"select * from t\"}"),
                                finalAnswer())),
                new EvalCase("工具幻觉拒绝", "调用不存在的工具",
                        List.of(), // 期望：成功执行的工具为空（幻觉被拦截，不执行）
                        List.of(toolCall("c1", "not_exist_tool", "{\"q\":1}"), finalAnswer())),
                new EvalCase("直接回答", "你好",
                        List.of(),
                        List.of(finalAnswer()))
        );
    }

    /** 跑一个评测用例：全新 mock（stubbing 不跨用例累积），返回 Agent 响应 */
    private AgentChatResponse run(EvalCase evalCase) {
        AgentTool kb = mockTool("kb_search", "检索知识库", "[{\"documentName\":\"a.md\",\"content\":\"RAG 是检索增强生成\",\"similarityScore\":0.9}]");
        AgentTool sql = mockTool("sql_diagnose", "SQL 诊断", "{\"advice\":\"建议加索引\"}");
        AgentTool api = mockTool("declared_api", "库存查询接口", "{\"stock\":100}");
        ToolRegistry registry = new ToolRegistry(List.of(kb, sql, api));

        ChatRouter chatRouter = mock(ChatRouter.class);
        when(chatRouter.chatWithTools(anyString(), anyList(), anyList()))
                .thenReturn(evalCase.modelScript().get(0),
                        evalCase.modelScript().subList(1, evalCase.modelScript().size()).toArray(new AiModelGateway.ChatResult[0]));

        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        when(conversationRepository.create(any(), anyString())).thenReturn(100L);

        AgentMemoryRepository memoryRepository = mock(AgentMemoryRepository.class);
        com.devmind.user.UserService userService = mock(com.devmind.user.UserService.class);
        lenient().when(userService.tenantIdOf(eq(1L))).thenReturn(1L);
        com.devmind.modelusage.ModelUsageService modelUsageService = mock(com.devmind.modelusage.ModelUsageService.class);
        AiModelGateway modelGateway = mock(AiModelGateway.class);
        com.devmind.retrieval.RetrievalService retrievalService = mock(com.devmind.retrieval.RetrievalService.class);
        com.devmind.knowledge.KnowledgeBaseService knowledgeBaseService = mock(com.devmind.knowledge.KnowledgeBaseService.class);
        ToolAccessService toolAccessService = mock(ToolAccessService.class);
        lenient().when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenAnswer(inv -> {
            Set<String> names = new HashSet<>();
            for (AgentTool t : registry.all()) {
                names.add(t.name());
            }
            return names;
        });
        lenient().when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(List.of());
        ChatFileStore chatFileStore = mock(ChatFileStore.class);

        AgentService service = new AgentService(
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
                mock(LlmInputGuard.class)
        );
        return service.chat(new AgentChatRequest(0L, evalCase.query(), null), 1L);
    }

    @Test
    void runAgentBehaviorEvalSuite() {
        List<EvalCase> cases = goldenSet();
        List<String> report = new ArrayList<>();
        int toolHits = 0;
        int tasksDone = 0;

        for (EvalCase c : cases) {
            AgentChatResponse resp = run(c);
            // 实际"选择并成功执行"的工具（ok=true），幻觉/失败轨迹不计入选择
            List<String> actual = resp.toolTrace().stream()
                    .filter(ToolTraceItem::ok)
                    .map(ToolTraceItem::tool)
                    .toList();
            boolean toolOk = actual.equals(c.expectedTools());
            boolean taskOk = resp.answer() != null && !resp.answer().isBlank();
            if (toolOk) {
                toolHits++;
            }
            if (taskOk) {
                tasksDone++;
            }
            report.add(String.format("  [%s] 期望工具=%s 实际=%s 工具选择%s 任务完成%s",
                    c.name(), c.expectedTools(), actual, toolOk ? "✓" : "✗", taskOk ? "✓" : "✗"));
        }

        String summary = String.format("""
                ===== Agent 行为级离线评测报告（G5）=====
                %s
                指标：
                  - 工具选择准确率: %d/%d = %.1f%%
                  - 任务完成率: %d/%d = %.1f%%
                ==============================================
                """,
                String.join("\n", report),
                toolHits, cases.size(), toolHits * 100.0 / cases.size(),
                tasksDone, cases.size(), tasksDone * 100.0 / cases.size());
        System.out.println(summary);

        // 评测集为模型脚本可控，Golden Set 全量通过才是合格线
        assertThat(toolHits).as("工具选择准确率应为 100%%（Golden Set 脚本可控）").isEqualTo(cases.size());
        assertThat(tasksDone).as("任务完成率应为 100%%").isEqualTo(cases.size());
    }
}
