package com.devmind.skill;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import com.devmind.workflow.Workflow;
import com.devmind.workflow.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 技能（Skill）服务（Guide-51）：CRUD + 权限 + 从工作流沉淀。
 * 权限：本人可管理自己的 personal skill；团队 skill 同租户可见；admin 可管理全部。
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

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
                    existing.sourceWorkflowId(), existing.enabled(), existing.hitCount(), existing.createdBy(), existing.createdTime());
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
                existing.sourceWorkflowId(), enabled, existing.hitCount(), existing.createdBy(), existing.createdTime());
        repository.update(updated);
        return repository.findById(tenantId, id);
    }

    private Skill withField(Skill skill, String field, String value) {
        return switch (field) {
            case "description" -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    value, skill.applyTo(), skill.content(), skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.hitCount(), skill.createdBy(), skill.createdTime());
            case "applyTo" -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    skill.description(), value, skill.content(), skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.hitCount(), skill.createdBy(), skill.createdTime());
            default -> new Skill(skill.id(), skill.tenantId(), skill.scope(), skill.name(),
                    skill.description(), skill.applyTo(), value, skill.source(), skill.sourceWorkflowId(),
                    skill.enabled(), skill.hitCount(), skill.createdBy(), skill.createdTime());
        };
    }

    /**
     * 对话式修正技能（Guide-51 对话闭环）：基于技能现有内容 + 用户的修改指令，
     * 调用 LLM 生成新的规范内容并更新。供 Agent 的 update_skill 内部工具调用。
     * 返回修改前/后内容，供 Agent 向用户展示对比（确认闭环）。
     */
    public UpdateResult updateByInstruction(Long userId, Long id, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "修改指令不能为空");
        }
        Long tenantId = userService.tenantIdOf(userId);
        Skill existing = repository.findById(tenantId, id);
        if (existing == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "技能不存在: " + id);
        }
        requireManageable(existing, userId);
        String prompt = """
                你是技能修正助手。用户对一份技能规范提出修改意见，请据此生成修正后的完整规范。
                要求：
                1. 保持原规范的风格与结构（适用场景 + 执行要点）；
                2. 只应用用户明确要求的修改，其他部分保持不变；
                3. 全部使用中文，不超过 400 字；
                4. 只输出修正后的规范内容本身，不要解释或标题包裹。

                技能名称：%s
                当前规范：%s

                用户的修改意见：%s
                """.formatted(existing.name(), existing.content(), instruction.trim());
        AiModelGateway.ChatResult result = chatRouter.chat(
                "你是一个技能修正助手，根据用户意见修改技能规范。",
                prompt
        );
        String oldContent = existing.content();
        String content = extractSkillContent(result);
        repository.updateContent(tenantId, id, content);
        log.info("对话式修正技能成功 (skill={}, user={})", id, userId);
        Skill updated = repository.findById(tenantId, id);
        return new UpdateResult(updated, oldContent, content);
    }

    /** 技能修正结果：含修改前后内容（供 Agent 向用户展示对比） */
    public record UpdateResult(Skill skill, String oldContent, String newContent) {
    }

    /** 技能命中统计（Agent 注入时自增） */
    public void recordHit(Long userId, Long id) {
        try {
            Long tenantId = userService.tenantIdOf(userId);
            repository.incrementHit(tenantId, id);
        } catch (Exception ex) {
            log.warn("技能命中统计失败: {}", ex.getMessage());
        }
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
        String content = extractSkillContent(result);
        String applyTo = workflow.description() == null || workflow.description().isBlank()
                ? workflow.name() : workflow.name() + "|" + workflow.description();
        return new SkillDraft(workflow.name(), workflow.description(), applyTo, content, "from_workflow", workflowId);
    }

    /**
     * 从对话沉淀为技能草稿（Guide-51 P2）：把一次 Agent 对话（问题 + 工具调用 + 最终回答）
     * 提炼为技能规范。用户在问答页"存为技能"，编辑确认后 create。
     */
    public SkillDraft draftFromChat(Long userId, String question, List<ToolTraceItem> toolTrace, String answer) {
        if (question == null || question.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "对话问题不能为空");
        }
        StringBuilder traceText = new StringBuilder();
        if (toolTrace != null && !toolTrace.isEmpty()) {
            for (ToolTraceItem item : toolTrace) {
                traceText.append("- ").append(item.tool())
                        .append(" args=").append(item.args() == null ? "{}" : item.args())
                        .append(" ok=").append(item.ok())
                        .append('\n');
            }
        } else {
            traceText.append("（本次未调用工具）");
        }
        String prompt = """
                你是技能沉淀助手。下面是一次用户与 AI Agent 的对话（问题、Agent 调用的工具、最终回答）。请把它提炼为一份"技能规范"，供 AI Agent 在遇到同类任务时遵循。
                要求：
                1. 开头一句：适用场景（何时使用本技能）；
                2. 然后列出执行要点（先调用哪些工具、按什么顺序、参数要点、最后输出什么）；
                3. 语言精炼，全部使用中文，不超过 400 字；
                4. 只输出技能内容本身，不要标题包裹或解释。

                用户问题：%s

                Agent 调用的工具：
                %s

                最终回答：%s
                """.formatted(question.trim(), traceText, answer == null ? "" : answer);
        AiModelGateway.ChatResult result = chatRouter.chat(
                "你是一个技能沉淀助手，把一次成功的对话提炼为 AI 可遵循的规范文本。",
                prompt
        );
        String content = extractSkillContent(result);
        // 技能名取自问题核心（截断），描述为空（由用户补充）
        String name = question.trim().replaceAll("[\\r\\n]+", " ");
        if (name.length() > 30) {
            name = name.substring(0, 30);
        }
        return new SkillDraft(name, "", name, content, "from_chat", null);
    }

    /** 调用模型并容错提取技能内容（去代码块包裹） */
    private String extractSkillContent(AiModelGateway.ChatResult result) {
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "技能生成失败，请稍后重试");
        }
        String content = result.content().trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            int lastFence = content.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                content = content.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return content;
    }

    /** 从工作流沉淀的草稿（前端编辑确认后 create） */
    public record SkillDraft(String name, String description, String applyTo, String content,
                             String source, Long sourceWorkflowId) {
    }

    /** 对话沉淀的工具轨迹项 */
    public record ToolTraceItem(String tool, String args, Boolean ok, Long costMs) {
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
