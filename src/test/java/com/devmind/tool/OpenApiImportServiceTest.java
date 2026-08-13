package com.devmind.tool;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.tool.dto.ToolCreateRequest;
import com.devmind.tool.dto.ToolResponse;
import com.devmind.tool.openapi.OpenApiOperation;
import com.devmind.tool.openapi.OpenApiParser;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiImportServiceTest {

    private OpenApiParser parser;
    private InterfaceToolService toolService;
    private ToolSemanticRepository semanticRepository;
    private ToolDefinitionRepository toolDefinitionRepository;
    private AiModelGateway modelGateway;
    private ChatRouter chatRouter;
    private UserService userService;
    private OpenApiImportService service;

    private OpenApiOperation op(String method, String path, String operationId, String summary) {
        return new OpenApiOperation(method, path, operationId, summary, summary + " 详细描述",
                List.of("测试"), List.of(), null);
    }

    @BeforeEach
    void setUp() {
        parser = mock(OpenApiParser.class);
        toolService = mock(InterfaceToolService.class);
        semanticRepository = mock(ToolSemanticRepository.class);
        toolDefinitionRepository = mock(ToolDefinitionRepository.class);
        modelGateway = mock(AiModelGateway.class);
        chatRouter = mock(ChatRouter.class);
        userService = mock(UserService.class);
        service = new OpenApiImportService(parser, toolService, semanticRepository,
                toolDefinitionRepository, modelGateway, chatRouter, userService);
        when(userService.tenantIdOf(1L)).thenReturn(1L);
    }

    @Test
    void nonAdminCannotImport() {
        when(userService.isAdmin(2L)).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.importOpenApi(file, 2L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("管理员");
    }

    @Test
    void importsOperationsAndVectorizes() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(parser.parse(anyString(), anyString())).thenReturn(new OpenApiParser.ParsedDocument(
                "订单服务", "https://api.example.com",
                List.of(op("GET", "/orders", "listOrders", "查询订单列表"),
                        op("POST", "/orders", "createOrder", "创建订单")),
                List.of()));
        when(toolService.create(any(), anyLong())).thenAnswer(inv -> {
            ToolCreateRequest req = inv.getArgument(0);
            return new ToolResponse(1L, req.name(), req.description(), "interface",
                    req.endpointUrl(), req.httpMethod(), req.requestSchemaJson(), null,
                    "none", null, "READY", 1L);
        });
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(0.1, 0.2), List.of(0.3, 0.4)));

        MockMultipartFile file = new MockMultipartFile("file", "orders.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        OpenApiImportService.ImportResult result = service.importOpenApi(file, 1L);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        verify(toolService, org.mockito.Mockito.times(2)).create(any(), anyLong());
        // 工具名由 operationId 规范化而来
        verify(toolService).create(org.mockito.ArgumentMatchers.argThat(
                r -> r.name().equals("listOrders") && r.endpointUrl().equals("https://api.example.com/orders")), anyLong());
        // 语义档案向量化入库
        verify(semanticRepository, org.mockito.Mockito.times(2)).upsert(anyLong(), anyLong(), anyString(), anyList());
    }

    @Test
    void duplicateNameIsSkippedIdempotently() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(parser.parse(anyString(), anyString())).thenReturn(new OpenApiParser.ParsedDocument(
                "重复文档", "", List.of(op("GET", "/a", "getA", "A")), List.of()));
        when(toolService.create(any(), anyLong())).thenThrow(new ApiException(null, "工具名已存在: getA"));

        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        OpenApiImportService.ImportResult result = service.importOpenApi(file, 1L);

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(semanticRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyList());
    }

    @Test
    void duplicateImportRefreshesSemanticArchive() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(parser.parse(anyString(), anyString())).thenReturn(new OpenApiParser.ParsedDocument(
                "重复文档", "https://x", List.of(op("GET", "/a", "getA", "查询A")), List.of()));
        when(toolService.create(any(), anyLong())).thenThrow(new ApiException(null, "工具名已存在: getA"));
        // 已存在工具可查 → 刷新其语义档案（接口描述可能已更新）
        ToolDefinition existing = new ToolDefinition(7L, 1L, "getA", "查询A", "interface",
                "http://x/a", "GET", null, null, "none", null, null, "READY", 1L, null);
        when(toolDefinitionRepository.findByName("getA")).thenReturn(existing);
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(0.1, 0.2)));

        MockMultipartFile file = new MockMultipartFile("file", "a.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        OpenApiImportService.ImportResult result = service.importOpenApi(file, 1L);

        assertThat(result.skipped()).isEqualTo(1);
        // 已存在接口（id=7）的语义档案被刷新
        verify(semanticRepository).upsert(eq(1L), eq(7L), anyString(), anyList());
    }

    @Test
    void embeddingFailureDegradesButKeepsTools() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(parser.parse(anyString(), anyString())).thenReturn(new OpenApiParser.ParsedDocument(
                "订单服务", "", List.of(op("GET", "/orders", "listOrders", "查询订单列表")), List.of()));
        when(toolService.create(any(), anyLong())).thenAnswer(inv -> {
            ToolCreateRequest req = inv.getArgument(0);
            return new ToolResponse(1L, req.name(), req.description(), "interface",
                    req.endpointUrl(), req.httpMethod(), req.requestSchemaJson(), null,
                    "none", null, "READY", 1L);
        });
        when(modelGateway.embed(anyList())).thenThrow(new RuntimeException("embedding 服务不可用"));

        MockMultipartFile file = new MockMultipartFile("file", "orders.json", "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        OpenApiImportService.ImportResult result = service.importOpenApi(file, 1L);

        // 接口仍登记成功，只是无语义向量
        assertThat(result.created()).isEqualTo(1);
        verify(semanticRepository, never()).upsert(anyLong(), anyLong(), anyString(), anyList());
    }

    @Test
    void semanticSearchUsesVectorWhenAvailable() {
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(0.9, 0.1)));
        when(semanticRepository.semanticSearch(anyLong(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(new ToolSemanticRepository.SemanticHit(
                        1L, "listOrders", "查询订单列表", "https://api.example.com/orders", "GET", 0.91)));

        List<ToolSemanticRepository.SemanticHit> hits = service.semanticSearch("帮我查一下订单列表", 1L, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("listOrders");
        assertThat(hits.get(0).score()).isEqualTo(0.91);
        verify(semanticRepository).semanticSearch(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(5), anyDouble());
    }

    @Test
    void semanticSearchFallsBackToKeyword() {
        when(modelGateway.embed(anyList())).thenThrow(new RuntimeException("embedding 超时"));
        when(semanticRepository.keywordSearch(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of(new ToolSemanticRepository.SemanticHit(
                        2L, "createOrder", "创建订单", "/orders", "POST", 1.0)));

        List<ToolSemanticRepository.SemanticHit> hits = service.semanticSearch("创建订单", 1L, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("createOrder");
        verify(semanticRepository).keywordSearch(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("创建订单"), org.mockito.ArgumentMatchers.eq(5));
    }

    @Test
    void blankQueryReturnsEmpty() {
        assertThat(service.semanticSearch("   ", 1L, 5)).isEmpty();
    }

    @Test
    void enhanceSemanticRequiresAdminAndUpserts() {
        when(userService.isAdmin(1L)).thenReturn(true);
        when(toolService.get(1L, 1L)).thenReturn(new ToolResponse(1L, "listOrders", "查询订单列表",
                "interface", "https://api.example.com/orders", "GET", null, null, "none", null, "READY", 1L));
        when(semanticRepository.findSemanticText(1L)).thenReturn("GET /orders — 查询订单列表");
        when(chatRouter.chat(anyString(), anyString())).thenReturn(
                new AiModelGateway.ChatResult("查询订单列表，用于订单管理页面展示全部订单。\n分页浏览订单时使用。\n订单,列表,查询,分页", "mock", 0, 0));
        when(modelGateway.embed(anyList())).thenReturn(List.of(List.of(0.5, 0.5)));

        OpenApiImportService.EnhanceResult result = service.enhanceSemantic(1L, 1L);

        assertThat(result.name()).isEqualTo("listOrders");
        assertThat(result.semanticText()).contains("【AI 增强】");
        verify(semanticRepository).upsert(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(result.semanticText()), anyList());
    }
}
