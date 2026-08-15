package com.devmind.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口工具 SSRF 防护回归测试：拦截私有/回环/链路本地地址与敏感主机名，
 * 白名单放行与关闭开关按预期工作。
 * authType=none 时执行不触碰 SecretCipher，故传 null。
 */
class InterfaceToolAdapterSsrfTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ToolDefinition def(String url) {
        return new ToolDefinition(1L, 1L, "tool", "测试", "interface", url, "GET",
                "{}", null, "none", null, null, "READY", 1L, "2026-08-15");
    }

    private String execute(String url, boolean enabled, String allowed) {
        InterfaceToolAdapter adapter = new InterfaceToolAdapter(
                def(url), RestClient.builder(), null, OBJECT_MAPPER, enabled, allowed);
        return adapter.execute("{}", null);
    }

    @Test
    void blocksLoopbackIp() {
        assertThat(execute("http://127.0.0.1:8080/admin", true, ""))
                .contains("SSRF");
    }

    @Test
    void blocksLocalhostHostname() {
        assertThat(execute("http://localhost:8080/admin", true, ""))
                .contains("SSRF");
    }

    @Test
    void blocksPrivateIp() {
        assertThat(execute("http://192.168.1.1:8080/internal", true, ""))
                .contains("SSRF");
        assertThat(execute("http://10.0.0.1/internal", true, ""))
                .contains("SSRF");
        assertThat(execute("http://172.16.0.1/internal", true, ""))
                .contains("SSRF");
    }

    @Test
    void blocksCloudMetadataIp() {
        assertThat(execute("http://169.254.169.254/latest/meta-data/", true, ""))
                .contains("SSRF");
    }

    @Test
    void blocksDockerInternalHost() {
        assertThat(execute("http://host.docker.internal:18080/api", true, ""))
                .contains("SSRF");
    }

    @Test
    void allowsPublicHost() {
        // 公网域名不应被拦（返回的是 HTTP 调用失败 401 等，而不是 SSRF 拦截）
        String result = execute("https://api.stripe.com/v1/customers", true, "");
        assertThat(result).doesNotContain("SSRF");
    }

    @Test
    void allowedHostsWhitelistBypasses() {
        String result = execute("http://host.docker.internal:18080/api", true, "host.docker.internal");
        assertThat(result).doesNotContain("SSRF");
    }

    @Test
    void disabledSkipsCheck() {
        String result = execute("http://127.0.0.1:18080/api", false, "");
        assertThat(result).doesNotContain("SSRF");
    }
}
