package com.devmind.workflow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 工作流定义仓储（workflow 表） */
@Repository
public class WorkflowRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkflowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS = """
            id, tenant_id, name, description, steps_json, trigger_type,
            cron_expr, scope, status, created_by, created_time::text
            """;

    private Workflow map(ResultSet rs, int rowNum) throws SQLException {
        return new Workflow(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("steps_json"),
                rs.getString("trigger_type"),
                rs.getString("cron_expr"),
                rs.getString("scope"),
                rs.getString("status"),
                rs.getLong("created_by"),
                rs.getString("created_time")
        );
    }

    public List<Workflow> listAll(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM workflow WHERE tenant_id = ? AND status <> 'DELETED' ORDER BY id DESC",
                this::map, tenantId
        );
    }

    /** 按触发方式列出启用的工作流（定时调度扫描用） */
    public List<Workflow> listEnabledByTrigger(Long tenantId, String triggerType) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM workflow WHERE tenant_id = ? AND trigger_type = ? AND status = 'ENABLED' ORDER BY id",
                this::map, tenantId, triggerType
        );
    }

    public Workflow findById(Long tenantId, Long id) {
        List<Workflow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM workflow WHERE tenant_id = ? AND id = ? AND status <> 'DELETED'",
                this::map, tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按 webhook token 查工作流（外部调用触发用，token 全局唯一） */
    public Workflow findByWebhookToken(String token) {
        List<Workflow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM workflow WHERE webhook_token = ? AND status <> 'DELETED'",
                this::map, token
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String findWebhookToken(Long tenantId, Long id) {
        List<String> rows = jdbcTemplate.query(
                "SELECT webhook_token FROM workflow WHERE tenant_id = ? AND id = ?",
                (rs, i) -> rs.getString(1),
                tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveWebhookToken(Long tenantId, Long id, String token) {
        jdbcTemplate.update(
                "UPDATE workflow SET webhook_token = ?, updated_time = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                token, id, tenantId
        );
    }

    public void clearWebhookToken(Long tenantId, Long id) {
        jdbcTemplate.update(
                "UPDATE workflow SET webhook_token = NULL, updated_time = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
    }

    public Long insert(Workflow w) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO workflow (tenant_id, name, description, steps_json, trigger_type,
                                      cron_expr, scope, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                w.tenantId(), w.name(), w.description(), w.stepsJson(), w.triggerType(),
                w.cronExpr(), w.scope(), w.status(), w.createdBy()
        );
    }

    public void update(Long tenantId, Workflow w) {
        jdbcTemplate.update(
                """
                UPDATE workflow SET name = ?, description = ?, steps_json = ?,
                    trigger_type = ?, cron_expr = ?, scope = ?, status = ?,
                    updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """,
                w.name(), w.description(), w.stepsJson(), w.triggerType(),
                w.cronExpr(), w.scope(), w.status(), w.id(), tenantId
        );
    }

    public void softDelete(Long tenantId, Long id) {
        jdbcTemplate.update(
                "UPDATE workflow SET status = 'DELETED', updated_time = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
    }
}
