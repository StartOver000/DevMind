package com.devmind.document.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定大小切块策略：按最大字符数切块，标题行作为新块起点，
 * 每块在标题行后追加当前小节标题作为上下文前缀，便于对比不同切块策略。
 */
public class FixedSizeTextChunker implements TextChunker {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    private final int maxChars;

    public FixedSizeTextChunker(int maxChars) {
        this.maxChars = maxChars;
    }

    @Override
    public List<TextChunk> chunk(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder current = new StringBuilder();
        String currentHeading = null;
        int index = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                if (current.length() > 0) {
                    index = flush(chunks, current, currentHeading, index);
                }
                current = new StringBuilder();
                currentHeading = heading.group(1).trim();
                appendLine(current, line);
                continue;
            }

            if (current.length() > 0 && current.length() + line.length() + 1 > maxChars) {
                index = flush(chunks, current, currentHeading, index);
                current = new StringBuilder();
                if (currentHeading != null) {
                    current.append("# ").append(currentHeading).append("\n\n");
                }
            }

            appendLine(current, line);
            if (current.length() >= maxChars) {
                index = flush(chunks, current, currentHeading, index);
                current = new StringBuilder();
                if (currentHeading != null) {
                    current.append("# ").append(currentHeading).append("\n\n");
                }
            }
        }

        if (!current.isEmpty()) {
            flush(chunks, current, currentHeading, index);
        }
        return chunks;
    }

    private int flush(List<TextChunk> chunks, StringBuilder current, String heading, int index) {
        String content = current.toString().trim();
        if (!content.isEmpty()) {
            chunks.add(new TextChunk(index, content, heading));
            return index + 1;
        }
        return index;
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }
}
