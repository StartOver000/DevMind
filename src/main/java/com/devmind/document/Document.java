package com.devmind.document;

import java.time.OffsetDateTime;
import java.util.Map;

public record Document(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String fileType,
        Long fileSize,
        String filePath,
        String contentHash,
        String status,
        String errorMessage,
        Long createdBy,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime,
        Map<String, Object> metadata
) {
}
