package com.devmind.workflow;

/**
 * 工作流人工审批请求（对应表 workflow_approval，P2-3 human-in-the-loop）：
 * 执行到审批节点时暂停 run（WAITING_APPROVAL），记录审批请求；
 * 审批人通过/拒绝后恢复或终止执行。vars_snapshot 保存审批点变量快照，供恢复执行。
 */
public record WorkflowApproval(
        Long id,
        Long workflowId,
        Long runId,
        Long tenantId,
        String title,
        String assignee,
        String status,      // PENDING | APPROVED | REJECTED
        String comment,
        String varsSnapshot,
        Integer stepIndex,  // 审批点在顶层单元中的下标（恢复执行定位用）
        String createdAt,
        String decidedAt,
        String decidedBy
) {
}
