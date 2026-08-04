package com.devmind.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 覆盖默认 RestClient.Builder：为模型调用（智谱/备用模型）配置连接与读取超时，
 * 避免上游 API 挂起时请求无限阻塞（此前 429 挂起可导致请求 120s+ 不返回）。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient.Builder devmindRestClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder().requestFactory(factory);
    }
}
