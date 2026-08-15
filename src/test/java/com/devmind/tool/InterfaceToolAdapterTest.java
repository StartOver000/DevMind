package com.devmind.tool;

import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class InterfaceToolAdapterTest {

    @Mock
    private SecretCipher secretCipher;

    private RestClient.Builder builder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
    }

    private InterfaceToolAdapter adapter(ToolDefinition def) {
        return new InterfaceToolAdapter(def, builder, secretCipher, objectMapper, false, "");
    }

    private ToolDefinition def(String name, String url, String method, String authType, String authEnc, String mask) {
        return ToolDefinition.forInsert(
                1L, name, "测试接口", "interface", url, method, "{}", null,
                authType, authEnc, mask, "READY", 1L
        );
    }

    @Test
    void getSendsQueryParamsAndReturnsBody() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://crm.example.com/api/customers?days=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"clients\":[1,2]}", MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "customer_query", "http://crm.example.com/api/customers", "GET", "none", null, null));

        assertThat(adapter.execute("{\"days\":1}", 1L)).isEqualTo("{\"clients\":[1,2]}");
        server.verify();
    }

    @Test
    void postSendsJsonBody() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", "application/json"))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "create_order", "http://api.example.com/orders", "POST", "none", null, null));

        assertThat(adapter.execute("{\"amount\":100}", 1L)).isEqualTo("{\"id\":7}");
        server.verify();
    }

    @Test
    void injectsApiKeyHeaderFromDecryptedConfig() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/customers"))
                .andExpect(header("X-API-Key", "secret-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        when(secretCipher.resolve("enc:xxx"))
                .thenReturn("{\"location\":\"header\",\"key\":\"X-API-Key\",\"value\":\"secret-123\"}");

        InterfaceToolAdapter adapter = adapter(def(
                "secure_query", "http://api.example.com/customers", "GET", "api_key", "enc:xxx", null));

        adapter.execute("{}", 1L);
        server.verify();
    }

    @Test
    void masksSensitiveFieldsInJsonResponse() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/customers"))
                .andRespond(withSuccess(
                        "{\"name\":\"张三\",\"phone\":\"13800000000\"}",
                        MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "customer_query", "http://api.example.com/customers", "GET", "none", null, "[\"phone\"]"));

        String result = adapter.execute("{}", 1L);
        assertThat(result).contains("\"phone\":\"***\"");
        assertThat(result).contains("张三");
        server.verify();
    }

    @Test
    void returnsErrorJsonOnHttpFailureWithoutThrowing() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/broken"))
                .andRespond(withServerError());

        InterfaceToolAdapter adapter = adapter(def(
                "broken", "http://api.example.com/broken", "GET", "none", null, null));

        String result = adapter.execute("{}", 1L);
        assertThat(result).contains("\"error\"");
        server.verify();
    }

    @Test
    void rejectsNonHttpScheme() {
        InterfaceToolAdapter adapter = adapter(def(
                "ftp_tool", "ftp://example.com/file", "GET", "none", null, null));

        String result = adapter.execute("{}", 1L);
        assertThat(result).contains("仅支持 http/https");
    }

    @Test
    void exposesNameDescriptionAndSchemaFromDefinition() {
        ToolDefinition def = ToolDefinition.forInsert(
                1L, "customer_query", "查询客户列表", "interface",
                "http://x/api", "GET",
                "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\"}}}",
                null, "none", null, null, "READY", 1L);
        InterfaceToolAdapter adapter = adapter(def);

        assertThat(adapter.name()).isEqualTo("customer_query");
        assertThat(adapter.description()).isEqualTo("查询客户列表");
        assertThat(adapter.parametersJsonSchema()).contains("\"days\"");
    }

    @Test
    void replacesPathParamsBeforeUriParsing() {
        // 回归：路径占位符 {param} 必须在 URI 解析前替换，否则 URI.create 报 Illegal character in path
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/invoices/in_123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"in_123\"}", MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "get_invoice", "http://api.example.com/invoices/{invoice}", "GET", "none", null, null));

        assertThat(adapter.execute("{\"invoice\":\"in_123\"}", 1L)).isEqualTo("{\"id\":\"in_123\"}");
        server.verify();
    }

    @Test
    void pathParamValueContainingSpecialCharsIsEncodedSafely() {
        // 路径参数值含特殊字符时不应抛异常，且替换发生在 URI 解析前（之前会因 { } 非法字符直接抛）
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/orders/ORD-2026/001"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "get_order", "http://api.example.com/orders/{orderId}", "GET", "none", null, null));

        String result = adapter.execute("{\"orderId\":\"ORD-2026/001\"}", 1L);
        assertThat(result).doesNotContain("Illegal character");
        server.verify();
    }

    @Test
    void pathParamIsExcludedFromPostBody() {
        // 路径参数替换后不应再进入请求 body
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://api.example.com/customers/cus_9"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        InterfaceToolAdapter adapter = adapter(def(
                "update_customer", "http://api.example.com/customers/{id}", "POST", "none", null, null));

        String result = adapter.execute("{\"id\":\"cus_9\",\"name\":\"新客户\"}", 1L);
        assertThat(result).contains("\"ok\":true");
        server.verify();
    }

    @Test
    void oauth2ExchangesTokenThenCallsBusinessApi() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 首次调用：先向 token_url 换 token（client_credentials）
        server.expect(requestTo("https://auth.example.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-abc\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        // 业务请求携带 Bearer token
        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(header("Authorization", "Bearer tok-abc"))
                .andRespond(withSuccess("{\"id\":7}", MediaType.APPLICATION_JSON));
        when(secretCipher.resolve("enc:oauth"))
                .thenReturn("{\"token_url\":\"https://auth.example.com/oauth2/token\"," +
                        "\"client_id\":\"cid\",\"client_secret\":\"csec\",\"scope\":\"read\"}");

        InterfaceToolAdapter adapter = adapter(def(
                "oauth_api", "https://api.example.com/orders", "GET", "oauth2", "enc:oauth", null));

        assertThat(adapter.execute("{}", 1L)).isEqualTo("{\"id\":7}");
        server.verify();
    }

    @Test
    void oauth2ReusesCachedTokenWithoutReExchanging() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // token 端点只应被调用一次（第二次调用复用缓存）
        server.expect(requestTo("https://auth.example.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andRespond(withSuccess("{\"id\":2}", MediaType.APPLICATION_JSON));
        when(secretCipher.resolve("enc:oauth"))
                .thenReturn("{\"token_url\":\"https://auth.example.com/oauth2/token\"," +
                        "\"client_id\":\"cid\",\"client_secret\":\"csec\"}");

        InterfaceToolAdapter adapter = adapter(def(
                "oauth_api", "https://api.example.com/orders", "GET", "oauth2", "enc:oauth", null));

        adapter.execute("{}", 1L);
        adapter.execute("{}", 1L);
        // token 端点若被调用第二次，verify 会因多余请求失败
        server.verify();
    }

    @Test
    void oauth2ReExchangesTokenWhenCachedExpired() throws InterruptedException {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // expires_in=1 秒 → 提前 20% 过期（0.8s）→ 第二次调用需重新换 token
        server.expect(requestTo("https://auth.example.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-a\",\"expires_in\":1}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(header("Authorization", "Bearer tok-a"))
                .andRespond(withSuccess("{\"id\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth.example.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-b\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.example.com/orders"))
                .andExpect(header("Authorization", "Bearer tok-b"))
                .andRespond(withSuccess("{\"id\":2}", MediaType.APPLICATION_JSON));
        when(secretCipher.resolve("enc:oauth"))
                .thenReturn("{\"token_url\":\"https://auth.example.com/oauth2/token\"," +
                        "\"client_id\":\"cid\",\"client_secret\":\"csec\"}");

        InterfaceToolAdapter adapter = adapter(def(
                "oauth_api", "https://api.example.com/orders", "GET", "oauth2", "enc:oauth", null));

        assertThat(adapter.execute("{}", 1L)).isEqualTo("{\"id\":1}");
        Thread.sleep(1100); // 超过 0.8s 提前过期窗口
        assertThat(adapter.execute("{}", 1L)).isEqualTo("{\"id\":2}");
        server.verify();
    }
}
