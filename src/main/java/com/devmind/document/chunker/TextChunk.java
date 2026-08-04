package com.devmind.document.chunker;

public record TextChunk(
        int index,
        String content,
        String heading
) {
}
