package com.devmind.skill;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 技能（Skill）服务（Guide-51）：CRUD + 权限 + 从工作流沉淀。
 * 权限：本人可管理自己的 personal skill；团队 skill 同租户可见；admin 可管理全部。
 */
@Service
public class SkillService {

    private final SkillRepository repository;
    private final UserService userService;
    private final WorkflowRepository workflowRepository;
    private final ChatRouter chatRouter;

    public SkillService(
            SkillRepository repository,
            UserService userService,
            WorkflowRepository workflowRepository,
            ChatRouter chatRouter
    ) {
        this.repository = repository;
        this.userService = userService;
        this.workflowRepository = workflowRepository;
        this.chatRouter = chatRouter;
    }

    public List<Skill> list(Long userId, String scope) {
        Long tenantId = userService.tenantIdOf(userId);
        return repository.listVisible(tenantId, userId, scope == null ? "all" : scope);
    }

    public Skill get(Long userId, Long id) {
        Long tenantId = userService.tenantIdOf(userId);
        Skill skill = repository.findById(tenantId, id);
        if (skill == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能不存在: " + id);
        }
        requireVisible(skill, userId);
        return skill;
    }

    public Skill create(Long userId, String scope, String name, String description,
                        String applyTo, String content, String source, Long sourceWorkflowId) {
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能名称不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能内容不能为空");
        }
        if (!"personal".equals(scope) && !"team".equals(scope)) {
            scope = "team";
        }
        // 非 admin 创建 team skill 时默认走沉淀流程（source 标记），管理员可手动建
        Long tenantId = userService.tenantIdOf(userId);
        Skill skill = Skill.forInsert(
                tenantId, scope, name.trim(), description == null ? "" : description,
                applyTo == null ? "" : applyTo, content,
                source == null ? "manual" : source, sourceWorkflowId, userId
        );
        return repository.insert(skill);
    }

    public Skill update(Long userId, Long id, String scope, String name, String description,
                        String applyTo, String content, boolean enabled) {
        Long tenantId = userService.tenantIdOf(userId);
        Skill existing = repository.findById(tenantId, id);
        if (existing == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能不存在: " + id);
        }
        requireManageable(existing, userId);
        if (name != null && !name.isBlank()) {
            existing = new Skill(existing.id(), existing.tenantId(), existing.scope(), name.trim(),
                    existing.description(), existing.applyTo(), existing.content(), existing.source(),
                    existing.sourceWorkflowId(), existing.enabled(), existing.createdBy(), existing.createdTime());
        }
        if (description != null) {
            existing = withField(existing, "description", description);
        }
        if (applyTo != null) {
            existing = withField(existing, "applyTo", applyTo);
        }
        if (content != null && !content.isBlank()) {
            existing = withField(existing, "content", content);
        }
        String newScope = scope == null ? existing.scope() : scope;
        Skill updated = new Skill(existing.id(), existing.tenantId(), newScope, existing.name(),
                existing.description(), existing.applyTo(), existing.content(), existing.source(),
                existing.sourceWorkflowId(), enabled, existing.createdBy(), existing.createdTime());
        repository.update(updated);
        return repository.findById(tenantId, id);
    }

    private Skill withField(Skill skill, String field, String value) {
        return switch (field) {
            case "description" -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    value, skill.applyTo(), skill.content(), skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.createdBy(), skill.createdTime());
            case "applyTo" -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    skill.description(), value, skill.content(), skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.createdBy(), skill.createdTime());
            default -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    skill.description(), skill.applyTo(), value, skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.createdBy(), skill.createdTime());
        };
    }

    public void toggle(Long userId, Long id, boolean enabled) {
        Long tenantId = userService.tenantIdOf(userId);
        Skill existing = repository.findById(tenantId, id);
        if (existing == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能不存在: " + id);
        }
        requireManageable(existing, userId);
        repository.toggle(tenantId, id, enabled);
    }

    public void delete(Long userId, Long id) {
        Long tenantId = userService.tenantIdOf(userId);
        Skill existing = repository.findById(tenantId, id);
        if (existing == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能不存在: " + id);
        }
        requireManageable(existing, userId);
        repository.delete(tenantId, id);
    }

    /**
     * 从工作流沉淀为技能草案（Guide-51 §5.3A）：读取工作流的 name/description/stepsJson，
     * 调用 LLM 生成一段"规范文本"（何时用、怎么做），返回草稿供用户编辑确认。
     * 不直接保存（用户可编辑 scope/name/content 后再 create）。
     */
    public SkillDraft draftFromWorkflow(Long userId, Long workflowId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow workflow = workflowRepository.findById(tenantId, workflowId);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流不存在: " + workflowId);
        }
        String stepsText = workflow.stepsJson();
        String prompt = """
                你是流程沉淀助手。下面是一个工作流（名称、描述、步骤 JSON）。请把它提炼为一份"技能规范"，供 AI Agent 在遇到同类任务时遵循。
                要求：
                1. 开头一句：适用场景（何时使用本技能）；
                2. 然后列出执行要点（先做什么、再做什么、什么条件走哪个分支、最后输出什么）；
                3. 语言精炼，全部使用中文，不超过 400 字；
                4. 只输出技能内容本身，不要标题包裹或解释。

                工作流名称：%s
                工作流描述：%s
                工作流步骤：%s
                """.formatted(
                workflow.name(),
                workflow.description() == null ? "" : workflow.description(),
                stepsText
        );
        AiModelGateway.ChatResult result = chatRouter.chat(
                "你是一个流程沉淀助手，把工作流提炼为 AI 可遵循的规范文本。",
                prompt
        );
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "技能生成失败，请稍后重试");
        }
        String content = result.content().trim();
        // 移除可能的 markdown 代码块包裹
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                content = content.substring(firstNewline + 1, lastFence).trim();
            }
        }
        String applyTo = workflow.description() == null || workflow.description().isBlank()
                ? workflow.name() : workflow.name() + "|" + workflow.description();
        return new SkillDraft(workflow.name(), workflow.description(), applyTo, content, "from_workflow", workflowId);
    }

    /** 从工作流沉淀的草稿（前端编辑确认后 create） */
    public record SkillDraft(String name, String description, String applyTo, String content,
                             String source, Long sourceWorkflowId) {
    }

    /** 可见性校验：团队技能同租户可见；personal 仅本人 */
    private void requireVisible(Skill skill, Long userId) {
        if ("personal".equals(skill.scope()) && !skill.createdBy().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权查看该技能");
        }
    }

    /** 可管理校验：本人创建的或 admin */
    private void requireManageable(Skill skill, Long userId) {
        if (skill.createdBy().equals(userId) || userService.isAdmin(userId)) {
            return;
        }
        throw new ApiException(ErrorCode.FORBIDDEN, "无权操作该技能");
    }
}
