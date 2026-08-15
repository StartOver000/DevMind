package com.devmind.workflow;

import com.devmind.workflow.WorkflowService.WorkflowRunDetail;
import com.devmind.workflow.dto.WorkflowCreateRequest;
import com.devmind.workflow.dto.WorkflowGenerateRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 工作流 API：CRUD + 手动运行 + 运行记录 + 对话式生成草案 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowGenerationService generationService;
    private final com.devmind.common.SsePusher ssePusher;

    public WorkflowController(WorkflowService workflowService, WorkflowGenerationService generationService,
                              com.devmind.common.SsePusher ssePusher) {
        this.workflowService = workflowService;
        this.generationService = generationService;
        this.ssePusher = ssePusher;
    }

    /** 对话式生成工作流草案（业务人员大白话描述 → LLM 生成步骤 + 可直接创建的 stepsJson） */
    @PostMapping("/generate")
    public Map<String, Object> generate(
            @Valid @RequestBody WorkflowGenerateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        WorkflowGenerationService.GenerationResult result =
                generationService.generate(userId, request.description());
        return Map.of("steps", result.steps(), "stepsJson", result.stepsJson());
    }

    @PostMapping
    public Workflow create(
            @Valid @RequestBody WorkflowCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.create(request, userId);
    }

    @GetMapping
    public List<Workflow> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.list(userId);
    }

    @GetMapping("/{id}")
    public Workflow get(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.get(id, userId);
    }

    /** webhook 触发信息（token + 调用 URL），仅 webhook 类型工作流启用 */
    @GetMapping("/{id}/webhook")
    public Map<String, Object> webhookInfo(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.webhookInfo(id, userId);
    }

    @PutMapping("/{id}")
    public Workflow update(
            @PathVariable Long id,
            @Valid @RequestBody WorkflowCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        workflowService.delete(id, userId);
        return Map.of("deleted", true);
    }

    /** 手动运行（同步执行） */
    @PostMapping("/{id}/run")
    public WorkflowRun run(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.run(id, userId);
    }

    /**
     * 流式执行（SSE 步骤进度实时推送）：
     * event: step —— 每步执行结果（runId/stepIndex/toolName/status/error）
     * event: done —— run 最终结果
     */
    @PostMapping("/{id}/run/stream")
    public SseEmitter runStream(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        SseEmitter emitter = ssePusher.createEmitter();
        ssePusher.async(emitter, () -> {
            WorkflowRun result = workflowService.runStream(id, userId, event -> {
                try {
                    ssePusher.sendJson(emitter, "step", event);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            try {
                ssePusher.sendJson(emitter, "done", result);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            emitter.complete();
        });
        return emitter;
    }

    @GetMapping("/{id}/runs")
    public List<WorkflowRun> runs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.runList(id, limit, userId);
    }

    @GetMapping("/runs/{runId}")
    public WorkflowRunDetail runDetail(
            @PathVariable Long runId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.runDetail(runId, userId);
    }

    // ---------- 人工审批（P2-3 human-in-the-loop） ----------

    /** 运行挂起的审批请求列表 */
    @GetMapping("/{id}/runs/{runId}/approvals")
    public List<WorkflowApproval> approvals(
            @PathVariable Long id,
            @PathVariable Long runId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.approvals(runId, userId);
    }

    /** 审批通过：恢复执行审批节点之后的步骤 */
    @PostMapping("/{id}/runs/{runId}/approvals/{approvalId}/approve")
    public WorkflowRun approve(
            @PathVariable Long id,
            @PathVariable Long runId,
            @PathVariable Long approvalId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        String comment = body == null ? null : body.get("comment");
        return workflowService.decideApproval(runId, approvalId, true, comment, userId);
    }

    /** 审批拒绝：run 置 REJECTED */
    @PostMapping("/{id}/runs/{runId}/approvals/{approvalId}/reject")
    public WorkflowRun reject(
            @PathVariable Long id,
            @PathVariable Long runId,
            @PathVariable Long approvalId,
            @RequestBody(required = false) Map<String, String> body,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        String comment = body == null ? null : body.get("comment");
        return workflowService.decideApproval(runId, approvalId, false, comment, userId);
    }
}
