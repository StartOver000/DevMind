package com.devmind.workflow;

import com.devmind.workflow.WorkflowService.WorkflowRunDetail;
import com.devmind.workflow.dto.WorkflowCreateRequest;
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

import java.util.List;
import java.util.Map;

/** 工作流 API：CRUD + 手动运行 + 运行记录 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping
    public Workflow create(
            @Valid @RequestBody WorkflowCreateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return workflowService.create(request, userId);
    }

    @GetMapping
    public List<Workflow> list() {
        return workflowService.list();
    }

    @GetMapping("/{id}")
    public Workflow get(@PathVariable Long id) {
        return workflowService.get(id);
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

    @GetMapping("/{id}/runs")
    public List<WorkflowRun> runs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return workflowService.runList(id, limit);
    }

    @GetMapping("/runs/{runId}")
    public WorkflowRunDetail runDetail(@PathVariable Long runId) {
        return workflowService.runDetail(runId);
    }
}
