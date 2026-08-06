package com.devmind.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class McpAgentToolTest {

    @Mock
    private McpSyncClient client;

    private McpAgentTool tool;

    @BeforeEach
    void setUp() {
        McpSchema.Tool mcpTool = new McpSchema.Tool("echo", "Echo a message", "{}");
        tool = new McpAgentTool(() -> client, "demo", mcpTool, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void executeReturnsTextContent() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("hello world")), false);
        when(client.callTool(any())).thenReturn(result);

        String output = tool.execute("{\"message\":\"hi\"}", 1L);

        assertThat(tool.name()).isEqualTo("mcp_demo_echo");
        assertThat(output).isEqualTo("hello world");
    }

    @Test
    void executeReturnsErrorWhenMcpReportsError() {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("boom")), true);
        when(client.callTool(any())).thenReturn(result);

        String output = tool.execute("{}", 1L);

        assertThat(output).startsWith("{\"error\"");
    }

    @Test
    void executeReturnsErrorWhenClientGone() {
        McpAgentTool orphan = new McpAgentTool(() -> null, "demo",
                new McpSchema.Tool("echo", "d", "{}"), new com.fasterxml.jackson.databind.ObjectMapper());

        String output = orphan.execute("{}", 1L);

        assertThat(output).startsWith("{\"error\"");
    }
}
