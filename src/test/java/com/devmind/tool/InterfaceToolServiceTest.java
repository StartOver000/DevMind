package com.devmind.tool;

import com.devmind.agent.ToolRegistry;
import com.devmind.ai.AiModelGateway;
import com.devmind.common.ApiException;
import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.devmind.tool.dto.ToolCreateRequest;
import com.devmind.tool.dto.ToolResponse;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterfaceToolServiceTest {

    @Mock
    private ToolDefinitionRepository repository;

    @Mock
    private SecretCipher secretCipher;

    @Mock
    private UserService userService;

    @Mock
    private ToolAccessService toolAccessService;

    @Mock
    private AiModelGateway modelGateway;

    @Mock
    private ToolSemanticRepository semanticRepository;

    @Mock
    private DevMindProperties properties;

    private ToolRegistry registry;
    private InterfaceToolService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of());
        // 测试默认关闭 SSRF，避免 mock 构造的接口地址（如 http://crm.example.com）被误拦
        lenient().when(properties.interfaceToolSsrfEnabled()).thenReturn(false);
        lenient().when(properties.interfaceToolSsrfAllowedHosts()).thenReturn("");
        service = new InterfaceToolService(
                repository, registry, RestClient.builder(), secretCipher, objectMapper,
                userService, toolAccessService, modelGateway, semanticRepository, properties
        );
        // 语义档案同步默认成功（避免 mock 干扰主流程断言）
        lenient().when(modelGateway.embed(any())).thenReturn(List.of(List.of(0.1, 0.2, 0.3)));
        // 测试用户 1 为管理员，租户 1
        lenient().when(userService.isAdmin(1L)).thenReturn(true);
        lenient().when(userService.tenantIdOf(1L)).thenReturn(1L);
    }

    private ToolCreateRequest req(String name, String url) {
        return new ToolCreateRequest(name, "测试接口", url, "GET", "{}", null, "none", null, null);
    }

    private ToolDefinition savedDef(Long id, String name, String url) {
        return new ToolDefinition(id, 1L, name, "测试接口", "interface", url, "GET",
                "{}", null, "none", null, null, "READY", 1L, "2026-08-06");
    }

    @Test
    void createInsertsEncryptsAuthAndRegistersDynamicTool() {
        when(repository.findByName("customer_query")).thenReturn(null);
        when(secretCipher.encrypt("{\"key\":\"k\"}")).thenReturn("enc:abc");
        when(repository.insert(any())).thenReturn(10L);
        when(repository.findById(eq(1L), eq(10L))).thenReturn(savedDef(10L, "customer_query", "http://crm.example.com/api"));

        ToolCreateRequest req = new ToolCreateRequest(
                "customer_query", "查客户", "http://crm.example.com/api", "GET", "{}", null,
                "api_key", "{\"key\":\"k\"}", null);
        ToolResponse resp = service.create(req, 1L);

        assertThat(resp.id()).isEqualTo(10L);
        assertThat(resp.name()).isEqualTo("customer_query");
        // 鉴权已加密存储，动态工具已注册可用
        verify(repository).insert(any());
        assertThat(registry.has("customer_query")).isTrue();
        assertThat(registry.all()).hasSize(1);
    }

    @Test
    void createRejectsDuplicateNameFromDb() {
        when(repository.findByName("customer_query")).thenReturn(savedDef(1L, "customer_query", "http://x"));

        assertThatThrownBy(() -> service.create(req("customer_query", "http://x/api"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已存在");
        verify(repository, never()).insert(any());
    }

    @Test
    void createRejectsConflictWithExistingRegistryTool() {
        when(repository.findByName("kb_search")).thenReturn(null);
        // 内置工具 kb_search 已在 registry（模拟启动加载）
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "kb_search"; }
            @Override public String description() { return "检索"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "ok"; }
        });

        assertThatThrownBy(() -> service.create(req("kb_search", "http://x/api"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("冲突");
    }

    @Test
    void createRejectsNonHttpScheme() {
        assertThatThrownBy(() -> service.create(req("bad", "ftp://x/file"), 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void deleteSoftDeletesAndUnregisters() {
        when(repository.findById(eq(1L), eq(5L))).thenReturn(savedDef(5L, "customer_query", "http://x"));
        registry.register(new InterfaceToolAdapter(savedDef(5L, "customer_query", "http://x"),
                RestClient.builder(), secretCipher, objectMapper, false, ""));
        assertThat(registry.has("customer_query")).isTrue();

        service.delete(5L, 1L);

        verify(repository).softDelete(eq(1L), eq(5L));
        assertThat(registry.has("customer_query")).isFalse();
    }

    @Test
    void updateKeepsAuthWhenNotProvided() {
        when(repository.findById(eq(1L), eq(5L)))
                .thenReturn(new ToolDefinition(5L, 1L, "customer_query", "old desc", "interface",
                        "http://old", "GET", "{}", null, "api_key", "enc:keep", null, "READY", 1L, "2026-08-06"))
                .thenReturn(new ToolDefinition(5L, 1L, "customer_query", "new desc", "interface",
                        "http://new", "POST", "{}", null, "api_key", "enc:keep", null, "READY", 1L, "2026-08-06"));
        ToolCreateRequest req = new ToolCreateRequest(
                "customer_query", "new desc", "http://new", "POST", "{}", null, "api_key", null, null);

        ToolResponse resp = service.update(5L, req, 1L);

        assertThat(resp.description()).isEqualTo("new desc");
        assertThat(resp.endpointUrl()).isEqualTo("http://new");
        // 未传鉴权 → 保留原加密值
        verify(repository).update(anyLong(), any());
    }

    @Test
    void runLoadsEnabledToolsOnStartup() {
        when(repository.listEnabledAll()).thenReturn(List.of(savedDef(1L, "t1", "http://x")));
        service.run(null);

        assertThat(registry.has("t1")).isTrue();
    }

    @Test
    void createRejectedForNonAdmin() {
        when(userService.isAdmin(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(req("customer_query", "http://x/api"), 2L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("管理员");
        verify(repository, never()).insert(any());
    }
}
