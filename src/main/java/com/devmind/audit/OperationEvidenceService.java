package com.devmind.audit;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 操作证据链（G3）：审批决定与执行结果各落一条证据，用前序哈希串成链，事后可校验顺序未被篡改。
 *
 * <p>哈希口径（对测试是稳定契约）：</p>
 * <pre>
 * canonical = tenantId|runId|approvalId|action|nodeTitle|decidedBy|beforeHash|afterHash|prevHash
 * recordHash = SHA-256(canonical) 小写 hex；null 字段按空串；链首 prevHash = {@value #GENESIS}
 * </pre>
 *
 * <p>这不是 WORM，也不是外部可验证的存证：单库单机下它能证明「这条记录之后没被静默改过，
 * 且与前一条同源」，防的是应用层误操作与随手改库。</p>
 */
@Service
public class OperationEvidenceService {

    public static final String GENESIS = "GENESIS";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_EXECUTE = "EXECUTE";

    private final OperationEvidenceRepository repository;

    public OperationEvidenceService(OperationEvidenceRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录一次审批决定。
     *
     * @param varsSnapshot 审批点变量快照（决定前状态），会哈希后入库、不留原文
     * @return 本条 recordHash
     */
    public synchronized String appendDecision(Long tenantId, Long runId, Long approvalId, String nodeTitle,
                                              boolean approved, String decidedBy, String comment,
                                              String varsSnapshot) {
        return append(tenantId, runId, approvalId,
                approved ? ACTION_APPROVE : ACTION_REJECT,
                nodeTitle, decidedBy, comment,
                hashOrNull(varsSnapshot), null);
    }

    /**
     * 记录审批通过后的执行结果（决定之后的状态），与决定记录同链。
     *
     * @param varsAfterJson 续跑结束时的变量快照 JSON
     * @return 本条 recordHash
     */
    public synchronized String appendExecution(Long tenantId, Long runId, Long approvalId, String varsAfterJson) {
        return append(tenantId, runId, approvalId, ACTION_EXECUTE, null, "system", null,
                null, hashOrNull(varsAfterJson));
    }

    private String append(Long tenantId, Long runId, Long approvalId, String action, String nodeTitle,
                          String decidedBy, String comment, String beforeHash, String afterHash) {
        String previous = repository.findLastHash(tenantId, runId);
        String prevHash = previous == null ? GENESIS : previous;
        String recordHash = sha256(canonical(tenantId, runId, approvalId, action, nodeTitle, decidedBy,
                comment, beforeHash, afterHash, prevHash));
        repository.insert(new OperationEvidence(tenantId, runId, approvalId, action, nodeTitle, decidedBy,
                comment, beforeHash, afterHash, prevHash, recordHash));
        return recordHash;
    }

    /**
     * G4：校验某次运行的证据链是否完整（逐条重算哈希 + 校验前序衔接）。
     *
     * @return 链校验结果；{@code brokenIndex} 是 1-based 记录序号，未断链为 -1
     */
    public ChainVerification verifyChain(Long tenantId, Long runId) {
        return verifyRecords(repository.listByRun(tenantId, runId));
    }

    /**
     * G4：证据包——把一次运行的全部证据 + 链校验结果 + 五要素摘要组装成可直接出 JSON 的结构。
     */
    public EvidencePackage evidencePackage(Long tenantId, Long runId) {
        List<OperationEvidence> records = repository.listByRun(tenantId, runId);
        ChainVerification verification = verifyRecords(records);
        boolean approved = records.stream().anyMatch(r -> ACTION_APPROVE.equals(r.action()));
        boolean rejected = records.stream().anyMatch(r -> ACTION_REJECT.equals(r.action()));
        boolean executed = records.stream().anyMatch(r -> ACTION_EXECUTE.equals(r.action()));
        String decidedBy = records.stream()
                .filter(r -> ACTION_APPROVE.equals(r.action()) || ACTION_REJECT.equals(r.action()))
                .map(OperationEvidence::decidedBy)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        String lastRecordHash = records.isEmpty() ? null : records.get(records.size() - 1).recordHash();
        return new EvidencePackage(runId, verification.valid(), verification.recordCount(),
                verification.brokenIndex(), verification.reason(), approved, rejected, executed,
                decidedBy, lastRecordHash, records);
    }

    private ChainVerification verifyRecords(List<OperationEvidence> records) {
        if (records.isEmpty()) {
            // 空链不等于「被篡改」：brokenIndex 用 -1，调用方据此区分「无证据」与「断链」
            return new ChainVerification(false, 0, -1, "无证据：该运行没有审批或执行记录");
        }
        String expectedPrev = GENESIS;
        for (int i = 0; i < records.size(); i++) {
            OperationEvidence record = records.get(i);
            int index = i + 1;
            if (i == 0 && !GENESIS.equals(record.prevHash())) {
                return new ChainVerification(false, records.size(), index,
                        "链首前序哈希不是 GENESIS（首条记录可能被删除）");
            }
            if (i > 0 && !expectedPrev.equals(record.prevHash())) {
                return new ChainVerification(false, records.size(), index,
                        "前序哈希断链：第 " + index + " 条与前一条不衔接（记录被删或乱序）");
            }
            String recomputed = sha256(canonical(record.tenantId(), record.runId(), record.approvalId(),
                    record.action(), record.nodeTitle(), record.decidedBy(), record.comment(),
                    record.beforeHash(), record.afterHash(), record.prevHash()));
            if (!recomputed.equals(record.recordHash())) {
                return new ChainVerification(false, records.size(), index,
                        "哈希不匹配：第 " + index + " 条记录内容被改动");
            }
            expectedPrev = record.recordHash();
        }
        return new ChainVerification(true, records.size(), -1, null);
    }

    /** G4 链校验结果：valid + 断点位置（1-based，-1 表示完整） */
    public record ChainVerification(boolean valid, int recordCount, int brokenIndex, String reason) {
    }

    /** G4 证据包：链校验 + 五要素摘要 + 原始证据行 */
    public record EvidencePackage(Long runId, boolean valid, int recordCount, int brokenIndex, String reason,
                                  boolean approved, boolean rejected, boolean executed, String decidedBy,
                                  String lastRecordHash, List<OperationEvidence> records) {
    }

    /** 哈希输入口径；改这里等于改协议，必须同步全部测试与文档 */
    static String canonical(Long tenantId, Long runId, Long approvalId, String action, String nodeTitle,
                            String decidedBy, String comment, String beforeHash, String afterHash,
                            String prevHash) {
        return join(tenantId) + "|" + join(runId) + "|" + join(approvalId) + "|" + nullSafe(action)
                + "|" + nullSafe(nodeTitle) + "|" + nullSafe(decidedBy) + "|" + nullSafe(comment)
                + "|" + nullSafe(beforeHash) + "|" + nullSafe(afterHash) + "|" + nullSafe(prevHash);
    }

    private static String hashOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return sha256(value);
    }

    private static String join(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
