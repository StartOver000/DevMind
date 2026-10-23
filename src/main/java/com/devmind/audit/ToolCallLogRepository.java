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
                    (tenant_id, user_id, tool_name, tool_type, source, workflow_run_id,
                     status, cost_ms, error, args_hash, decision, policy_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                log.tenantId(), log.userId(), log.toolName(), log.toolType(),
                log.source(), log.workflowRunId(), log.status(), log.costMs(), log.error(),
                log.argsHash(), log.decision(), log.policyId()
        );
    }

    /**
     * 按工具聚合统计。userId 为 null 时统计整个租户。
     *
     * @return 每行 {toolName, toolType, total, successCount, failCount, deniedCount, avgCostMs, lastTime}
     */
    public List<Map<String, Object>> stats(Long tenantId, Long userId, int days) {
        StringBuilder sql = new StringBuilder("""
                SELECT tool_name, tool_type, COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'success') AS success_count,
                       COUNT(*) FILTER (WHERE status = 'fail') AS fail_count,
                       COUNT(*) FILTER (WHERE status = 'denied') AS denied_count,
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
                SELECT id, user_id, tool_name, tool_type, source, workflow_run_id, status,
                       decision, policy_id, args_hash, cost_ms, error, created_time
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

    /**
     * 单用户最近 N 分钟的工具调用时间线（G2 回放）：按发生顺序返回，含授权结论与策略号。
     * 只回放一个用户的连续操作，因此不需要会话 id —— 同一用户的写操作序列就是一次「操作轨迹」。
     *
     * @param minutes 回放窗口（分钟），夹在 1..1440
     */
    public List<Map<String, Object>> timeline(Long tenantId, Long userId, int minutes) {
        int window = Math.max(1, Math.min(minutes, 24 * 60));
        return jdbcTemplate.queryForList("""
                SELECT id, user_id, tool_name, tool_type, source, workflow_run_id, status,
                       decision, policy_id, args_hash, cost_ms, error, created_time
                FROM tool_call_log
                WHERE tenant_id = ? AND user_id = ?
                  AND created_time >= CURRENT_TIMESTAMP - make_interval(mins => ?)
                ORDER BY id ASC
                LIMIT 500
                """, tenantId, userId, window);
    }

    /**
     * 审计记录。
     *
     * @param argsHash 参数摘要（不落原文，避免把敏感参数写进审计）
     * @param decision ALLOW / DENY（G1 授权结论）
     * @param policyId 命中的策略编号（回放时说明「为什么允许/为什么拦住」）
     */
    public record ToolCallLog(
            Long tenantId,
            Long userId,
            String toolName,
            String toolType,
            String source,
            Long workflowRunId,
            String status,
            long costMs,
            String error,
            String argsHash,
            String decision,
            String policyId
    ) {
        /** 兼容旧调用点（G1 之前的 9 参形态） */
        public ToolCallLog(Long tenantId, Long userId, String toolName, String toolType,
                           String source, Long workflowRunId, String status, long costMs, String error) {
            this(tenantId, userId, toolName, toolType, source, workflowRunId, status, costMs, error,
                    null, "ALLOW", null);
        }
    }
}
