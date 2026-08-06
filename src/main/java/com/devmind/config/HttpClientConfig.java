package com.devmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 覆盖默认 RestClient.Builder：为模型调用（智谱/备用模型）配置连接与读取超时，
 * 避免上游 API 挂起时请求无限阻塞（此前 429 挂起可导致请求 120s+ 不返回）。
 * read 15s：必须小于 Agent 工具超时（20s），保证备用模型（免费档）响应慢时
 * 能快速失败并触发上层降级，而不是被工具超时误杀导致整个 Agent 降级本地。
 *
 * 2026-08：改用 JDK HttpClient（JdkClientHttpRequestFactory）——
 * 此前 SimpleClientHttpRequestFactory（HttpURLConnection）会默认请求 gzip，
 * 智谱对大请求返回 Content-Encoding: gzip，解压后 Spring 拿到 application/octet-stream
 * 导致 Jackson 解析失败（表现为"模型链路不稳定"）。JDK HttpClient 默认不请求 gzip，
 * 叠加显式 Accept-Encoding: identity，服务器返回明文 JSON，彻底规避该问题。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder devmindRestClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder()
                .requestFactory(factory)
                // 显式要求不压缩：服务器返回明文 JSON，避免 gzip 解压后 content-type 判定问题
                .defaultHeader("Accept-Encoding", "identity");
    }
}
