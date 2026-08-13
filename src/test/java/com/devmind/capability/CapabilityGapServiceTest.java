package com.devmind.capability;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.tool.OpenApiImportService;
import com.devmind.tool.ToolSemanticRepository;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityGapServiceTest {

    private ChatRouter chatRouter;
    private OpenApiImportService openApiImportService;
    private UserService userService;
    private CapabilityGapService service;

    private ToolSemanticRepository.SemanticHit hit(String name) {
        return new ToolSemanticRepository.SemanticHit(1L, name, "描述 " + name,
                "https://x/" + name, "GET", 0.5);
    }

    @BeforeEach
    void setUp() {
        chatRouter = mock(ChatRouter.class);
        openApiImportService = mock(OpenApiImportService.class);
        userService = mock(UserService.class);
        service = new CapabilityGapService(chatRouter, openApiImportService, userService, new ObjectMapper());
        when(userService.tenantIdOf(1L)).thenReturn(1L);
    }

    @Test
    void analyzesCoverageAndGaps() {
        when(openApiImportService.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(
                List.of(hit("listOrders"), hit("checkStock")));
        String llm = """
                {"steps":[
                  {"step":"查询昨日订单","covered":true,"interface":"listOrders","note":"按时间筛选"},
                  {"step":"检查库存","covered":true,"interface":"checkStock","note":"查询库存"},
                  {"step":"发送企业微信通知","covered":false,"gap":{"suggestedName":"sendWechatMessage","method":"POST","path":"/wechat/messages","description":"向企业微信发送消息"}}
                ]}
                """;
        when(chatRouter.chat(anyString(), anyString())).thenReturn(new AiModelGateway.ChatResult(llm, "m", 0, 0));

        CapabilityGapService.CapabilityAnalysis result = service.analyze(1L, "每天汇总订单，库存不足时通知企业微信");

        assertThat(result.matchedInterfaces()).hasSize(2);
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(0).covered()).isTrue();
        assertThat(result.steps().get(0).interfaceName()).isEqualTo("listOrders");
        assertThat(result.steps().get(2).covered()).isFalse();
        assertThat(result.steps().get(2).gap().suggestedName()).isEqualTo("sendWechatMessage");
        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).method()).isEqualTo("POST");
        assertThat(result.gaps().get(0).path()).isEqualTo("/wechat/messages");
        verify(openApiImportService).semanticSearch(eq("每天汇总订单，库存不足时通知企业微信"), eq(1L), anyInt());
    }

    @Test
    void toleratesMarkdownCodeBlockWrapper() {
        when(openApiImportService.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of(hit("listOrders")));
        String llm = "```json\n{\"steps\":[{\"step\":\"查订单\",\"covered\":true,\"interface\":\"listOrders\"}]}\n```";
        when(chatRouter.chat(anyString(), anyString())).thenReturn(new AiModelGateway.ChatResult(llm, "m", 0, 0));

        CapabilityGapService.CapabilityAnalysis result = service.analyze(1L, "查订单");

        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).covered()).isTrue();
    }

    @Test
    void deduplicatesGapsByName() {
        when(openApiImportService.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of());
        String llm = """
                {"steps":[
                  {"step":"a","covered":false,"gap":{"suggestedName":"sendWechat","method":"POST","path":"/a","description":"发消息"}},
                  {"step":"b","covered":false,"gap":{"suggestedName":"sendWechat","method":"POST","path":"/a","description":"再发"}}
                ]}
                """;
        when(chatRouter.chat(anyString(), anyString())).thenReturn(new AiModelGateway.ChatResult(llm, "m", 0, 0));

        CapabilityGapService.CapabilityAnalysis result = service.analyze(1L, "发通知");

        assertThat(result.gaps()).hasSize(1);
        assertThat(result.gaps().get(0).suggestedName()).isEqualTo("sendWechat");
    }

    @Test
    void llmParseFailureReturnsMatchedInterfacesWithoutBreaking() {
        when(openApiImportService.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(
                List.of(hit("listOrders")));
        when(chatRouter.chat(anyString(), anyString())).thenReturn(
                new AiModelGateway.ChatResult("模型乱说话了没有 JSON", "m", 0, 0));

        CapabilityGapService.CapabilityAnalysis result = service.analyze(1L, "查订单");

        // 不中断：至少返回语义检索命中的接口 + 告警
        assertThat(result.matchedInterfaces()).hasSize(1);
        assertThat(result.steps()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> service.analyze(1L, "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("描述");
    }

    @Test
    void modelFailureThrows() {
        when(openApiImportService.semanticSearch(anyString(), eq(1L), anyInt())).thenReturn(List.of());
        when(chatRouter.chat(anyString(), anyString()))
                .thenThrow(new ApiException(com.devmind.common.ErrorCode.MODEL_CALL_FAILED, "模型不可用"));

        assertThatThrownBy(() -> service.analyze(1L, "查订单"))
                .isInstanceOf(ApiException.class);
    }
}
