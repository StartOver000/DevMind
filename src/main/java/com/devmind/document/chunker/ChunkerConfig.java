package com.devmind.document.chunker;

import com.devmind.config.DevMindProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 切块器装配配置：由配置类统一创建，chunker 类本身不依赖 Spring 注解，
 * 便于在单元测试中直接 new 并 mock。
 */
@Configuration
public class ChunkerConfig {

    @Bean
    public DefaultTextChunker defaultTextChunker(DevMindProperties properties) {
        return new DefaultTextChunker(properties.maxChunkChars(), properties.overlapChars());
    }

    @Bean
    public FixedSizeTextChunker fixedSizeTextChunker(DevMindProperties properties) {
        return new FixedSizeTextChunker(properties.maxChunkChars());
    }
}
