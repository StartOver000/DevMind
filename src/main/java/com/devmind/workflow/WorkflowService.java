package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import com.devmind.workflow.dto.WorkflowCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流管理：CRUD + 手动运行 + 运行记录查询。
 * 执行委托 {@link WorkflowExecutor}（复用 Agent 工具引擎）。
 */
@Service
@SuppressWarnings("null")
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository repository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutor executor;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ToolAccessService toolAccessService;

    public WorkflowService(
            WorkflowRepository repository,
            WorkflowRunRepository runRepository,
            WorkflowExecutor executor,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            UserService userService,
            ToolAccessService toolAccessService
    ) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.executor = executor;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.userService = userService;
        this.toolAccessService = toolAccessService;
    }

    public List<Workflow> list(Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        List<Workflow> all = repository.listAll(tenantId);
        if (userService.isAdmin(userId)) {
            return all;
        }
        // private 工作流仅创建者可见（同租户其他用户不可见）
        return all.stream()
                .filter(w -> !"private".equals(w.scope()) || w.createdBy().equals(userId))
                .toList();
    }

    public Workflow get(Long id, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow workflow = requireWorkflow(tenantId, id);
        requireVisible(workflow, userId);
        return workflow;
    }

    @Transactional
    public Workflow create(WorkflowCreateRequest req, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        validateSteps(req.stepsJson(), userId);
        validateTrigger(req.triggerType(), req.cronExpr());
        String trigger = req.triggerType() == null ? "manual" : req.triggerType();
        String scope = req.scope() == null ? "private" : req.scope();
        String status = req.status() == null ? "ENABLED" : req.status();
        Workflow workflow = Workflow.forInsert(
                tenantId, req.name(), req.description(), req.stepsJson(),
                trigger, req.cronExpr(), scope, status, userId
        );
        Long id = repository.insert(workflow);
        ensureWebhookToken(tenantId, id, trigger);
        log.info("创建工作流 {} (id={}, tenant={}, by user={})", req.name(), id, tenantId, userId);
        return requireWorkflow(tenantId, id);
    }

    @Transactional
    public Workflow update(Long id, WorkflowCreateRequest req, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow existing = requireWorkflow(tenantId, id);
        requireManageable(existing, userId);
        validateSteps(req.stepsJson(), userId);
        validateTrigger(req.triggerType(), req.cronExpr());
        Workflow updated = new Workflow(
                id, existing.tenantId(), req.name(), req.description(), req.stepsJson(),
                req.triggerType() == null ? existing.triggerType() : req.triggerType(),
                req.cronExpr(), req.scope() == null ? existing.scope() : req.scope(),
                req.status() == null ? existing.status() : req.status(),
                existing.createdBy(), existing.createdTime()
        );
        repository.update(tenantId, updated);
        ensureWebhookToken(tenantId, id, updated.triggerType());
        log.info("更新工作流 {} (id={}, by user={})", req.name(), id, userId);
        return requireWorkflow(tenantId, id);
    }

    public void delete(Long id, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow existing = requireWorkflow(tenantId, id);
        requireManageable(existing, userId);
        repository.softDelete(tenantId, id);
        log.info("删除工作流 (id={}, by user={})", id, userId);
    }

    /** 手动运行：同步执行，返回本次运行记录 */
    public WorkflowRun run(Long id, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow workflow = requireWorkflow(tenantId, id);
        requireVisible(workflow, userId);
        if (!"ENABLED".equals(workflow.status())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流已停用");
        }
        log.info("手动运行工作流 {} (id={}, by user={})", workflow.name(), id, userId);
        return executor.execute(workflow, userId, "manual");
    }

    public List<WorkflowRun> runList(Long workflowId, int limit, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow workflow = requireWorkflow(tenantId, workflowId);
        requireVisible(workflow, userId);
        return runRepository.listRuns(tenantId, workflowId, Math.min(limit, 50));
    }

    /** 运行详情：run + 步骤明细（校验所属工作流可见性） */
    public WorkflowRunDetail runDetail(Long runId, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        WorkflowRun run = runRepository.findRun(tenantId, runId);
        if (run == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "运行记录不存在: " + runId);
        }
        Workflow workflow = requireWorkflow(tenantId, run.workflowId());
        requireVisible(workflow, userId);
        return new WorkflowRunDetail(run, runRepository.listSteps(runId));
    }

    public record WorkflowRunDetail(WorkflowRun run, List<WorkflowRunStep> steps) {
    }

    /** 工作流 webhook 触发信息（创建者/admin 可见）：token 与调用 URL */
    public Map<String, Object> webhookInfo(Long workflowId, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Workflow workflow = requireWorkflow(tenantId, workflowId);
        requireManageable(workflow, userId);
        String token = repository.findWebhookToken(tenantId, workflowId);
        boolean enabled = "webhook".equals(workflow.triggerType());
        return Map.of(
                "enabled", enabled,
                "token", token == null ? "" : token,
                "url", enabled && token != null && !token.isBlank() ? "/api/webhooks/" + token : ""
        );
    }

    /** webhook 触发的工作流：生成/保留调用 token */
    private void ensureWebhookToken(Long tenantId, Long workflowId, String triggerType) {
        if (!"webhook".equals(triggerType)) {
            return;
        }
        String existing = repository.findWebhookToken(tenantId, workflowId);
        if (existing == null || existing.isBlank()) {
            repository.saveWebhookToken(tenantId, workflowId, newWebhookToken());
        }
    }

    private static final java.security.SecureRandom WEBHOOK_RANDOM = new java.security.SecureRandom();

    /** 生成 32 位 hex 随机 token（外部调用凭据） */
    private String newWebhookToken() {
        byte[] bytes = new byte[16];
        WEBHOOK_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Workflow requireWorkflow(Long tenantId, Long id) {
        Workflow workflow = repository.findById(tenantId, id);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流不存在: " + id);
        }
        return workflow;
    }

    /** 可见性校验：team 工作流同租户可见；private 仅创建者/admin（参照 SkillService.requireVisible） */
    private void requireVisible(Workflow workflow, Long userId) {
        if ("private".equals(workflow.scope())
                && !workflow.createdBy().equals(userId)
                && !userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权查看该工作流");
        }
    }

    /** 可管理校验：创建者或 admin 可改/删/查 webhook token（参照 SkillService.requireManageable） */
    private void requireManageable(Workflow workflow, Long userId) {
        if (workflow.createdBy().equals(userId) || userService.isAdmin(userId)) {
            return;
        }
        throw new ApiException(ErrorCode.FORBIDDEN, "无权操作该工作流");
    }

    /** 校验触发配置：cron 触发时 cron 表达式必填且合法 */
    private void validateTrigger(String triggerType, String cronExpr) {
        if ("cron".equals(triggerType)) {
            if (cronExpr == null || cronExpr.isBlank()) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "定时触发需要填写 cron 表达式（如 0 0 9 * * *）");
            }
            try {
                CronExpression.parse(cronExpr.trim());
            } catch (Exception ex) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "cron 表达式无效: " + cronExpr);
            }
        }
    }

    /** 校验 steps_json：必须是数组、每步 tool 已注册且对当前用户可见；支持 {"parallel":[...]} 与 {"if":...,"then":[...]} */
    private void validateSteps(String stepsJson, Long userId) {
        if (stepsJson == null || stepsJson.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤不能为空");
        }
        Long tenantId = userService.tenantIdOf(userId);
        Set<String> accessible = toolAccessService.accessibleToolNames(tenantId, userId);
        try {
            JsonNode array = objectMapper.readTree(stepsJson);
            if (!array.isArray() || array.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤必须是非空数组");
            }
            for (JsonNode node : array) {
                validateStepNode(node, accessible);
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤 JSON 解析失败");
        }
    }

    /** 递归校验单个步骤节点（if/parallel/普通步骤） */
    private void validateStepNode(JsonNode node, Set<String> accessible) {
        // 条件分支
        String condition = node.path("if").asText("");
        if (!condition.isBlank()) {
            JsonNode thenNode = node.path("then");
            if (thenNode.isArray()) {
                for (JsonNode item : thenNode) {
                    validateStepNode(item, accessible);
                }
            }
            JsonNode elseNode = node.path("else");
            if (elseNode.isArray()) {
                for (JsonNode item : elseNode) {
                    validateStepNode(item, accessible);
                }
            }
            return;
        }
        // 并行组
        JsonNode parallel = node.path("parallel");
        if (parallel.isArray() && !parallel.isEmpty()) {
            for (JsonNode item : parallel) {
                validateStepTool(item.path("tool").asText(""), accessible);
            }
            return;
        }
        // 普通步骤
        validateStepTool(node.path("tool").asText(""), accessible);
    }

    private void validateStepTool(String tool, Set<String> accessible) {
        if (tool.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "步骤缺少 tool 字段");
        }
        if (!toolRegistry.has(tool)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "步骤引用了未登记的工具: " + tool);
        }
        if (!accessible.contains(tool)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "步骤引用了未授权的工具: " + tool);
        }
    }
}
