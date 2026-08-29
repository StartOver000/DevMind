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
        if (whitelist.contains("workflow_run")) {
            callbacks.add(FunctionToolCallback
                    .builder("workflow_run", (DevMindMcpTools.WorkflowRunInput input) -> tools.workflowRun(input))
                    .description("执行 DevMind 中一个已启用的工作流（写操作）：走引擎选择/授权/审计/配额治理链路，"
                            + "返回 runId 与执行状态")
                    .inputType(DevMindMcpTools.WorkflowRunInput.class)
                    .build());
        }
        if (whitelist.contains("workflow_generate")) {
            callbacks.add(FunctionToolCallback
                    .builder("workflow_generate", (DevMindMcpTools.WorkflowGenerateInput input) -> tools.workflowGenerate(input))
                    .description("用自然语言生成工作流草稿（只生成不执行）：返回 stepsJson 步骤结构，"
                            + "供确认后再创建为正式工作流")
                    .inputType(DevMindMcpTools.WorkflowGenerateInput.class)
                    .build());
        }
        if (whitelist.contains("skill_invoke")) {
            callbacks.add(FunctionToolCallback
                    .builder("skill_invoke", (DevMindMcpTools.SkillInvokeInput input) -> tools.skillInvoke(input))
                    .description("执行一个技能关联的工作流（技能 references 中 type=workflow 的资源）："
                            + "按声明注入接口，稳定可复现")
                    .inputType(DevMindMcpTools.SkillInvokeInput.class)
                    .build());
        }
        List<ToolCallback> exposed = List.copyOf(callbacks);
        return () -> exposed.toArray(ToolCallback[]::new);
    }
}
