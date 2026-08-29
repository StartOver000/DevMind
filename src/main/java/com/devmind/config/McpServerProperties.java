package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 二期·双向互联（docs/architecture/05）：MCP Server 配置。
 *
 * <p>enabled 总开关与 {@code spring.ai.mcp.server.enabled} 绑定同一环境变量
 * {@code DEVMIND_MCP_SERVER_ENABLED}（默认关）；tools 为暴露白名单（逗号分隔，
 * 注册期过滤——未列入的工具对客户端不可见）；userId 为服务账号（审计/访问校验归属）。</p>
 */
@ConfigurationProperties(prefix = "devmind.mcp-server")
public record McpServerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("kb_search,kb_chat") String tools,
        @DefaultValue("1") long userId
) {
}
