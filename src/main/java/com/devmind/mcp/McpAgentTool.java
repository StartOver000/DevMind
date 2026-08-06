package com.devmind.mcp;

import com.devmind.agent.AgentTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具包装：把 MCP 服务器的 tool 包装成平台可调用的 {@link AgentTool}。
 * 命名 mcp_&lt;server&gt;_&lt;tool&gt; 避免与内置/动态工具冲突；
 * 调用时透传参数给 MCP server，返回文本内容。
 */
public class McpAgentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);

    private final McpSyncClientHolder clientHolder;
    private final String fullName;
    private final String mcpToolName;
    private final String description;
    private final String parametersJsonSchema;
    private final ObjectMapper objectMapper;

    /** 线程安全的 client 引用（断开时置空，调用报错让上层降级） */
    public interface McpSyncClientHolder {
        io.modelcontextprotocol.client.McpSyncClient client();
    }

    public McpAgentTool(
            McpSyncClientHolder clientHolder,
            String serverName,
            McpSchema.Tool tool,
            ObjectMapper objectMapper
    ) {
        this.clientHolder = clientHolder;
        this.fullName = "mcp_" + serverName + "_" + tool.name();
        this.mcpToolName = tool.name();
        this.description = tool.description() == null || tool.description().isBlank()
                ? "MCP 工具 " + tool.name() + "（来自 " + serverName + "）"
                : tool.description();
        this.parametersJsonSchema = schemaToJson(tool.inputSchema(), objectMapper);
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return fullName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String parametersJsonSchema() {
        return parametersJsonSchema;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        McpSyncClient client = clientHolder.client();
        if (client == null) {
            return "{\"error\": \"MCP 服务器未连接: " + fullName + "\"}";
        }
        try {
            Map<String, Object> args = objectMapper.readValue(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(mcpToolName, args));
            String text = result.content().stream()
                    .filter(c -> c instanceof McpSchema.TextContent)
                    .map(c -> ((McpSchema.TextContent) c).text())
                    .collect(Collectors.joining("\n"));
            if (result.isError() != null && result.isError()) {
                return "{\"error\": \"" + (text == null || text.isBlank() ? "MCP 工具调用失败" : text) + "\"}";
            }
            return text == null || text.isBlank() ? "{}" : text;
        } catch (Exception ex) {
            log.warn("MCP 工具 {} 调用失败: {}", fullName, ex.getMessage());
            return "{\"error\": \"MCP 工具调用失败: " + ex.getMessage() + "\"}";
        }
    }

    /** MCP JsonSchema → 标准 JSON Schema 字符串（供模型读取参数定义） */
    private String schemaToJson(McpSchema.JsonSchema schema, ObjectMapper objectMapper) {
        try {
            if (schema == null) {
                return "{}";
            }
            return objectMapper.writeValueAsString(schema);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
