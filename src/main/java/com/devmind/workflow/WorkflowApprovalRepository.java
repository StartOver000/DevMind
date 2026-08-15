package com.devmind.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工作流审批请求存储（P2-3 human-in-the-loop）。
 * 审批节点执行时落一条 PENDING 请求；审批人决定后更新状态；
 * 恢复执行时按 vars_snapshot + step_index 续跑。
 */
@Repository
public class WorkflowApprovalRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowApprovalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long create(Long workflowId, Long runId, Long tenantId, String title, String assignee,
                       String varsSnapshot, Integer stepIndex) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO workflow_approval (workflow_id, run_id, tenant_id, title, assignee,
                                               status, vars_snapshot, step_index)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
                RETURNING id
                """, Long.class, workflowId, runId, tenantId, title, assignee, varsSnapshot, stepIndex);
    }

    public WorkflowApproval findById(Long tenantId, Long id) {
        List<WorkflowApproval> rows = jdbcTemplate.query(
                "SELECT id, workflow_id, run_id, tenant_id, title, assignee, status, comment, " +
                        "vars_snapshot, step_index, created_at::text, decided_at::text, decided_by " +
                        "FROM workflow_approval WHERE id = ? AND tenant_id = ?",
                this::map, id, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WorkflowApproval> listByRun(Long tenantId, Long runId) {
        return jdbcTemplate.query(
                "SELECT id, workflow_id, run_id, tenant_id, title, assignee, status, comment, " +
                        "vars_snapshot, step_index, created_at::text, decided_at::text, decided_by " +
                        "FROM workflow_approval WHERE tenant_id = ? AND run_id = ? ORDER BY id",
                this::map, tenantId, runId);
    }

    public void decide(Long id, String status, String comment, String decidedBy) {
        jdbcTemplate.update("""
                UPDATE workflow_approval
                SET status = ?, comment = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, comment, decidedBy, id);
    }

    private WorkflowApproval map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WorkflowApproval(
                rs.getLong("id"),
                rs.getLong("workflow_id"),
                rs.getLong("run_id"),
                rs.getLong("tenant_id"),
                rs.getString("title"),
                rs.getString("assignee"),
                rs.getString("status"),
                rs.getString("comment"),
                rs.getString("vars_snapshot"),
                rs.getObject("step_index") == null ? null : rs.getInt("step_index"),
                rs.getString("created_at"),
                rs.getString("decided_at"),
                rs.getString("decided_by")
        );
    }
}
