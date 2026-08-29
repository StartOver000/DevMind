package com.devmind.mcp;

import com.devmind.config.McpServerProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 二期·双向互联（docs/architecture/05）：MCP Server 装配。
 *
 * <p>总开关 {@code devmind.mcp-server.enabled}（默认 false）：关闭时本配置整体不装配，
 * Spring AI 的 MCP server autoconfiguration 也因 {@code spring.ai.mcp.server.enabled=false}
 * 而退避——应用行为与一期完全一致。</p>
 *
 * <p>工具白名单 {@code devmind.mcp-server.tools}（逗号分隔）在<b>注册期</b>过滤：
 * 未列入白名单的工具不会出现在 MCP tools 列表里（对客户端不可见，而非可见但拒绝调用）。
 * `sql_diagnose` 等有注入面的能力在 allowlist 完成前不进入本配置（见设计文档 §2）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "devmind.mcp-server.enabled", havingValue = "true")
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider devmindMcpToolCallbacks(DevMindMcpTools tools, McpServerProperties properties) {
        Set<String> whitelist = Arrays.stream(properties.tools().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        List<ToolCallback> callbacks = new ArrayList<>();
        if (whitelist.contains("kb_search")) {
            callbacks.add(FunctionToolCallback
                    .builder("kb_search", (DevMindMcpTools.KbSearchInput input) -> tools.kbSearch(input))
                    .description("DevMind 知识库纯语义检索：问题向量化 + 向量/关键词混合检索，不调 LLM。"
                            + "先检索确认命中再决定是否问答，可省模型成本")
                    .inputType(DevMindMcpTools.KbSearchInput.class)
                    .build());
        }
        if (whitelist.contains("kb_chat")) {
            callbacks.add(FunctionToolCallback
                    .builder("kb_chat", (DevMindMcpTools.KbChatInput input) -> tools.kbChat(input))
                    .description("DevMind 知识库 RAG 问答：检索增强回答，返回答案与引用来源；"
                            + "模型用量计入 DevMind 服务账号")
                    .inputType(DevMindMcpTools.KbChatInput.class)
                    .build());
        }
        List<ToolCallback> exposed = List.copyOf(callbacks);
        return () -> exposed.toArray(ToolCallback[]::new);
    }
}
