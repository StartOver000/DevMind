package com.devmind.config;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.CachedEmbeddingGateway;
import com.devmind.ai.EmbeddingCacheRepository;
import com.devmind.ai.MockAiModelGateway;
import com.devmind.ai.SpringAiModelGateway;
import com.devmind.ai.ZhipuRestModelGateway;
import com.devmind.security.SecretCipher;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
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
            EmbeddingCacheRepository cache
    ) {
        return new CachedEmbeddingGateway(new SpringAiModelGateway(embeddingModel, chatModel), cache);
    }

    @Bean
    @ConditionalOnProperty(name = "devmind.model-mode", havingValue = "mock")
    public AiModelGateway mockAiModelGateway(DevMindProperties properties, EmbeddingCacheRepository cache) {
        return new CachedEmbeddingGateway(new MockAiModelGateway(properties.embeddingDimensions()), cache);
    }

    @Bean
    @ConditionalOnProperty(name = "devmind.model-mode", havingValue = "zhipu")
    public AiModelGateway zhipuAiModelGateway(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            EmbeddingCacheRepository cache,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        return new CachedEmbeddingGateway(
                new ZhipuRestModelGateway(restClientBuilder, properties, secretCipher, objectMapper),
                cache
        );
    }
}
