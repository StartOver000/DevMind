package com.devmind.audit;

/**
 * 一条操作证据（G3）：审批决定或执行结果，带前序哈希形成链。
 *
 * @param action    APPROVE | REJECT | EXECUTE
 * @param beforeHash 决定前状态摘要（审批点变量快照的 SHA-256）；无快照为 null
 * @param afterHash  执行后状态摘要（续跑后变量的 SHA-256）；决策记录为 null
 * @param prevHash   同 run 上一条证据的 recordHash；链首为 {@code GENESIS}
 * @param recordHash 本条的 SHA-256（口径见 {@link OperationEvidenceService}）
 */
public record OperationEvidence(
        Long tenantId,
        Long runId,
        Long approvalId,
        String action,
        String nodeTitle,
        String decidedBy,
        String comment,
        String beforeHash,
        String afterHash,
        String prevHash,
        String recordHash
) {
}
