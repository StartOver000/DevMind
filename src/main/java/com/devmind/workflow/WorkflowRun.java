package com.devmind.workflow;

/** 一次工作流执行记录（对应表 workflow_run） */
public record WorkflowRun(
        Long id,
        Long workflowId,
        Long tenantId,
        String triggerType,
        String status,     // RUNNING | SUCCESS | FAILED
        Double totalCost,
        String startedAt,
        String finishedAt,
        String error
) {
}
