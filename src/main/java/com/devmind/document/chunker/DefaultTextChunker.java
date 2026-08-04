package com.devmind.document.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultTextChunker implements TextChunker {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$");

    private final int maxChars;
    private final int overlapChars;

    public DefaultTextChunker(int maxChars, int overlapChars) {
        this.maxChars = maxChars;
        this.overlapChars = overlapChars;
    }

    @Override
    public List<TextChunk> chunk(String text) {
        List<TextChunk> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder current = new StringBuilder();
        String currentHeading = null;
        boolean inCodeBlock = false;
        int index = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock && current.length() > 0 && current.length() + line.length() + 1 > maxChars) {
                    index = flush(chunks, current, currentHeading, index);
                    current = new StringBuilder();
                }
                appendLine(current, line);
                continue;
            }

            Matcher heading = HEADING.matcher(trimmed);
            if (!inCodeBlock && heading.matches()) {
                index = flush(chunks, current, currentHeading, index);
                current = new StringBuilder();
                currentHeading = heading.group(1).trim();
                appendLine(current, line);
                continue;
            }

            if (!inCodeBlock
                    && current.length() > 0
                    && current.length() + line.length() + 1 > maxChars
                    && isBoundary(trimmed)) {
                String previous = current.toString();
                index = flush(chunks, current, currentHeading, index);
                current = new StringBuilder();
                if (currentHeading != null && overlapChars > 0 && previous.length() > overlapChars) {
                    current.append(overlapTail(previous));
                    appendLine(current, "");
                }
            }

            appendLine(current, line);
            if (!inCodeBlock && current.length() >= maxChars) {
                index = flush(chunks, current, currentHeading, index);
                current = new StringBuilder();
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

    private String overlapTail(String content) {
        int start = Math.max(0, content.length() - overlapChars);
        return content.substring(start);
    }

    private boolean isBoundary(String line) {
        return line.startsWith("- ")
                || line.startsWith("* ")
                || line.startsWith("+ ")
                || line.startsWith("> ")
                || line.startsWith("|")
                || line.matches("\\d+[.、].*");
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }
}
