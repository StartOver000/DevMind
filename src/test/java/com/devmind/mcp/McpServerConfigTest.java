package com.devmind.mcp;

import com.devmind.config.McpServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MCP Server 装配测试：总开关关闭时不装配；白名单在注册期过滤（未列入 = 对客户端不可见）。
 */
class McpServerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(McpServerConfig.class)
            .withBean(DevMindMcpTools.class, () -> mock(DevMindMcpTools.class));

    private McpServerProperties propertiesWithTools(String csv) {
        return new McpServerProperties(true, csv, 1L);
    }

    @Test
    void disabledByDefault() {
        runner.withBean(McpServerProperties.class, () -> propertiesWithTools("kb_search,kb_chat"))
                .run(context -> assertThat(context).doesNotHaveBean("devmindMcpToolCallbacks"));
    }

    @Test
    void enabledRegistersWhitelistedToolsOnly() {
        runner.withBean(McpServerProperties.class, () -> propertiesWithTools("kb_search"))
                .withPropertyValues("devmind.mcp-server.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ToolCallbackProvider.class);
                    ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
                    assertThat(callbacks).hasSize(1);
                    assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("kb_search");
                });
    }

    @Test
    void bothToolsRegisteredWhenWhitelisted() {
        runner.withBean(McpServerProperties.class, () -> propertiesWithTools("kb_search, kb_chat"))
                .withPropertyValues("devmind.mcp-server.enabled=true")
                .run(context -> {
                    ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
                    assertThat(callbacks).extracting(cb -> cb.getToolDefinition().name())
                            .containsExactlyInAnyOrder("kb_search", "kb_chat");
                });
    }

    @Test
    void emptyWhitelistExposesNothing() {
        runner.withBean(McpServerProperties.class, () -> propertiesWithTools("  "))
                .withPropertyValues("devmind.mcp-server.enabled=true")
                .run(context -> {
                    ToolCallback[] callbacks = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
                    assertThat(callbacks).isEmpty();
                });
    }
}
