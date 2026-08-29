package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 二期·双向互联（docs/architecture/05 §3）：n8n 工作流引擎配置。
 *
 * <p>n8n 通过 MCP Server Trigger 接入：DevMind 作为 MCP 客户端连接 n8n 的 SSE 端点，
 * 把 n8n 工作流作为外部步骤调用。mcpUrl 为 n8n MCP Server 的 SSE 端点地址
 *（如 http://localhost:5678/mcp-sse）。</p>
 */
@ConfigurationProperties(prefix = "devmind.n8n")
public record N8nProperties(
        @DefaultValue("") String mcpUrl,
        @DefaultValue("30000") long timeoutMs
) {
    public boolean enabled() {
        return mcpUrl != null && !mcpUrl.isBlank();
    }
}