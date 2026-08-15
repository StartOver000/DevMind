package com.devmind.config;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.CachedEmbeddingGateway;
import com.devmind.ai.EmbeddingCacheRepository;
import com.devmind.ai.FallbackEmbeddingGateway;
import com.devmind.ai.MockAiModelGateway;
import com.devmind.ai.SpringAiModelGateway;
import com.devmind.ai.ZhipuRestModelGateway;
import com.devmind.security.SecretCipher;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiModelConfig {

    @Bean
    @ConditionalOnProperty(name = "devmind.model-mode", havingValue = "openai", matchIfMissing = true)
    public AiModelGateway springAiModelGateway(
            EmbeddingModel embeddingModel,
            ChatModel chatModel,
            EmbeddingCacheRepository cache,
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            @Value("${spring.ai.openai.embedding.options.model:openai}") String embeddingModelName
    ) {
        return new CachedEmbeddingGateway(
                new SpringAiModelGateway(embeddingModel, chatModel),
                cache,
                "openai:" + embeddingModelName,
                meterRegistry
        );
    }

    @Bean
    @ConditionalOnProperty(name = "devmind.model-mode", havingValue = "mock")
    public AiModelGateway mockAiModelGateway(DevMindProperties properties, EmbeddingCacheRepository cache,
                                             io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new CachedEmbeddingGateway(
                new MockAiModelGateway(properties.embeddingDimensions()),
                cache,
                "mock:dim" + properties.embeddingDimensions(),
                meterRegistry
        );
    }

    @Bean
    @ConditionalOnProperty(name = "devmind.model-mode", havingValue = "zhipu")
    public AiModelGateway zhipuAiModelGateway(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            EmbeddingCacheRepository cache,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            @Value("${devmind.embedding-fallback.base-url:}") String embeddingFallbackBaseUrl,
            @Value("${devmind.embedding-fallback.api-key:}") String embeddingFallbackApiKey,
            @Value("${devmind.embedding-fallback.model:BAAI/bge-m3}") String embeddingFallbackModel
    ) {
        AiModelGateway gateway = new CachedEmbeddingGateway(
                new ZhipuRestModelGateway(restClientBuilder, properties, secretCipher, objectMapper),
                cache,
                "zhipu:" + properties.zhipuEmbeddingModel() + "@" + properties.zhipuBaseUrl(),
                meterRegistry
        );
        // 配置了备用 embedding（如硅基流动 bge-m3）时，主 embedding 失败自动切换；
        // 传主模型维度做校验——备用维度不一致时拒绝写入，防污染 pgvector 向量库
        if (embeddingFallbackBaseUrl != null && !embeddingFallbackBaseUrl.isBlank()) {
            gateway = new FallbackEmbeddingGateway(
                    gateway,
                    restClientBuilder,
                    embeddingFallbackBaseUrl,
                    secretCipher.resolve(embeddingFallbackApiKey),
                    embeddingFallbackModel,
                    properties.embeddingDimensions()
            );
        }
        return gateway;
    }
}
