package com.devmind.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G4 证据包：把某次运行的所有证据读出来、逐条重算哈希、校验链是否完整。
 *
 * <p>校验只依赖库里的字段（不依赖原文），因此能发现「记录被静默改写」；
 * 但它防不了「整条链被同时重写」——那需要外部锚定，边界写在 README 里。</p>
 */
class OperationEvidenceVerifierTest {

    private static final Long TENANT = 1L;
    private static final Long RUN = 100L;

    private OperationEvidenceRepository repository;
    private OperationEvidenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(OperationEvidenceRepository.class);
        service = new OperationEvidenceService(repository);
    }

    @Test
    void verifiesIntactDecisionExecutionChain() {
        OperationEvidence decide = record(999L, "APPROVE", "财务审批", "7", "同意",
                hash("{\"amount\":\"4800\"}"), null, OperationEvidenceService.GENESIS);
        OperationEvidence execute = record(999L, "EXECUTE", null, "system", null,
                null, hash("{\"id\":\"R-1\"}"), decide.recordHash());
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(decide, execute));

        OperationEvidenceService.ChainVerification result = service.verifyChain(TENANT, RUN);

        assertThat(result.valid()).isTrue();
        assertThat(result.recordCount()).isEqualTo(2);
        assertThat(result.brokenIndex()).isEqualTo(-1);
        assertThat(result.reason()).isNull();
    }

    @Test
    void detectsTamperedRecordHash() {
        OperationEvidence decide = record(999L, "APPROVE", "财务审批", "7", "同意", null, null,
                OperationEvidenceService.GENESIS);
        // 第二条的 recordHash 被改了一个字符
        OperationEvidence tampered = new OperationEvidence(TENANT, RUN, 999L, "EXECUTE", null, "system", null,
                null, hash("{\"id\":\"R-1\"}"), decide.recordHash(), "deadbeef" + "0".repeat(56));
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(decide, tampered));

        OperationEvidenceService.ChainVerification result = service.verifyChain(TENANT, RUN);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenIndex()).isEqualTo(2);
        assertThat(result.reason()).contains("哈希不匹配");
    }

    @Test
    void detectsBrokenLinkToPreviousRecord() {
        OperationEvidence decide = record(999L, "APPROVE", "财务审批", "7", "同意", null, null,
                OperationEvidenceService.GENESIS);
        // 第二条 prevHash 指向了一个不存在的哈希（顺序被挪动/删除过）
        OperationEvidence detached = record(999L, "EXECUTE", null, "system", null, null,
                hash("{\"id\":\"R-1\"}"), "not-the-previous-hash");
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(decide, detached));

        OperationEvidenceService.ChainVerification result = service.verifyChain(TENANT, RUN);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenIndex()).isEqualTo(2);
        assertThat(result.reason()).contains("前序哈希断链");
    }

    @Test
    void chainMustStartFromGenesis() {
        // 只留下后面的记录、链首被删：第一条 prevHash 不是 GENESIS
        OperationEvidence orphan = record(999L, "EXECUTE", null, "system", null, null, hash("{}"),
                "some-hash");
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(orphan));

        OperationEvidenceService.ChainVerification result = service.verifyChain(TENANT, RUN);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenIndex()).isEqualTo(1);
        assertThat(result.reason()).contains("链首");
    }

    @Test
    void emptyChainIsReportedAsEmptyNotValid() {
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of());

        OperationEvidenceService.ChainVerification result = service.verifyChain(TENANT, RUN);

        assertThat(result.valid()).isFalse();
        assertThat(result.recordCount()).isZero();
        assertThat(result.brokenIndex()).isEqualTo(-1);
        assertThat(result.reason()).contains("无证据");
    }

    @Test
    void packageSummarizesApprovalAndExecution() {
        OperationEvidence decide = record(999L, "APPROVE", "财务审批", "7", "同意", null, null,
                OperationEvidenceService.GENESIS);
        OperationEvidence execute = record(999L, "EXECUTE", null, "system", null, null,
                hash("{\"id\":\"R-1\"}"), decide.recordHash());
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(decide, execute));

        OperationEvidenceService.EvidencePackage pkg = service.evidencePackage(TENANT, RUN);

        assertThat(pkg.runId()).isEqualTo(RUN);
        assertThat(pkg.valid()).isTrue();
        assertThat(pkg.recordCount()).isEqualTo(2);
        assertThat(pkg.approved()).isTrue();
        assertThat(pkg.rejected()).isFalse();
        assertThat(pkg.executed()).isTrue();
        assertThat(pkg.decidedBy()).isEqualTo("7");
        assertThat(pkg.lastRecordHash()).isEqualTo(execute.recordHash());
        assertThat(pkg.records()).hasSize(2);
    }

    @Test
    void packageReportsRejectionWithoutExecution() {
        OperationEvidence decide = record(999L, "REJECT", "财务审批", "7", "金额超限", null, null,
                OperationEvidenceService.GENESIS);
        when(repository.listByRun(TENANT, RUN)).thenReturn(List.of(decide));

        OperationEvidenceService.EvidencePackage pkg = service.evidencePackage(TENANT, RUN);

        assertThat(pkg.valid()).isTrue();
        assertThat(pkg.approved()).isFalse();
        assertThat(pkg.rejected()).isTrue();
        assertThat(pkg.executed()).isFalse();
        assertThat(pkg.decidedBy()).isEqualTo("7");
    }

    // ---- helpers：测试端独立造一条哈希正确的证据 ----

    private static String canonical(Long approvalId, String action, String nodeTitle, String decidedBy,
                                    String comment, String beforeHash, String afterHash, String prevHash) {
        return OperationEvidenceService.canonical(TENANT, RUN, approvalId, action, nodeTitle, decidedBy,
                comment, beforeHash, afterHash, prevHash);
    }

    private static OperationEvidence record(Long approvalId, String action, String nodeTitle, String decidedBy,
                                            String comment, String beforeHash, String afterHash,
                                            String prevHash) {
        String recordHash = hash(canonical(approvalId, action, nodeTitle, decidedBy, comment,
                beforeHash, afterHash, prevHash));
        return new OperationEvidence(TENANT, RUN, approvalId, action, nodeTitle, decidedBy, comment,
                beforeHash, afterHash, prevHash, recordHash);
    }

    private static String hash(String value) {
        return OperationEvidenceService.sha256(value);
    }
}
