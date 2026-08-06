package com.devmind.mcp;

/**
 * MCP 服务器登记定义（对应表 mcp_server）。
 * transportType: stdio（本地命令拉起）| http（远程 URL，SSE）。
 */
public record McpServerDefinition(
        Long id,
        Long tenantId,
        String name,
        String transportType,
        String command,
        String argsJson,     // JSON 数组，如 ["-y","@modelcontextprotocol/server-filesystem"]
        String url,
        String status,       // ENABLED | DISABLED | DELETED
        Long createdBy,
        String createdTime
) {
    public static McpServerDefinition forInsert(
            Long tenantId, String name, String transportType, String command,
            String argsJson, String url, Long createdBy
    ) {
        return new McpServerDefinition(null, tenantId, name, transportType,
                command, argsJson, url, "ENABLED", createdBy, null);
    }
}
