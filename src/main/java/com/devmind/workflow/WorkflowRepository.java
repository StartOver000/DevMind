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

    public Workflow findById(Long tenantId, Long id) {
        List<Workflow> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM workflow WHERE tenant_id = ? AND id = ? AND status <> 'DELETED'",
                this::map, tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
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
