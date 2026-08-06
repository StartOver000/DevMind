package com.devmind.workflow;

/** 工作流单步执行记录（对应表 workflow_run_step） */
public record WorkflowRunStep(
        Long id,
        Long runId,
        Integer stepIndex,
        String toolName,
        String inputJson,
        String outputJson,
        String status,   // SUCCESS | FAILED
        Long costMs,
        String error,
        String createdTime
) {
}
