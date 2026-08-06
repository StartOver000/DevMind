package com.devmind.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private AgentTool tool(String name, String result) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn("测试工具 " + name);
        when(tool.parametersJsonSchema()).thenReturn("{}");
        when(tool.execute(anyString(), any())).thenReturn(result);
        return tool;
    }

    @Test
    void collectsBuiltinToolsAtConstruction() {
        AgentTool kb = tool("kb_search", "ok");
        ToolRegistry registry = new ToolRegistry(List.of(kb));

        assertThat(registry.has("kb_search")).isTrue();
        assertThat(registry.all()).hasSize(1);
        assertThat(registry.execute("kb_search", "{}", 1L)).isEqualTo("ok");
    }

    @Test
    void registersDynamicToolAfterConstruction() {
        ToolRegistry registry = new ToolRegistry(List.of());
        AgentTool customer = tool("customer_query", "{\"clients\":[1,2]}");

        registry.register(customer);

        assertThat(registry.has("customer_query")).isTrue();
        assertThat(registry.all()).hasSize(1);
        assertThat(registry.execute("customer_query", "{\"days\":1}", 1L)).isEqualTo("{\"clients\":[1,2]}");
    }

    @Test
    void dynamicAndBuiltinCoexist() {
        AgentTool kb = tool("kb_search", "kb");
        AgentTool customer = tool("customer_query", "crm");
        ToolRegistry registry = new ToolRegistry(List.of(kb));
        registry.register(customer);

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.execute("kb_search", "{}", 1L)).isEqualTo("kb");
        assertThat(registry.execute("customer_query", "{}", 1L)).isEqualTo("crm");
    }

    @Test
    void unregisterRemovesDynamicTool() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.register(tool("customer_query", "crm"));
        assertThat(registry.has("customer_query")).isTrue();

        registry.unregister("customer_query");

        assertThat(registry.has("customer_query")).isFalse();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void registerOverwritesSameName() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.register(tool("customer_query", "old"));
        registry.register(tool("customer_query", "new"));

        assertThat(registry.execute("customer_query", "{}", 1L)).isEqualTo("new");
        assertThat(registry.all()).hasSize(1);
    }
}
