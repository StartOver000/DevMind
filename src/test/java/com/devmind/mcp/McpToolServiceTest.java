package com.devmind.mcp;

import com.devmind.agent.ToolRegistry;
import com.devmind.config.McpSecurityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class McpToolServiceTest {

    @Mock
    private McpServerRepository repository;

    @Mock
    private McpSyncClient client;

    private ToolRegistry registry;
    private McpToolService service;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of());
        service = new McpToolService(repository, registry, new ObjectMapper(),
                new McpSecurityProperties("npx,node,python,uvx")) {
            @Override
            protected McpSyncClient createClient(McpServerDefinition def) {
                return client;
            }
        };
    }

    private McpServerDefinition stdioDef(Long id, String name) {
        return new McpServerDefinition(id, 1L, name, "stdio", "npx",
                "[\"-y\",\"@modelcontextprotocol/server-everything\"]", null, "ENABLED", 1L, null);
    }

    @Test
    void connectRegistersMcpToolsIntoRegistry() {
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                new McpSchema.Tool("echo", "Echo a message", "{}"),
                new McpSchema.Tool("get_weather", "Get weather", "{}")
        ), null));

        int count = service.connect(stdioDef(1L, "demo"));

        assertThat(count).isEqualTo(2);
        assertThat(registry.has("mcp_demo_echo")).isTrue();
        assertThat(registry.has("mcp_demo_get_weather")).isTrue();
        assertThat(service.isConnected(1L)).isTrue();
        assertThat(service.loadedToolCount(1L)).isEqualTo(2);
        verify(client).initialize();
    }

    @Test
    void disconnectUnregistersAndCloses() {
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                new McpSchema.Tool("echo", "Echo", "{}")
        ), null));
        service.connect(stdioDef(1L, "demo"));
        assertThat(registry.has("mcp_demo_echo")).isTrue();

        service.disconnect(1L);

        assertThat(registry.has("mcp_demo_echo")).isFalse();
        assertThat(service.isConnected(1L)).isFalse();
        verify(client).close();
    }

    // ---- P3-1 命令治理：白名单 + 参数校验 ----

    @Test
    void validateCommandAllowsWhitelistedCommandWithPlainArgs() {
        // npx 在白名单，args 为包名/flag，无元字符
        service.validateCommand(stdioDef(1L, "demo"));
    }

    @Test
    void validateCommandRejectsCommandOutsideWhitelist() {
        McpServerDefinition def = new McpServerDefinition(1L, 1L, "bad", "stdio", "bash",
                "[\"-c\",\"rm -rf /\"]", null, "ENABLED", 1L, null);

        assertThatThrownBy(() -> service.validateCommand(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("允许列表");
    }

    @Test
    void validateCommandRejectsShellMetacharactersInArgs() {
        McpServerDefinition def = new McpServerDefinition(1L, 1L, "bad", "stdio", "npx",
                "[\"-y\",\"pkg; rm -rf /\"]", null, "ENABLED", 1L, null);

        assertThatThrownBy(() -> service.validateCommand(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shell 元字符");
    }

    @Test
    void validateCommandRejectsOversizedArg() {
        McpServerDefinition def = new McpServerDefinition(1L, 1L, "bad", "stdio", "npx",
                "[\"" + "a".repeat(300) + "\"]", null, "ENABLED", 1L, null);

        assertThatThrownBy(() -> service.validateCommand(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过长");
    }

    @Test
    void validateCommandRejectsEmptyArg() {
        McpServerDefinition def = new McpServerDefinition(1L, 1L, "bad", "stdio", "npx",
                "[\"\"]", null, "ENABLED", 1L, null);

        assertThatThrownBy(() -> service.validateCommand(def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数不能为空");
    }
}
