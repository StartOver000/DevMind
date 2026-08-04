package com.devmind.chat.dto;

import java.util.Map;

public record Reference(
        Long documentId,
        String documentName,
        Long chunkId,
        String content,
        double similarityScore,
        Map<String, Object> metadata
) {
}
