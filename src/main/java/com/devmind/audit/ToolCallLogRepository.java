package com.devmind.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具调用审计仓储（tool_call_log 表）。
 * 记录 Agent / 工作流每次工具调用的轨迹，供用量统计与审计查询。
 */
@Repository
@SuppressWarnings("null")
public class ToolCallLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 追加一条工具调用记录（审计写入失败不影响主流程，调用方自行 try/catch） */
    public void insert(ToolCallLog log) {
        jdbcTemplate.update("""
                INSERT INTO tool_call_log
                    (tenant_id, user_id, tool_name, tool_type, source, workflow_run_id, status, cost_ms, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                log.tenantId(), log.userId(), log.toolName(), log.toolType(),
                log.source(), log.workflowRunId(), log.status(), log.costMs(), log.error()
        );
    }

    /**
     * 按工具聚合统计。userId 为 null 时统计整个租户。
     *
     * @return 每行 {toolName, toolType, total, successCount, failCount, avgCostMs, lastTime}
     */
    public List<Map<String, Object>> stats(Long tenantId, Long userId, int days) {
        StringBuilder sql = new StringBuilder("""
                SELECT tool_name, tool_type, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'success') AS success_count,
                       COUNT(*) FILTER (WHERE status = 'fail') AS fail_count,
                       COALESCE(AVG(cost_ms), 0)::BIGINT AS avg_cost_ms,
                       MAX(created_time) AS last_time
                FROM tool_call_log
                WHERE tenant_id = ? AND created_time >= CURRENT_TIMESTAMP - make_interval(days => ?)
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId, days));
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        sql.append(" GROUP BY tool_name, tool_type ORDER BY total DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 工具调用明细（倒序）。userId 为 null 时查整个租户。 */
    public List<Map<String, Object>> logs(Long tenantId, Long userId, int days, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, tool_name, tool_type, source, status, cost_ms, error, created_time
                FROM tool_call_log
                WHERE tenant_id = ? AND created_time >= CURRENT_TIMESTAMP - make_interval(days => ?)
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId, days));
        if (userId != null) {
            sql.append(" AND user_id = ?");
            args.add(userId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.min(limit, 500));
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 审计记录 */
    public record ToolCallLog(
            Long tenantId,
            Long userId,
            String toolName,
            String toolType,
            String source,
            Long workflowRunId,
            String status,
            long costMs,
            String error
    ) {
    }
}
