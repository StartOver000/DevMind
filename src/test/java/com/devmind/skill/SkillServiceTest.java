package com.devmind.skill;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.user.UserService;
import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    private SkillService service;

    private void setUp() {
        repository = mock(SkillRepository.class);
        userService = mock(UserService.class);
        workflowRepository = mock(WorkflowRepository.class);
        chatRouter = mock(ChatRouter.class);
        service = new SkillService(repository, userService, workflowRepository, chatRouter);
        when(userService.tenantIdOf(1L)).thenReturn(1L);
        when(userService.isAdmin(1L)).thenReturn(true);
    }

    @Test
    void createsSkill() {
        setUp();
        Skill created = new Skill(10L, 1L, "team", "月报规范", "desc", "月报",
                "内容", "manual", null, true, 1L, null);
        when(repository.insert(org.mockito.ArgumentMatchers.any())).thenReturn(created);

        Skill result = service.create(1L, "team", "月报规范", "desc", "月报", "内容", null, null);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.scope()).isEqualTo("team");
    }

    @Test
    void rejectsBlankName() {
        setUp();
        assertThatThrownBy(() -> service.create(1L, "team", "  ", "d", "a", "内容", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("名称");
    }

    @Test
    void rejectsBlankContent() {
        setUp();
        assertThatThrownBy(() -> service.create(1L, "team", "名字", "d", "a", "  ", null, null))
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
        when(repository.listVisible(1L, 1L, "all")).thenReturn(List.of());

        List<Skill> result = service.list(1L, null);

        assertThat(result).isEmpty();
        verify(repository).listVisible(1L, 1L, "all");
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
}
