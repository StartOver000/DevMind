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
}
