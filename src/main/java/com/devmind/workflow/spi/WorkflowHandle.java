package com.devmind.workflow.spi;

/**
 * 工作流执行句柄：builtin 同步执行时即终态；异步引擎（n8n）用 externalRef 定位外部执行。
 */
public record WorkflowHandle(
        String engineId,
        Long runId,
        String status,      // RUNNING | SUCCESS | FAILED | UNKNOWN
        String error,
        String externalRef  // 外部引擎执行引用（如 n8n executionId），builtin 为 null
) {

    public static WorkflowHandle builtin(Long runId, String status, String error) {
        return new WorkflowHandle("builtin", runId, status, error, null);
    }

    public boolean terminal() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
