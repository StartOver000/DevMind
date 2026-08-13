package com.devmind.agent;

import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.skill.Skill;
import com.devmind.skill.SkillMatcher;
import com.devmind.skill.SkillService;
import com.devmind.user.UserService;
import com.devmind.workflow.WorkflowRun;
import com.devmind.workflow.WorkflowService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 拆分后的执行器独立单测：工具校验/执行、Plan-Execute、4 个内部特判工具、结果回填。
 */
@ExtendWith(MockitoExtension.class)
class AgentToolExecutorTest {

    @Mock
    private AgentConversationStore conversationStore;
    @Mock
    private AgentMemoryRepository memoryRepository;
    @Mock
    private UserService userService;
    @Mock
    private SkillService skillService;
    @Mock
    private SkillMatcher skillMatcher;
    @Mock
    private WorkflowService workflowService;

    private AgentTool kbTool;
    private ToolRegistry registry;
    private AgentToolExecutor executor;

    @BeforeEach
    void setUp() {
        kbTool = tool("kb_search");
        registry = new ToolRegistry(List.of(kbTool, tool("hang_tool")));
        executor = new AgentToolExecutor(
                registry,
                new ToolCallValidator(registry),
                new SimpleMeterRegistry(),
                conversationStore,
                memoryRepository,
                userService
        );
    }

    private AgentTool tool(String name) {
        AgentTool t = org.mockito.Mockito.mock(AgentTool.class);
        lenient().when(t.name()).thenReturn(name);
        lenient().when(t.description()).thenReturn("工具 " + name);
        lenient().when(t.parametersJsonSchema()).thenReturn("{}");
        return t;
    }

    private AiModelGateway.ToolCall call(String id, String name, String args) {
        return new AiModelGateway.ToolCall(id, name, args);
    }

    // ---------- executeToolCore：校验 / 执行 / 失败 ----------

