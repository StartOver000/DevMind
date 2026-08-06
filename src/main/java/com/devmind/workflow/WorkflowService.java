package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.workflow.dto.WorkflowCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工作流管理：CRUD + 手动运行 + 运行记录查询。
 * 执行委托 {@link WorkflowExecutor}（复用 Agent 工具引擎）。
 */
@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    private static final Long DEFAULT_TENANT = 1L; // M1 单租户

    private final WorkflowRepository repository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutor executor;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public WorkflowService(
            WorkflowRepository repository,
            WorkflowRunRepository runRepository,
            WorkflowExecutor executor,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.executor = executor;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public List<Workflow> list() {
        return repository.listAll(DEFAULT_TENANT);
    }

    public Workflow get(Long id) {
        return requireWorkflow(id);
    }

    @Transactional
    public Workflow create(WorkflowCreateRequest req, Long userId) {
        validateSteps(req.stepsJson());
        String trigger = req.triggerType() == null ? "manual" : req.triggerType();
        String scope = req.scope() == null ? "private" : req.scope();
        String status = req.status() == null ? "ENABLED" : req.status();
        Workflow workflow = Workflow.forInsert(
                DEFAULT_TENANT, req.name(), req.description(), req.stepsJson(),
                trigger, req.cronExpr(), scope, status, userId
        );
        Long id = repository.insert(workflow);
        log.info("创建工作流 {} (id={}, by user={})", req.name(), id, userId);
        return requireWorkflow(id);
    }

    @Transactional
    public Workflow update(Long id, WorkflowCreateRequest req, Long userId) {
        Workflow existing = requireWorkflow(id);
        validateSteps(req.stepsJson());
        Workflow updated = new Workflow(
                id, existing.tenantId(), req.name(), req.description(), req.stepsJson(),
                req.triggerType() == null ? existing.triggerType() : req.triggerType(),
                req.cronExpr(), req.scope() == null ? existing.scope() : req.scope(),
                req.status() == null ? existing.status() : req.status(),
                existing.createdBy(), existing.createdTime()
        );
        repository.update(DEFAULT_TENANT, updated);
        log.info("更新工作流 {} (id={}, by user={})", req.name(), id, userId);
        return requireWorkflow(id);
    }

    public void delete(Long id, Long userId) {
        requireWorkflow(id);
        repository.softDelete(DEFAULT_TENANT, id);
        log.info("删除工作流 (id={}, by user={})", id, userId);
    }

    /** 手动运行：同步执行，返回本次运行记录 */
    public WorkflowRun run(Long id, Long userId) {
        Workflow workflow = requireWorkflow(id);
        if (!"ENABLED".equals(workflow.status())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流已停用");
        }
        log.info("手动运行工作流 {} (id={}, by user={})", workflow.name(), id, userId);
        return executor.execute(workflow, userId, "manual");
    }

    public List<WorkflowRun> runList(Long workflowId, int limit) {
        requireWorkflow(workflowId);
        return runRepository.listRuns(DEFAULT_TENANT, workflowId, Math.min(limit, 50));
    }

    /** 运行详情：run + 步骤明细 */
    public WorkflowRunDetail runDetail(Long runId) {
        WorkflowRun run = runRepository.findRun(DEFAULT_TENANT, runId);
        if (run == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "运行记录不存在: " + runId);
        }
        return new WorkflowRunDetail(run, runRepository.listSteps(runId));
    }

    public record WorkflowRunDetail(WorkflowRun run, List<WorkflowRunStep> steps) {
    }

    private Workflow requireWorkflow(Long id) {
        Workflow workflow = repository.findById(DEFAULT_TENANT, id);
        if (workflow == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流不存在: " + id);
        }
        return workflow;
    }

    /** 校验 steps_json：必须是数组、每步 tool 已注册且不为空 */
    private void validateSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤不能为空");
        }
        try {
            JsonNode array = objectMapper.readTree(stepsJson);
            if (!array.isArray() || array.isEmpty()) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤必须是非空数组");
            }
            for (JsonNode node : array) {
                String tool = node.path("tool").asText("");
                if (tool.isBlank()) {
                    throw new ApiException(ErrorCode.INVALID_ARGUMENT, "步骤缺少 tool 字段");
                }
                if (!toolRegistry.has(tool)) {
                    throw new ApiException(ErrorCode.INVALID_ARGUMENT, "步骤引用了未登记的工具: " + tool);
                }
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤 JSON 解析失败");
        }
    }
}
