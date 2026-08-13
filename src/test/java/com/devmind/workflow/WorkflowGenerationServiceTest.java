package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.tool.ToolAccessService;
import com.devmind.tool.ToolDefinition;
import com.devmind.tool.ToolSemanticRepository;
import com.devmind.tool.OpenApiImportService;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowGenerationServiceTest {

    @Mock
    private ChatRouter chatRouter;

    @Mock
    private UserService userService;

    @Mock
    private ToolAccessService toolAccessService;

    private ToolRegistry registry;
    private WorkflowGenerationService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(List.of());
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "prom_buildinfo"; }
            @Override public String description() { return "查询版本"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        registry.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "ai_generate"; }
            @Override public String description() { return "AI 生成文本"; }
            @Override public String parametersJsonSchema() { return "{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "ok"; }
        });
        service = new WorkflowGenerationService(
                chatRouter, registry, objectMapper, userService, toolAccessService,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class)
        );
        lenient().when(userService.tenantIdOf(1L)).thenReturn(1L);
        lenient().when(toolAccessService.accessibleToolNames(eq(1L), eq(1L)))
                .thenReturn(Set.of("prom_buildinfo", "ai_generate"));
    }

    @Test
    void generatesStepsFromDescription() {
        String json = """
                [{"tool":"prom_buildinfo","params":{},"output_var":"info","goal":"查版本"},
                 {"tool":"ai_generate","params":{"prompt":"总结：{{info}}"},"output_var":"report","goal":"生成总结"}]
                """;
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        WorkflowGenerationService.GenerationResult result = service.generate(1L, "查一下监控版本并总结");

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).tool()).isEqualTo("prom_buildinfo");
        assertThat(result.steps().get(0).outputVar()).isEqualTo("info");
        assertThat(result.steps().get(1).tool()).isEqualTo("ai_generate");
        assertThat(result.steps().get(1).paramsJson()).contains("{{info}}");
        // stepsJson 可直接用于创建工作流（tool/params/output_var 格式）
        assertThat(result.stepsJson()).startsWith("[{\"tool\":\"prom_buildinfo\"");
        assertThat(result.stepsJson()).contains("output_var");
    }

    @Test
    void toleratesMarkdownCodeBlockWrapper() {
        String json = "```json\n[{\"tool\":\"prom_buildinfo\",\"params\":{},\"output_var\":\"info\",\"goal\":\"查\"}]\n```";
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        WorkflowGenerationService.GenerationResult result = service.generate(1L, "查版本");

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).tool()).isEqualTo("prom_buildinfo");
    }

    @Test
    void rejectsUnregisteredToolFromModel() {
        String json = "[{\"tool\":\"evil_api\",\"params\":{},\"goal\":\"x\"}]";
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        assertThatThrownBy(() -> service.generate(1L, "调接口"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    void throwsWhenModelFails() {
        when(chatRouter.chat(anyString(), anyString()))
                .thenThrow(new ApiException(com.devmind.common.ErrorCode.MODEL_CALL_FAILED, "模型不可用"));

        assertThatThrownBy(() -> service.generate(1L, "生成"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> service.generate(1L, "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("描述");
    }

    @Test
    void generatesIfBranchFromDescription() {
        String json = """
                [{"tool":"prom_buildinfo","params":{},"output_var":"info","goal":"查版本"},
                 {"if":"{{info}} contains 'version'",
                  "then":[{"tool":"ai_generate","params":{"prompt":"有版本：{{info}}"}}],
                  "else":[{"tool":"ai_generate","params":{"prompt":"无版本"}}]}]
                """;
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        WorkflowGenerationService.GenerationResult result = service.generate(1L, "查版本，如果有版本信息就总结，否则提示无");

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).kind()).isEqualTo("if");
        assertThat(result.steps().get(1).condition()).isEqualTo("{{info}} contains 'version'");
        assertThat(result.steps().get(1).thenBranch()).hasSize(1);
        assertThat(result.steps().get(1).thenBranch().get(0).tool()).isEqualTo("ai_generate");
        assertThat(result.steps().get(1).elseBranch()).hasSize(1);
        // stepsJson 保留 if/then/else 结构，可直接用于创建工作流
        assertThat(result.stepsJson()).contains("\"if\":");
        assertThat(result.stepsJson()).contains("\"then\":");
        assertThat(result.stepsJson()).contains("\"else\":");
        // 序列化后的 JSON 可被工作流创建校验接受（工具合法）
        assertThat(result.stepsJson()).contains("prom_buildinfo");
    }

    @Test
    void generatesParallelFromDescription() {
        String json = """
                [{"parallel":[
                   {"tool":"prom_buildinfo","params":{},"output_var":"info","goal":"查版本"},
                   {"tool":"ai_generate","params":{"prompt":"独立生成"}}]}]
                """;
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        WorkflowGenerationService.GenerationResult result = service.generate(1L, "同时查版本并生成一段文案");

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).kind()).isEqualTo("parallel");
        assertThat(result.steps().get(0).parallelSteps()).hasSize(2);
        assertThat(result.stepsJson()).contains("\"parallel\":");
        assertThat(result.stepsJson()).contains("prom_buildinfo");
    }

    @Test
    void ifBranchValidatesToolsInsideBranches() {
        // then 分支里用了未登记工具 → 拒绝
        String json = """
                [{"tool":"prom_buildinfo","params":{},"output_var":"info"},
                 {"if":"{{info}} != ''","then":[{"tool":"evil_api","params":{}}]}]
                """;
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        assertThatThrownBy(() -> service.generate(1L, "查版本并分支"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未登记");
    }

    @Test
    void ifBranchWithoutElseIsAllowed() {
        String json = """
                [{"tool":"prom_buildinfo","params":{},"output_var":"info"},
                 {"if":"{{info}} != ''","then":[{"tool":"ai_generate","params":{"prompt":"有信息"}}]}]
                """;
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(json, "m", 0, 0));

        WorkflowGenerationService.GenerationResult result = service.generate(1L, "查版本，有信息就总结");

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1).kind()).isEqualTo("if");
        assertThat(result.steps().get(1).elseBranch()).isEmpty();
        assertThat(result.stepsJson()).doesNotContain("\"else\":");
    }

    // ---------- M3 接口语义聚焦（生成工作流时只列与需求相关的接口）----------

    private ToolRegistry registryWithBuiltin() {
        ToolRegistry reg = new ToolRegistry(List.of());
        reg.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "prom_buildinfo"; }
            @Override public String description() { return "查询版本"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "{}"; }
        });
        reg.register(new com.devmind.agent.AgentTool() {
            @Override public String name() { return "ai_generate"; }
            @Override public String description() { return "AI 生成文本"; }
            @Override public String parametersJsonSchema() { return "{}"; }
            @Override public String execute(String argumentsJson, Long userId) { return "ok"; }
        });
        return reg;
    }

    private ToolRegistry registryWithInterfaceTools(int count) {
        ToolRegistry reg = registryWithBuiltin();
        for (int i = 0; i < count; i++) {
            com.devmind.agent.AgentTool t = mock(com.devmind.agent.AgentTool.class);
            lenient().when(t.name()).thenReturn("api_tool_" + i);
            lenient().when(t.description()).thenReturn("接口 " + i);
            lenient().when(t.parametersJsonSchema()).thenReturn("{}");
            reg.register(t);
        }
        return reg;
    }

    private List<ToolDefinition> interfaceDefinitions(int count) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            defs.add(new ToolDefinition((long) (i + 1), 1L, "api_tool_" + i, "接口 " + i, "interface",
                    "http://x/api/" + i, "GET", null, null, "none", null, null, "READY", 1L, null));
        }
        return defs;
    }

    private String capturedSystemPrompt(WorkflowGenerationService svc, String description) {
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult(
                        "[{\"tool\":\"prom_buildinfo\",\"params\":{},\"goal\":\"x\"}]", "m", 0, 0));
        svc.generate(1L, description);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatRouter).chat(captor.capture(), anyString());
        return captor.getValue();
    }

    @Test
    void manyInterfacesOnlyListSemanticHitsInPrompt() {
        ToolRegistry reg = registryWithInterfaceTools(25);
        WorkflowGenerationService svc = new WorkflowGenerationService(
                chatRouter, reg, objectMapper, userService, toolAccessService,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class));
        Set<String> allNames = reg.all().stream().map(com.devmind.agent.AgentTool::name).collect(Collectors.toSet());
        when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenReturn(allNames);
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        OpenApiImportService openApi = mock(OpenApiImportService.class);
        when(openApi.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of(
                new ToolSemanticRepository.SemanticHit(4L, "api_tool_3", "库存", "http://x/3", "GET", 0.4),
                new ToolSemanticRepository.SemanticHit(18L, "api_tool_17", "库存", "http://x/17", "GET", 0.3)
        ));
        svc.setOpenApiImportService(openApi);

        String prompt = capturedSystemPrompt(svc, "查库存");

        // 命中的接口列出；未命中不列出；内置工具不受影响
        assertThat(prompt).contains("api_tool_3");
        assertThat(prompt).contains("api_tool_17");
        assertThat(prompt).doesNotContain("api_tool_0");
        assertThat(prompt).doesNotContain("api_tool_24");
        assertThat(prompt).contains("prom_buildinfo");
        assertThat(prompt).contains("ai_generate");
        verify(openApi).semanticSearch(eq("查库存"), eq(1L), anyInt());
    }

    @Test
    void manyInterfacesFallBackToAllWhenNoSemanticHit() {
        ToolRegistry reg = registryWithInterfaceTools(25);
        WorkflowGenerationService svc = new WorkflowGenerationService(
                chatRouter, reg, objectMapper, userService, toolAccessService,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class));
        Set<String> allNames = reg.all().stream().map(com.devmind.agent.AgentTool::name).collect(Collectors.toSet());
        when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenReturn(allNames);
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        OpenApiImportService openApi = mock(OpenApiImportService.class);
        when(openApi.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of());
        svc.setOpenApiImportService(openApi);

        String prompt = capturedSystemPrompt(svc, "查库存");

        // 命中为空 → 回退全量（接口可用优先）
        assertThat(prompt).contains("api_tool_0");
        assertThat(prompt).contains("api_tool_24");
    }

    @Test
    void fewInterfacesListAllWithoutSemanticSearch() {
        ToolRegistry reg = registryWithInterfaceTools(3);
        WorkflowGenerationService svc = new WorkflowGenerationService(
                chatRouter, reg, objectMapper, userService, toolAccessService,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class));
        Set<String> allNames = reg.all().stream().map(com.devmind.agent.AgentTool::name).collect(Collectors.toSet());
        when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenReturn(allNames);
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(3));
        OpenApiImportService openApi = mock(OpenApiImportService.class);
        svc.setOpenApiImportService(openApi);

        String prompt = capturedSystemPrompt(svc, "查库存");

        // 接口少 → 全量列出且不触发语义检索
        assertThat(prompt).contains("api_tool_0");
        assertThat(prompt).contains("api_tool_2");
        verify(openApi, never()).semanticSearch(anyString(), any(), anyInt());
    }

    @Test
    void withoutSemanticServiceListsAllInterfaces() {
        ToolRegistry reg = registryWithInterfaceTools(25);
        WorkflowGenerationService svc = new WorkflowGenerationService(
                chatRouter, reg, objectMapper, userService, toolAccessService,
                org.mockito.Mockito.mock(com.devmind.security.LlmInputGuard.class));
        Set<String> allNames = reg.all().stream().map(com.devmind.agent.AgentTool::name).collect(Collectors.toSet());
        when(toolAccessService.accessibleToolNames(eq(1L), eq(1L))).thenReturn(allNames);
        when(toolAccessService.accessibleDynamicTools(eq(1L), eq(1L))).thenReturn(interfaceDefinitions(25));
        // 不注入 openApiImportService → 回退全量（向后兼容）

        String prompt = capturedSystemPrompt(svc, "查库存");

        assertThat(prompt).contains("api_tool_0");
        assertThat(prompt).contains("api_tool_24");
    }
}
