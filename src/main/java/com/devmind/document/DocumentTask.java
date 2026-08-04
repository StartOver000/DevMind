package com.devmind.document;

import java.time.OffsetDateTime;

public record DocumentTask(
        Long id,
        Long documentId,
        String status,
        int retryCount,
        int maxRetries,
        String errorMessage,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}
