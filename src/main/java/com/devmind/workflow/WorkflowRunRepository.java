package com.devmind.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 工作流执行记录仓储（workflow_run / workflow_run_step） */
@Repository
public class WorkflowRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private WorkflowRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRun(
                rs.getLong("id"),
                rs.getLong("workflow_id"),
                rs.getLong("tenant_id"),
                rs.getString("trigger_type"),
                rs.getString("status"),
                rs.getDouble("total_cost"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("error")
        );
    }

    private WorkflowRunStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new WorkflowRunStep(
                rs.getLong("id"),
                rs.getLong("run_id"),
                rs.getInt("step_index"),
                rs.getString("tool_name"),
                rs.getString("input_json"),
                rs.getString("output_json"),
                rs.getString("status"),
                rs.getLong("cost_ms"),
                rs.getString("error"),
                rs.getString("created_time")
        );
    }

    public boolean hasRunning(Long tenantId, Long workflowId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE workflow_id = ? AND tenant_id = ? AND status = 'RUNNING'",
                Integer.class, workflowId, tenantId
        );
        return count != null && count > 0;
    }

    public Long insertRun(Long workflowId, Long tenantId, String triggerType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO workflow_run (workflow_id, tenant_id, trigger_type, status) VALUES (?, ?, ?, 'RUNNING') RETURNING id",
                Long.class, workflowId, tenantId, triggerType
        );
    }

    public void finishRun(Long runId, String status, String error) {
        jdbcTemplate.update(
                "UPDATE workflow_run SET status = ?, error = ?, finished_at = CURRENT_TIMESTAMP WHERE id = ?",
                status, error, runId
        );
    }

    /**
     * 把启动前已滞留超过 N 分钟的 RUNNING run 标记 FAILED（进程重启后清理历史卡死）。
     * 返回清理条数。加锁条件避免并发误杀正在执行的 run。
     */
    public int failStaleRuns(int staleMinutes) {
        return jdbcTemplate.update("""
                UPDATE workflow_run
                SET status = 'FAILED',
                    error = '执行滞留超过 ' || ? || ' 分钟，系统自动终止',
                    finished_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                  AND started_at < CURRENT_TIMESTAMP - make_interval(mins => ?)
                """, staleMinutes, staleMinutes);
    }

    /**
     * 启动时清理全部滞留 RUNNING run（单机场景：新进程启动即接管，旧进程残留都应终止）。
     * 防止进程被强杀后残留 RUNNING 阻塞对应工作流后续执行（如定时任务反复报"正在执行中"）。
     */
    public int failAllRunningOnStartup() {
        return jdbcTemplate.update("""
                UPDATE workflow_run
                SET status = 'FAILED',
                    error = '进程重启，遗留执行被终止',
                    finished_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING'
                """);
    }

    public void insertStep(Long runId, int index, String toolName, String inputJson, String outputJson,
                           String status, long costMs, String error) {
        jdbcTemplate.update(
                """
                INSERT INTO workflow_run_step (run_id, step_index, tool_name, input_json,
                                               output_json, status, cost_ms, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                runId, index, toolName, inputJson, outputJson, status, costMs, error
        );
    }

    public WorkflowRun findRun(Long tenantId, Long runId) {
        List<WorkflowRun> rows = jdbcTemplate.query(
                "SELECT id, workflow_id, tenant_id, trigger_type, status, total_cost, " +
                        "started_at::text, finished_at::text, error FROM workflow_run " +
                        "WHERE id = ? AND tenant_id = ?",
                this::mapRun, runId, tenantId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WorkflowRun> listRuns(Long tenantId, Long workflowId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, workflow_id, tenant_id, trigger_type, status, total_cost, " +
                        "started_at::text, finished_at::text, error FROM workflow_run " +
                        "WHERE workflow_id = ? AND tenant_id = ? ORDER BY id DESC LIMIT ?",
                this::mapRun, workflowId, tenantId, limit
        );
    }

    public List<WorkflowRunStep> listSteps(Long runId) {
        return jdbcTemplate.query(
                "SELECT id, run_id, step_index, tool_name, input_json, output_json, " +
                        "status, cost_ms, error, created_time::text FROM workflow_run_step " +
                        "WHERE run_id = ? ORDER BY step_index",
                this::mapStep, runId
        );
    }

    /** 按工作流聚合运行统计（M2-3 审计用量） */
    public List<Map<String, Object>> statsByWorkflow(Long tenantId, int days) {
        return jdbcTemplate.queryForList("""
                SELECT w.id AS workflow_id, w.name AS workflow_name,
                       COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE r.status = 'SUCCESS') AS success_count,
                       COUNT(*) FILTER (WHERE r.status = 'FAILED') AS fail_count,
                       COALESCE(SUM(r.total_cost), 0) AS total_cost
                FROM workflow_run r
                JOIN workflow w ON w.id = r.workflow_id
                WHERE r.tenant_id = ? AND r.started_at >= CURRENT_TIMESTAMP - make_interval(days => ?)
                GROUP BY w.id, w.name
                ORDER BY total DESC
                """, tenantId, days);
    }
}
