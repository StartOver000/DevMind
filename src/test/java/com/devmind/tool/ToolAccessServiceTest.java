package com.devmind.tool;

import com.devmind.agent.AgentTool;
import com.devmind.agent.ToolRegistry;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolAccessServiceTest {

    @Mock
    private ToolDefinitionRepository toolRepository;

    @Mock
    private ToolGrantRepository grantRepository;

    @Mock
    private UserService userService;

    private ToolRegistry registry;
    private ToolAccessService service;

    @BeforeEach
    void setUp() {
        // 内置工具：kb_search / ai_generate
        registry = new ToolRegistry(List.of(
                builtin("kb_search"),
                builtin("ai_generate")
        ));
        service = new ToolAccessService(toolRepository, grantRepository, registry, userService);
        // 租户 1 下登记了动态接口工具：crm_query / erp_query
        lenient().when(toolRepository.listEnabled(1L)).thenReturn(List.of(
                dynamic(10L, "crm_query"),
                dynamic(11L, "erp_query")
        ));
    }

    private AgentTool builtin(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "内置 " + name; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        };
    }

    private ToolDefinition dynamic(Long id, String name) {
        return new ToolDefinition(id, 1L, name, "动态接口", "interface",
                "http://x", "GET", "{}", null, "none", null, null, "READY", 1L, "2026-08-06");
    }

    @Test
    void adminSeesAllBuiltinAndDynamicTools() {
        when(userService.isAdmin(1L)).thenReturn(true);

        Set<String> names = service.accessibleToolNames(1L, 1L);

        assertThat(names).containsExactlyInAnyOrder("kb_search", "ai_generate", "crm_query", "erp_query");
    }

    @Test
    void memberSeesBuiltinPlusGrantedDynamicTools() {
        when(userService.isAdmin(2L)).thenReturn(false);
        when(grantRepository.findToolIdsForUser(1L, 2L)).thenReturn(Set.of(10L));

        Set<String> names = service.accessibleToolNames(1L, 2L);

        // 只拿到被授权的 crm_query，未授权 erp_query 不可见
        assertThat(names).containsExactlyInAnyOrder("kb_search", "ai_generate", "crm_query");
    }

    @Test
    void memberWithoutGrantSeesOnlyBuiltinTools() {
        when(userService.isAdmin(2L)).thenReturn(false);
        when(grantRepository.findToolIdsForUser(1L, 2L)).thenReturn(Set.of());

        Set<String> names = service.accessibleToolNames(1L, 2L);

        assertThat(names).containsExactlyInAnyOrder("kb_search", "ai_generate");
    }

    @Test
    void canUseChecksMembership() {
        when(userService.isAdmin(2L)).thenReturn(false);
        when(grantRepository.findToolIdsForUser(1L, 2L)).thenReturn(Set.of(10L));

        assertThat(service.canUse(1L, 2L, "kb_search")).isTrue();
        assertThat(service.canUse(1L, 2L, "crm_query")).isTrue();
        assertThat(service.canUse(1L, 2L, "erp_query")).isFalse();
    }

    @Test
    void accessibleDynamicToolsFiltersByGrantForMember() {
        when(userService.isAdmin(2L)).thenReturn(false);
        when(grantRepository.findToolIdsForUser(1L, 2L)).thenReturn(Set.of(10L));

        List<ToolDefinition> tools = service.accessibleDynamicTools(1L, 2L);

        assertThat(tools).extracting(ToolDefinition::name).containsExactly("crm_query");
    }
}
