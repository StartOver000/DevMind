package com.devmind.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G3 证据链：审批决定 / 执行结果各落一条带前序哈希的证据，链式可校验。
 *
 * <p>哈希口径（写进 {@code OperationEvidenceService} 文档，测试独立复算）：
 * {@code tenantId|runId|approvalId|action|nodeTitle|decidedBy|beforeHash|afterHash|prevHash}，
 * 空值按空串，SHA-256 小写 hex。</p>
 */
class OperationEvidenceServiceTest {

    private OperationEvidenceRepository repository;
    private OperationEvidenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(OperationEvidenceRepository.class);
        service = new OperationEvidenceService(repository);
    }

    @Test
    void firstRecordStartsChainFromGenesisAndHashesSnapshot() {
        when(repository.findLastHash(1L, 100L)).thenReturn(null);

        String recordHash = service.appendDecision(1L, 100L, 999L, "财务审批",
                true, "7", "同意", "{\"amount\":\"4800\"}");

        OperationEvidence saved = captureInsert();
        assertThat(saved.prevHash()).isEqualTo(OperationEvidenceService.GENESIS);
        assertThat(saved.action()).isEqualTo("APPROVE");
        assertThat(saved.beforeHash()).isEqualTo(sha256("{\"amount\":\"4800\"}"));
        assertThat(saved.afterHash()).isNull();
        assertThat(recordHash).isEqualTo(saved.recordHash());
        assertThat(recordHash).isEqualTo(sha256("1|100|999|APPROVE|财务审批|7|同意|"
                + sha256("{\"amount\":\"4800\"}") + "||GENESIS"));
    }

    @Test
    void laterRecordChainsToPreviousHash() {
        when(repository.findLastHash(1L, 100L)).thenReturn("prev-hash-abc");

        String recordHash = service.appendDecision(1L, 100L, 999L, "财务审批",
                false, "7", "金额不对", null);

        OperationEvidence saved = captureInsert();
        assertThat(saved.prevHash()).isEqualTo("prev-hash-abc");
        assertThat(saved.beforeHash()).isNull();
        assertThat(saved.recordHash()).isEqualTo(sha256("1|100|999|REJECT|财务审批|7|金额不对|||prev-hash-abc"));
        assertThat(recordHash).isEqualTo(saved.recordHash());
    }

    @Test
    void executionRecordChainsAndHashesAfterState() {
        when(repository.findLastHash(1L, 100L)).thenReturn("decision-hash");

        service.appendExecution(1L, 100L, 999L, "{\"reimburseId\":\"R-1\"}");

        OperationEvidence saved = captureInsert();
        assertThat(saved.action()).isEqualTo("EXECUTE");
        assertThat(saved.prevHash()).isEqualTo("decision-hash");
        assertThat(saved.afterHash()).isEqualTo(sha256("{\"reimburseId\":\"R-1\"}"));
        assertThat(saved.recordHash()).isEqualTo(sha256("1|100|999|EXECUTE||system|||"
                + sha256("{\"reimburseId\":\"R-1\"}") + "|decision-hash"));
    }

    @Test
    void blankAfterStateYieldsNullHash() {
        when(repository.findLastHash(1L, 100L)).thenReturn(null);

        service.appendExecution(1L, 100L, 999L, null);

        assertThat(captureInsert().afterHash()).isNull();
    }

    @Test
    void sameInputsProduceSameHash() {
        when(repository.findLastHash(1L, 100L)).thenReturn("x");

        String first = service.appendDecision(1L, 100L, 999L, "审批", true, "7", null, "{}");
        String second = service.appendDecision(1L, 100L, 999L, "审批", true, "7", null, "{}");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void chainQueryIsScopedToTenantAndRun() {
        when(repository.findLastHash(2L, 200L)).thenReturn(null);

        service.appendDecision(2L, 200L, 1L, "审批", true, "9", null, "{}");

        verify(repository).findLastHash(2L, 200L);
        assertThat(captureInsert().tenantId()).isEqualTo(2L);
        assertThat(captureInsert().runId()).isEqualTo(200L);
    }

    private OperationEvidence captureInsert() {
        ArgumentCaptor<OperationEvidence> captor = ArgumentCaptor.forClass(OperationEvidence.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
