package com.devmind.document.dto;

import java.time.OffsetDateTime;

public record DocumentTaskResponse(
        Long taskId,
        Long documentId,
        String status,
        int retryCount,
        int maxRetries,
        String errorMessage,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}