    @Test
    void rejectsUnknownToolCall() {
        AgentToolExecutor.ToolExecOutcome outcome =
                executor.executeToolCore(call("c1", "not_exist", "{}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("工具调用无效");
    }

    @Test
    void executesValidToolCall() {
        when(kbTool.execute(anyString(), eq(1L))).thenReturn("{\"ok\":true}");

        AgentToolExecutor.ToolExecOutcome outcome =
                executor.executeToolCore(call("c1", "kb_search", "{\"q\":\"x\"}"), 1L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.output()).contains("ok");
        verify(kbTool).execute(anyString(), eq(1L));
    }

    @Test
    void toolExecutionFailureReturnsErrorButNotThrow() {
        when(kbTool.execute(anyString(), eq(1L))).thenThrow(new RuntimeException("接口 500"));

        AgentToolExecutor.ToolExecOutcome outcome =
                executor.executeToolCore(call("c1", "kb_search", "{}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("工具执行失败");
    }

    // ---------- backfillTool：回填消息 + 记录轨迹 ----------

    @Test
    void backfillAddsToolMessageAndPersistsTrace() {
        AgentToolExecutor.ToolExecOutcome outcome = new AgentToolExecutor.ToolExecOutcome("{\"ok\":true}", true, 5L);
        List<Map<String, Object>> messages = new ArrayList<>();
        List<ToolTraceItem> received = new ArrayList<>();

        ToolTraceItem item = executor.backfillTool(
                call("c1", "kb_search", "{\"q\":1}"), outcome, messages, 100L, received::add);

        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("role", "tool");
        assertThat(item.tool()).isEqualTo("kb_search");
        assertThat(item.ok()).isTrue();
        assertThat(received).hasSize(1);
        verify(conversationStore).persistTrace(eq(100L), eq("kb_search"), anyString(), eq(true), eq(5L));
    }

    // ---------- executePlan：Plan-Execute ----------

    @Test
    void planParsingFailureReturnsFalseAndHintsModel() {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<ToolTraceItem> trace = new ArrayList<>();

        boolean allOk = executor.executePlan(
                call("p1", "plan", "{\"goal\":\"g\",\"steps\":[]}"), 1L, messages, trace, null, 100L);

        assertThat(allOk).isFalse();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("content").toString()).contains("计划解析失败");
    }

    @Test
    void planExecutesStepsInOrderAndCollectsTrace() {
        when(kbTool.execute(anyString(), eq(1L))).thenReturn("{\"ok\":true}");
        List<Map<String, Object>> messages = new ArrayList<>();
        List<ToolTraceItem> trace = new ArrayList<>();
        String planArgs = """
                {"goal":"g","steps":[
                  {"tool":"kb_search","args":{"q":"x"},"goal":"s1"},
                  {"tool":"kb_search","args":{"q":"y"},"goal":"s2"}]}
                """;

        boolean allOk = executor.executePlan(
                call("p1", "plan", planArgs), 1L, messages, trace, null, 100L);

        assertThat(allOk).isTrue();
        // 计划本身 + 2 个步骤轨迹
        assertThat(trace).hasSize(3);
        assertThat(trace.get(0).tool()).isEqualTo("plan");
        assertThat(trace.get(1).tool()).isEqualTo("kb_search");
        // 2 条 tool 消息回填
        assertThat(messages).hasSize(2);
        verify(kbTool, org.mockito.Mockito.times(2)).execute(anyString(), eq(1L));
    }

    // ---------- update_skill ----------

    @Test
    void updateSkillUnavailableReturnsError() {
        AgentToolExecutor.ToolExecOutcome outcome = executor.executeUpdateSkill(
                call("u1", "update_skill", "{\"skillId\":1,\"instruction\":\"改\"}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("技能服务不可用");
    }

    @Test
    void updateSkillUpdatesAndReturnsDiffSummary() {
        executor.setSkillService(skillService);
        Skill skill = new Skill(1L, 1L, "team", "库存规范", "d", "库存",
                "content", "[]", "manual", null, true, 0L, 1L, null);
        when(skillService.updateByInstruction(eq(1L), eq(5L), eq("把第2步改掉")))
                .thenReturn(new SkillService.UpdateResult(skill, "旧内容", "新内容"));

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeUpdateSkill(
                call("u1", "update_skill", "{\"skillId\":5,\"instruction\":\"把第2步改掉\"}"), 1L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.output()).contains("已更新技能").contains("旧内容").contains("新内容");
    }

    // ---------- load_skill ----------

    @Test
    void loadSkillReturnsFullContentAndRecordsHit() {
        executor.setSkillService(skillService);
        executor.setSkillMatcher(skillMatcher);
        when(userService.tenantIdOf(1L)).thenReturn(1L);
        Skill skill = new Skill(3L, 1L, "team", "监控规范", "d", "监控",
                "规范全文", "[]", "manual", null, true, 0L, 1L, null);
        when(skillService.get(eq(1L), eq(3L))).thenReturn(skill);

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeLoadSkill(
                call("l1", "load_skill", "{\"skillId\":3}"), 1L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.output()).contains("监控规范").contains("规范全文");
        verify(skillMatcher).recordLoad(1L, 3L);
    }

    // ---------- delete_memory ----------

    @Test
    void deleteMemoryRemovesEntry() {
        when(memoryRepository.deleteById(1L, 7L)).thenReturn(1);

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeDeleteMemory(
                call("d1", "delete_memory", "{\"memoryId\":7}"), 1L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.output()).contains("已删除长期记忆");
    }

    @Test
    void deleteMemoryNonexistentReturnsError() {
        when(memoryRepository.deleteById(1L, 99L)).thenReturn(0);

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeDeleteMemory(
                call("d1", "delete_memory", "{\"memoryId\":99}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("不存在或无权删除");
    }

    // ---------- run_workflow ----------

    @Test
    void runWorkflowReturnsSuccessStatus() {
        executor.setWorkflowService(workflowService);
        when(workflowService.run(3L, 1L))
                .thenReturn(new WorkflowRun(10L, 1L, 1L, "webhook", "SUCCESS", 0.0, null, null, null));

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeRunWorkflow(
                call("w1", "run_workflow", "{\"workflowId\":3}"), 1L);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.output()).contains("SUCCESS");
    }

    @Test
    void runWorkflowFailureReportsStatus() {
        executor.setWorkflowService(workflowService);
        when(workflowService.run(3L, 1L))
                .thenReturn(new WorkflowRun(10L, 1L, 1L, "webhook", "FAILED", 0.0, null, null, "超时"));

        AgentToolExecutor.ToolExecOutcome outcome = executor.executeRunWorkflow(
                call("w1", "run_workflow", "{\"workflowId\":3}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("FAILED").contains("超时");
    }

    @Test
    void runWorkflowUnavailableReturnsError() {
        AgentToolExecutor.ToolExecOutcome outcome = executor.executeRunWorkflow(
                call("w1", "run_workflow", "{\"workflowId\":3}"), 1L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.output()).contains("工作流服务不可用");
    }
}
