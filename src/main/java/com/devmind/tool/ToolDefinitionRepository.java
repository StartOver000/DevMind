package com.devmind.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 平台工具定义仓储：接口登记的持久化（tool_definition 表）。
 * M1 阶段单租户（tenant_id 默认 1）；P1 多租户时在此层强制 tenant 过滤。
 */
@Repository
public class ToolDefinitionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolDefinitionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS = """
            id, tenant_id, name, description, tool_type, endpoint_url, http_method,
            request_schema_json, response_desc, auth_type, auth_config_encrypted,
            mask_fields_json, status, created_by, created_time::text
            """;

    private ToolDefinition map(ResultSet rs, int rowNum) throws SQLException {
        return new ToolDefinition(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("tool_type"),
                rs.getString("endpoint_url"),
                rs.getString("http_method"),
                rs.getString("request_schema_json"),
                rs.getString("response_desc"),
                rs.getString("auth_type"),
                rs.getString("auth_config_encrypted"),
                rs.getString("mask_fields_json"),
                rs.getString("status"),
                rs.getLong("created_by"),
                rs.getString("created_time")
        );
    }

    public List<ToolDefinition> listEnabled(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tool_definition WHERE tenant_id = ? AND status = 'READY' ORDER BY id",
                this::map,
                tenantId
        );
    }

    public List<ToolDefinition> listAll(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tool_definition WHERE tenant_id = ? AND status <> 'DELETED' ORDER BY id",
                this::map,
                tenantId
        );
    }

    public ToolDefinition findById(Long tenantId, Long id) {
        List<ToolDefinition> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tool_definition WHERE tenant_id = ? AND id = ? AND status <> 'DELETED'",
                this::map,
                tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ToolDefinition findByName(String name) {
        List<ToolDefinition> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tool_definition WHERE name = ? AND status <> 'DELETED'",
                this::map,
                name
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long insert(ToolDefinition def) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO tool_definition
                    (tenant_id, name, description, tool_type, endpoint_url, http_method,
                     request_schema_json, response_desc, auth_type, auth_config_encrypted,
                     mask_fields_json, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                def.tenantId(), def.name(), def.description(), def.toolType(), def.endpointUrl(),
                def.httpMethod(), def.requestSchemaJson(), def.responseDesc(), def.authType(),
                def.authConfigEncrypted(), def.maskFieldsJson(), def.status(), def.createdBy()
        );
    }

    public void update(Long tenantId, ToolDefinition def) {
        jdbcTemplate.update(
                """
                UPDATE tool_definition SET
                    name = ?, description = ?, endpoint_url = ?, http_method = ?,
                    request_schema_json = ?, response_desc = ?, auth_type = ?,
                    auth_config_encrypted = ?, mask_fields_json = ?, status = ?,
                    updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """,
                def.name(), def.description(), def.endpointUrl(), def.httpMethod(),
                def.requestSchemaJson(), def.responseDesc(), def.authType(),
                def.authConfigEncrypted(), def.maskFieldsJson(), def.status(),
                def.id(), tenantId
        );
    }

    /** 软删除（保留审计痕迹） */
    public void softDelete(Long tenantId, Long id) {
        jdbcTemplate.update(
                "UPDATE tool_definition SET status = 'DELETED', updated_time = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
    }
}
