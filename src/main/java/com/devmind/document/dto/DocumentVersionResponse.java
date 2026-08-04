package com.devmind.document.dto;

import java.time.OffsetDateTime;

public record DocumentVersionResponse(
        Long documentId,
        int version,
        String fileName,
        String fileType,
        Long fileSize,
        String contentHash,
        OffsetDateTime createdTime
) {
}
