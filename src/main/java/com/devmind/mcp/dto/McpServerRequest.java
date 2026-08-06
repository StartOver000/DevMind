package com.devmind.mcp.dto;

import java.util.List;

/** 登记 MCP 服务器请求 */
public record McpServerRequest(
        String name,
        String transportType,    // stdio | http
        String command,          // stdio: 本地命令，如 npx
        List<String> args,       // stdio: 命令参数
        String url               // http: 远程地址
) {
}
