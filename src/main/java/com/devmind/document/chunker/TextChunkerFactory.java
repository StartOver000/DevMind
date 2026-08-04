package com.devmind.document.chunker;

import com.devmind.config.DevMindProperties;
import org.springframework.stereotype.Component;

/**
 * 根据配置选择切块策略，切换方案可回退（默认 BOUNDARY，与历史行为一致）。
 */
@Component
public class TextChunkerFactory {

    private final DevMindProperties properties;
    private final DefaultTextChunker defaultTextChunker;
    private final FixedSizeTextChunker fixedSizeTextChunker;

    public TextChunkerFactory(
            DevMindProperties properties,
            DefaultTextChunker defaultTextChunker,
            FixedSizeTextChunker fixedSizeTextChunker
    ) {
        this.properties = properties;
        this.defaultTextChunker = defaultTextChunker;
        this.fixedSizeTextChunker = fixedSizeTextChunker;
    }

    public TextChunker get() {
        return ChunkerStrategy.from(properties.chunkerStrategy()) == ChunkerStrategy.FIXED
                ? fixedSizeTextChunker
                : defaultTextChunker;
    }
}
