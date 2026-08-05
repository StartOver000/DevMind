package com.devmind.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingChunkSplitterTest {

    @Test
    void emptyTextReturnsEmptyList() {
        assertTrue(StreamingChunkSplitter.split(null).isEmpty());
        assertTrue(StreamingChunkSplitter.split("").isEmpty());
        assertTrue(StreamingChunkSplitter.split("   ").isEmpty());
    }

    @Test
    void splitsOnChineseSentenceBoundaries() {
        String text = "第一句。第二句！第三句？";
        List<String> chunks = StreamingChunkSplitter.split(text);
        assertEquals(3, chunks.size());
        assertEquals("第一句。", chunks.get(0));
        assertEquals("第二句！", chunks.get(1));
        assertEquals("第三句？", chunks.get(2));
    }

    @Test
    void splitsOnNewlineAndEnglishPunctuation() {
        String text = "line one;\nline two!";
        List<String> chunks = StreamingChunkSplitter.split(text);
        assertEquals("line one;", chunks.get(0));
        assertTrue(chunks.contains("line two!"));
        // 换行保留，拼接可还原
        assertEquals(text, String.join("", chunks));
    }

    @Test
    void hardSplitsLongSentenceWithoutBoundary() {
        // 60 字无标点：应按最大块长硬切
        String text = "这是一个非常长的没有标点符号的句子用来验证超过最大块长之后必须强制切分以保证前端能够及时收到内容";
        List<String> chunks = StreamingChunkSplitter.split(text);
        assertTrue(chunks.size() >= 2);
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 30, "单块不能超过最大长度: " + chunk);
        }
    }

    @Test
    void concatenatedChunksEqualOriginal() {
        String text = "回答的第一部分。这是第二部分，包含一些英文 content and details；第三部分包含换行。\n下一段内容!";
        List<String> chunks = StreamingChunkSplitter.split(text);
        String joined = String.join("", chunks);
        assertEquals(text, joined);
    }

    @Test
    void stripsLeadingWhitespaceOfChunk() {
        String text = "开头。 \n  缩进内容";
        List<String> chunks = StreamingChunkSplitter.split(text);
        assertTrue(chunks.stream().noneMatch(c -> c.startsWith(" ")));
        // 内容不丢失（前导空白被剥离）
        assertTrue(chunks.stream().anyMatch(c -> c.contains("缩进内容")));
    }

    @Test
    void shortTextIsSingleChunk() {
        List<String> chunks = StreamingChunkSplitter.split("一句话。");
        assertEquals(1, chunks.size());
        assertEquals("一句话。", chunks.get(0));
    }
}
