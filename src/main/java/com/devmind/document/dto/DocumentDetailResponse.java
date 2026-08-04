package com.devmind.document.dto;

import java.time.OffsetDateTime;

public record DocumentDetailResponse(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String fileType,
        Long fileSize,
        String filePath,
        String contentHash,
        String status,
        String errorMessage,
        Integer chunkCount,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}
