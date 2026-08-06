package com.devmind.mcp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** MCP 服务器登记仓储（mcp_server 表） */
@Repository
public class McpServerRepository {

    private final JdbcTemplate jdbcTemplate;

    public McpServerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS = """
            id, tenant_id, name, transport_type, command, args_json, url, status, created_by, created_time::text
            """;

    private McpServerDefinition map(ResultSet rs, int rowNum) throws SQLException {
        return new McpServerDefinition(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("name"),
                rs.getString("transport_type"),
                rs.getString("command"),
                rs.getString("args_json"),
                rs.getString("url"),
                rs.getString("status"),
                (Long) rs.getObject("created_by"),
                rs.getString("created_time")
        );
    }

    public List<McpServerDefinition> listAll(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM mcp_server WHERE tenant_id = ? AND status <> 'DELETED' ORDER BY id",
                this::map, tenantId
        );
    }

    /** 启动时加载启用的 MCP 服务器 */
    public List<McpServerDefinition> listEnabled(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM mcp_server WHERE tenant_id = ? AND status = 'ENABLED' ORDER BY id",
                this::map, tenantId
        );
    }

    public McpServerDefinition findById(Long tenantId, Long id) {
        List<McpServerDefinition> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM mcp_server WHERE tenant_id = ? AND id = ? AND status <> 'DELETED'",
                this::map, tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long insert(McpServerDefinition def) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO mcp_server (tenant_id, name, transport_type, command, args_json, url, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                def.tenantId(), def.name(), def.transportType(), def.command(),
                def.argsJson(), def.url(), def.status(), def.createdBy()
        );
    }

    public void update(Long tenantId, McpServerDefinition def) {
        jdbcTemplate.update(
                """
                UPDATE mcp_server SET name = ?, transport_type = ?, command = ?, args_json = ?, url = ?,
                    status = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """,
                def.name(), def.transportType(), def.command(), def.argsJson(), def.url(),
                def.status(), def.id(), tenantId
        );
    }

    public void softDelete(Long tenantId, Long id) {
        jdbcTemplate.update(
                "UPDATE mcp_server SET status = 'DELETED', updated_time = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
    }
}
