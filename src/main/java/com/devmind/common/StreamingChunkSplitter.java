package com.devmind.common;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式输出分片器：把完整回答文本按句子边界切成小块，供 SSE 逐块推送。
 * - 优先在中文/英文句子分隔符与换行处切块，保证语义完整、Markdown 结构不撕裂；
 * - 超过最大长度仍未遇到边界时硬切，避免单块过大导致前端长时间无更新。
 */
public final class StreamingChunkSplitter {

    /** 单块最大字符数 */
    private static final int MAX_CHUNK_CHARS = 30;
    /** 句子分隔符（中文标点 + 英文标点 + 换行） */
    private static final String SENTENCE_BOUNDARY = "。！？!?；;\n";

    private StreamingChunkSplitter() {
    }

    /**
     * 将文本切成流式推送块。
     * 规则：边界字符（。！？!?；;\n）作为块尾触发切块；块首的空白（空格/制表符）被剥离；
     * 换行保留（作为独立块或块的一部分），保证 Markdown 段落与拼接还原一致；
     * 纯空白块被丢弃。
     *
     * @param text 完整文本
     * @return 有序分块；空文本返回空列表
     */
    public static List<String> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            boolean boundary = SENTENCE_BOUNDARY.indexOf(c) >= 0;
            if (boundary || current.length() >= MAX_CHUNK_CHARS) {
                String chunk = stripLeadingWhitespace(current.toString());
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            String chunk = stripLeadingWhitespace(current.toString());
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /** 只剥离块首的空格与制表符，保留换行（保证拼接还原与原文本一致） */
    private static String stripLeadingWhitespace(String value) {
        int i = 0;
        while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) {
            i++;
        }
        return i == 0 ? value : value.substring(i);
    }
}
