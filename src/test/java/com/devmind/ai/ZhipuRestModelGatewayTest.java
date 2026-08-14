package com.devmind.ai;

import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 智谱网关 embedding 防御性测试（2026-08-14 修复）：
 * - input 数组 >24 条时自动分批（智谱 code 1214 限制）；
 * - input 含 null 时转空串（智谱 code 1210 限制）。
 */
class ZhipuRestModelGatewayTest {

    private DevMindProperties zhipuProperties() {
        return new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "https://open.bigmodel.cn/api/paas/v4", "test-key",
                "glm-4.7-flash", "embedding-2", 2000, false, true, "", "", "", "", "", ""
        );
    }

    private String embeddingsJson(int n) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"embedding\":[").append(i).append(".0,0.0,0.0]}");
        }
        sb.append("],\"model\":\"embedding-2\"}");
        return sb.toString();
    }

    private ZhipuRestModelGateway gateway(RestClient.Builder builder, SecretCipher cipher) {
        return new ZhipuRestModelGateway(builder, zhipuProperties(), cipher, new ObjectMapper());
    }

    @Test
    void embedBatchesOver24Items() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 31 条 → 自动分两批：24 + 7
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andRespond(withSuccess(embeddingsJson(24), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andRespond(withSuccess(embeddingsJson(7), MediaType.APPLICATION_JSON));

        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.resolve(anyString())).thenReturn("test-key");
        ZhipuRestModelGateway gw = gateway(builder, cipher);

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            texts.add("文本" + i);
        }
        List<List<Double>> result = gw.embed(texts);

        assertThat(result).hasSize(31);
        server.verify();
    }

    @Test
    void embedHandlesNullElements() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://open.bigmodel.cn/api/paas/v4/embeddings"))
                .andRespond(withSuccess(embeddingsJson(2), MediaType.APPLICATION_JSON));

        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.resolve(anyString())).thenReturn("test-key");
        ZhipuRestModelGateway gw = gateway(builder, cipher);

        // null 元素 → 转空串，不触发智谱 400（code 1210）；Arrays.asList 允许 null 元素
        List<List<Double>> result = gw.embed(java.util.Arrays.asList(null, "hello"));

        assertThat(result).hasSize(2);
        server.verify();
    }

    @Test
    void embedEmptyListReturnsEmptyWithoutHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.resolve(anyString())).thenReturn("test-key");
        ZhipuRestModelGateway gw = gateway(builder, cipher);

        assertThat(gw.embed(List.of())).isEmpty();
        server.verify(); // 无任何 HTTP 请求
    }
}
