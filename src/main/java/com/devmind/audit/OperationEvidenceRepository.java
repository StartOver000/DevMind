package com.devmind.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 操作证据存取（G3）。表 {@code operation_evidence}：一条审批决定 / 一次执行结果一行，按 run 串成哈希链。
 */
@Repository
public class OperationEvidenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationEvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(OperationEvidence evidence) {
        jdbcTemplate.update("""
                INSERT INTO operation_evidence
                    (tenant_id, run_id, approval_id, action, node_title, decided_by, comment,
                     before_hash, after_hash, prev_hash, record_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evidence.tenantId(), evidence.runId(), evidence.approvalId(), evidence.action(),
                evidence.nodeTitle(), evidence.decidedBy(), evidence.comment(),
                evidence.beforeHash(), evidence.afterHash(), evidence.prevHash(), evidence.recordHash());
    }

    /** 同 run 最新一条证据的 recordHash（链尾）；无记录返回 null 表示链首 */
    public String findLastHash(Long tenantId, Long runId) {
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT record_hash FROM operation_evidence "
                        + "WHERE tenant_id = ? AND run_id = ? ORDER BY id DESC LIMIT 1",
                String.class, tenantId, runId);
        return hashes.isEmpty() ? null : hashes.get(0);
    }

    /** 某次运行的全部证据（按写入顺序），供回放/校验使用 */
    public List<OperationEvidence> listByRun(Long tenantId, Long runId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, run_id, approval_id, action, node_title, decided_by, comment,
                       before_hash, after_hash, prev_hash, record_hash
                FROM operation_evidence
                WHERE tenant_id = ? AND run_id = ?
                ORDER BY id ASC
                """, (rs, rowNum) -> new OperationEvidence(
                rs.getLong("tenant_id"),
                rs.getLong("run_id"),
                rs.getObject("approval_id", Long.class),
                rs.getString("action"),
                rs.getString("node_title"),
                rs.getString("decided_by"),
                rs.getString("comment"),
                rs.getString("before_hash"),
                rs.getString("after_hash"),
                rs.getString("prev_hash"),
                rs.getString("record_hash")), tenantId, runId);
    }
}
