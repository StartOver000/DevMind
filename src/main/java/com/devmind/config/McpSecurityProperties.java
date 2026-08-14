package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MCP 接入安全配置（P3-1 命令治理）。
 * 登记/执行 stdio MCP Server 时，命令必须在允许列表内，参数做 shell 元字符校验，
 * 防止管理员配置或未来非管理员入口拉起任意命令。
 */
@ConfigurationProperties(prefix = "devmind.mcp")
public record McpSecurityProperties(
        @DefaultValue("npx,node,python,python3,uvx,docker") String allowedCommands
) {
    /** 允许命令集合（小写、去空白） */
    public Set<String> allowedCommandSet() {
        Set<String> set = new LinkedHashSet<>();
        for (String s : allowedCommands.split(",")) {
            if (!s.isBlank()) {
                set.add(s.trim().toLowerCase());
            }
        }
        return set;
    }
}
