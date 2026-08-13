package com.devmind.skill;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.user.UserService;
import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceTest {

    private SkillRepository repository;
    private UserService userService;
    private WorkflowRepository workflowRepository;
    private ChatRouter chatRouter;
    private com.devmind.tool.ToolAccessService toolAccessService;
    private SkillService service;

    private void setUp() {
        repository = mock(SkillRepository.class);
        userService = mock(UserService.class);
        workflowRepository = mock(WorkflowRepository.class);
        chatRouter = mock(ChatRouter.class);
        toolAccessService = mock(com.devmind.tool.ToolAccessService.class);
        service = new SkillService(repository, userService, workflowRepository, chatRouter, toolAccessService);
        when(userService.tenantIdOf(1L)).thenReturn(1L);
        when(userService.isAdmin(1L)).thenReturn(true);
        when(toolAccessService.accessibleDynamicTools(1L, 1L)).thenReturn(List.of());
    }

    @Test
    void createsSkill() {
        setUp();
        Skill created = new Skill(10L, 1L, "team", "月报规范", "desc", "月报",
                "内容", "[]", "manual", null, true, 0L, 1L, null);
        when(repository.insert(org.mockito.ArgumentMatchers.any())).thenReturn(created);

        Skill result = service.create(1L, "team", "月报规范", "desc", "月报", "内容", null, null, null);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.scope()).isEqualTo("team");
    }

    @Test
    void rejectsBlankName() {
        setUp();
        assertThatThrownBy(() -> service.create(1L, "team", "  ", "d", "a", "内容", null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("名称");
    }

    @Test
    void rejectsBlankContent() {
        setUp();
        assertThatThrownBy(() -> service.create(1L, "team", "名字", "d", "a", "  ", null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("内容");
    }

    @Test
    void draftFromWorkflowCallsLlm() {
        setUp();
        when(workflowRepository.findById(1L, 5L)).thenReturn(
                new Workflow(5L, 1L, "月度报告", "生成月度经营分析报告",
                        "[{\"tool\":\"kb_search\",\"params\":{\"question\":\"月度数据\"}}]",
                        "manual", null, "private", "ENABLED", 1L, null)
        );
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(
                        "当用户请求生成月度经营分析报告时：1. 先检索月度数据；2. 生成报告。", "m", 0, 0));

        SkillService.SkillDraft draft = service.draftFromWorkflow(1L, 5L);

        assertThat(draft.name()).isEqualTo("月度报告");
        assertThat(draft.content()).contains("月度经营分析报告");
        assertThat(draft.source()).isEqualTo("from_workflow");
        assertThat(draft.sourceWorkflowId()).isEqualTo(5L);
        verify(chatRouter).chat(anyString(), anyString());
    }

    @Test
    void draftFromWorkflowRecordsInterfaceToolsInReferences() {
        setUp();
        when(workflowRepository.findById(1L, 5L)).thenReturn(
                new Workflow(5L, 1L, "库存预警", "检查库存并发预警",
                        "[{\"tool\":\"checkStock\",\"params\":{\"productId\":5},\"output_var\":\"stock\"},"
                                + "{\"tool\":\"ai_generate\",\"params\":{\"prompt\":\"总结 {{stock}}\"}}]",
                        "manual", null, "private", "ENABLED", 1L, null)
        );
        // 接口工具集合含 checkStock（ai_generate 是内置工具，不应记录为接口依赖）
        when(toolAccessService.accessibleDynamicTools(1L, 1L)).thenReturn(List.of(
                new com.devmind.tool.ToolDefinition(1L, 1L, "checkStock", "库存查询", "interface",
                        "http://x/stock", "GET", null, null, "none", null, null, "READY", 1L, null)
        ));
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("检查库存，不足发预警。", "m", 0, 0));

        SkillService.SkillDraft draft = service.draftFromWorkflow(1L, 5L);

        assertThat(draft.references()).contains("\"type\":\"workflow\"");
        assertThat(draft.references()).contains("\"type\":\"interface_tool\"");
        assertThat(draft.references()).contains("checkStock");
        // 内置工具不记录为接口依赖
        assertThat(draft.references()).doesNotContain("ai_generate");
    }

    @Test
    void draftFromWorkflowThrowsWhenWorkflowMissing() {
        setUp();
        when(workflowRepository.findById(1L, 99L)).thenReturn(null);
        assertThatThrownBy(() -> service.draftFromWorkflow(1L, 99L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("工作流不存在");
    }

    @Test
    void listFiltersByScope() {
        setUp();
        when(repository.listVisible(1L, 1L, "all", null, null)).thenReturn(List.of());

        List<Skill> result = service.list(1L, null, null);

        assertThat(result).isEmpty();
        verify(repository).listVisible(1L, 1L, "all", null, null);
    }

    @Test
    void statsReturnsHealthDashboard() {
        setUp();
        when(repository.stats(1L, 1L)).thenReturn(Map.of(
                "total", 3L, "enabled", 2L, "hitTotal", 7L, "hot", List.of(), "zombie", List.of()
        ));

        Map<String, Object> result = service.stats(1L);

        assertThat(result.get("total")).isEqualTo(3L);
        assertThat(result.get("hitTotal")).isEqualTo(7L);
        verify(repository).stats(1L, 1L);
    }

    @Test
    void draftFromChatCallsLlmWithTrace() {
        setUp();
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(
                        "当用户请求查询监控版本信息时：1. 调用 prom_buildinfo；2. 若含 version 则总结。", "m", 0, 0));

        SkillService.SkillDraft draft = service.draftFromChat(1L, "查一下监控版本",
                List.of(new SkillService.ToolTraceItem("prom_buildinfo", "{}", true, 16L)),
                "版本是 3.13.2");

        assertThat(draft.name()).isEqualTo("查一下监控版本");
        assertThat(draft.content()).contains("prom_buildinfo");
        assertThat(draft.source()).isEqualTo("from_chat");
        assertThat(draft.sourceWorkflowId()).isNull();
        verify(chatRouter).chat(anyString(), anyString());
    }

    @Test
    void draftFromChatRejectsBlankQuestion() {
        setUp();
        assertThatThrownBy(() -> service.draftFromChat(1L, "  ", List.of(), "x"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("问题");
    }

    @Test
    void draftFromChatTruncatesLongQuestionToName() {
        setUp();
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("规范内容", "m", 0, 0));
        String longQuestion = "这是一个非常非常非常非常非常非常非常非常非常非常非常非常非常非常长的关于如何生成月度经营分析报告的问题描述";

        SkillService.SkillDraft draft = service.draftFromChat(1L, longQuestion, List.of(), "回答");

        assertThat(draft.name().length()).isLessThanOrEqualTo(30);
    }

    @Test
    void updateByInstructionRewritesContent() {
        setUp();
        Skill existing = new Skill(3L, 1L, "team", "月报规范", "d", "月报",
                "旧规范内容", "[]", "manual", null, true, 0L, 1L, null);
        when(repository.findById(1L, 3L)).thenReturn(existing);
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("新规范：必须含同比环比和利润归因。", "m", 0, 0));
        Skill updated = new Skill(3L, 1L, "team", "月报规范", "d", "月报",
                "新规范：必须含同比环比和利润归因。", "[]", "manual", null, true, 1L, 1L, null);
        when(repository.findById(1L, 3L)).thenReturn(existing, updated);

        SkillService.UpdateResult result = service.updateByInstruction(1L, 3L, "第 2 步改成先查利润再查费用");

        assertThat(result.skill().content()).contains("利润归因");
        assertThat(result.oldContent()).contains("旧规范");
        assertThat(result.newContent()).contains("利润归因");
        verify(repository).updateContent(1L, 3L, "新规范：必须含同比环比和利润归因。");
    }

    @Test
    void updateByInstructionRejectsBlank() {
        setUp();
        assertThatThrownBy(() -> service.updateByInstruction(1L, 3L, "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("指令");
    }

    @Test
    void updateByInstructionRejectsMissingSkill() {
        setUp();
        when(repository.findById(1L, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateByInstruction(1L, 99L, "改一下"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("技能不存在");
    }

    @Test
    void recordHitIncrements() {
        setUp();
        service.recordHit(1L, 3L);
        verify(repository).incrementHit(1L, 3L);
    }
}
