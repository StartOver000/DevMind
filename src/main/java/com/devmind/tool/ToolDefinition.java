package com.devmind.tool;

/**
 * 平台工具定义：登记的内部接口（或未来 MCP 工具）的元数据。
 * 对应表 tool_definition。登记后由 {@code InterfaceToolAdapter} 包装为 Agent 可调用工具。
 */
public record ToolDefinition(
        Long id,
        Long tenantId,
        String name,
        String description,
        String toolType,          // builtin | interface | mcp
        String endpointUrl,
        String httpMethod,        // GET | POST | PUT | DELETE
        String requestSchemaJson, // 参数 JSON Schema（给模型）
        String responseDesc,
        String authType,          // none | api_key | basic
        String authConfigEncrypted, // 加密后的鉴权配置（SecretCipher 加密）
        String maskFieldsJson,    // 脱敏字段列表（JSON 数组）
        String status,            // READY | DISABLED | DELETED
        Long createdBy,
        String createdTime
) {
    public static ToolDefinition forInsert(
            Long tenantId, String name, String description, String toolType,
            String endpointUrl, String httpMethod, String requestSchemaJson, String responseDesc,
            String authType, String authConfigEncrypted, String maskFieldsJson,
            String status, Long createdBy
    ) {
        return new ToolDefinition(
                null, tenantId, name, description, toolType, endpointUrl, httpMethod,
                requestSchemaJson, responseDesc, authType, authConfigEncrypted, maskFieldsJson,
                status, createdBy, null
        );
    }
}
