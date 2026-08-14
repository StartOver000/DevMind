package com.devmind.mcp;

import com.devmind.agent.ToolRegistry;
import com.devmind.config.McpSecurityProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * MCP 服务器接入：连接外部 MCP 服务器，把其 tools 包装注册进 {@link ToolRegistry}，
 * 使 Agent / 工作流可以直接调用 MCP 工具（接入层核心能力）。
 * - 启动时自动加载所有 ENABLED 的 MCP 服务器；
 * - connect/disconnect 可运行时操作（管理员 API 触发）；
 * - 支持 stdio（本地命令拉起）与 http（远程 SSE）两种传输；
 * - stdio 命令治理（P3-1）：命令白名单 + 参数 shell 元字符校验（登记与执行双层）。
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@SuppressWarnings("null")
public class McpToolService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);

    /** 参数中的 shell 元字符（防拼接注入）；保留普通空格以兼容多段参数 */
    private static final Pattern SHELL_METACHARS = Pattern.compile("[;&|<>`$(){}\\r\\n]");

    /** 参数长度上限（防止超长参数/二进制载荷） */
    private static final int MAX_ARG_LENGTH = 256;

    /** 已连接的 MCP 服务器：serverId -> 连接信息 */
    private final Map<Long, McpConnection> connections = new ConcurrentHashMap<>();

    private final McpServerRepository repository;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final McpSecurityProperties securityProperties;

    public McpToolService(
            McpServerRepository repository,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            McpSecurityProperties securityProperties
    ) {
        this.repository = repository;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    public record McpConnection(McpServerDefinition def, McpSyncClient client, List<McpAgentTool> tools) {
    }

    @Override
    public void run(ApplicationArguments args) {
        // 当前单租户：加载租户 1 的启用服务器
        for (McpServerDefinition def : repository.listEnabled(1L)) {
            try {
                int count = connect(def);
                log.info("启动加载 MCP 服务器 {} 成功，注册 {} 个工具", def.name(), count);
            } catch (Exception ex) {
                log.warn("启动加载 MCP 服务器 {} 失败: {}", def.name(), ex.getMessage());
            }
        }
    }

    /** 连接 MCP 服务器并注册其工具，返回注册的工具数 */
    public int connect(McpServerDefinition def) {
        McpSyncClient client = createClient(def);
        client.initialize();
        List<McpSchema.Tool> mcpTools = client.listTools().tools();
        List<McpAgentTool> tools = new ArrayList<>();
        for (McpSchema.Tool tool : mcpTools) {
            McpAgentTool agentTool = new McpAgentTool(() -> connections.get(def.id()) == null ? null : connections.get(def.id()).client(),
                    def.name(), tool, objectMapper);
            toolRegistry.register(agentTool);
            tools.add(agentTool);
        }
        connections.put(def.id(), new McpConnection(def, client, tools));
        return tools.size();
    }

    /** 断开 MCP 服务器并卸载其工具 */
    public void disconnect(Long serverId) {
        McpConnection conn = connections.remove(serverId);
        if (conn == null) {
            return;
        }
        for (McpAgentTool tool : conn.tools()) {
            toolRegistry.unregister(tool.name());
        }
        try {
            conn.client().close();
        } catch (Exception ex) {
            log.warn("关闭 MCP 客户端失败 (server={}): {}", conn.def().name(), ex.getMessage());
        }
    }

    public boolean isConnected(Long serverId) {
        return connections.containsKey(serverId);
    }

    public int loadedToolCount(Long serverId) {
        McpConnection conn = connections.get(serverId);
        return conn == null ? 0 : conn.tools().size();
    }

    /**
     * MCP 命令治理（P3-1）：stdio 命令白名单 + 参数安全校验。
     * 登记入口（McpServerController.create）与执行入口（createClient）统一调用。
     */
    public void validateCommand(McpServerDefinition def) {
        if (def.command() == null || def.command().isBlank()) {
            throw new IllegalArgumentException("stdio 传输需要 command");
        }
        String cmd = def.command().trim().toLowerCase();
        if (!securityProperties.allowedCommandSet().contains(cmd)) {
            throw new IllegalArgumentException("命令 '" + def.command()
                    + "' 不在允许列表（" + securityProperties.allowedCommands() + "），禁止启动");
        }
        for (String arg : parseArgs(def.argsJson())) {
            if (arg == null || arg.isBlank()) {
                throw new IllegalArgumentException("MCP 参数不能为空");
            }
            if (arg.length() > MAX_ARG_LENGTH) {
                throw new IllegalArgumentException("MCP 参数过长（>" + MAX_ARG_LENGTH + " 字符）");
            }
            if (SHELL_METACHARS.matcher(arg).find()) {
                throw new IllegalArgumentException("MCP 参数包含不允许的 shell 元字符");
            }
        }
    }

    /** 创建 MCP 客户端（protected 供测试注入 mock） */
    protected McpSyncClient createClient(McpServerDefinition def) {
        McpClientTransport transport;
        if ("http".equals(def.transportType())) {
            // MCP SDK 0.10 仅提供已废弃的 SSE 传输；HTTP/streamable 待 SDK 升级后支持
            throw new IllegalArgumentException("HTTP 传输暂不支持，请使用 stdio（本地命令）方式接入 MCP 服务器");
        } else {
            if (def.command() == null || def.command().isBlank()) {
                throw new IllegalArgumentException("stdio 传输需要 command");
            }
            // 执行入口再校验一次（纵深防御：绕过登记直接调用 connect 也会被拦截）
            validateCommand(def);
            String command = def.command();
            List<String> args = new ArrayList<>(parseArgs(def.argsJson()));
            // Windows 上 npx/python 等实际是 .cmd/.bat 批处理，ProcessBuilder 无法直接启动，
            // 统一经 cmd /c 包装（对用户透明）
            if (System.getProperty("os.name", "").toLowerCase().contains("win")
                    && !command.toLowerCase().endsWith(".exe")) {
                command = "cmd";
                args = new ArrayList<>(List.of("/c", def.command()));
                args.addAll(parseArgs(def.argsJson()));
            }
            ServerParameters.Builder builder = ServerParameters.builder(command);
            if (!args.isEmpty()) {
                builder.args(args);
            }
            transport = new StdioClientTransport(builder.build());
        }
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .build();
    }

    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            log.warn("MCP args 解析失败，忽略: {}", ex.getMessage());
            return List.of();
        }
    }
}
