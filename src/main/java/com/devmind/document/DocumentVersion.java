package com.devmind.document;

import java.time.OffsetDateTime;

public record DocumentVersion(
        Long id,
        Long documentId,
        int version,
        String fileName,
        String fileType,
        Long fileSize,
        String filePath,
        String contentHash,
        OffsetDateTime createdTime
) {
}
