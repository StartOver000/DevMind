package com.devmind.document.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultTextChunkerTest {

    @Test
    void keepsHeadingMetadataAndOrder() {
        DefaultTextChunker chunker = new DefaultTextChunker(1500, 200);
        String text = """
                # 标题一

                第一段内容。

                ## 标题二

                第二段内容。
                """;

        List<TextChunk> chunks = chunker.chunk(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).heading()).isEqualTo("标题一");
        assertThat(chunks.get(0).content()).contains("标题一", "第一段内容");
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(chunks.size() - 1).heading()).isEqualTo("标题二");
    }

    @Test
    void splitsLongParagraphsStably() {
        DefaultTextChunker chunker = new DefaultTextChunker(100, 20);
        String longText = "# A\n\n" + "词".repeat(300) + "\n\n# B\n\n正文";

        List<TextChunk> chunks = chunker.chunk(longText);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).heading()).isEqualTo("A");
        assertThat(chunks.get(chunks.size() - 1).heading()).isEqualTo("B");
    }
}
