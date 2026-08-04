package com.devmind.document.dto;

public record DocumentUploadResponse(
        Long id,
        Long knowledgeBaseId,
        String fileName,
        String status,
        boolean duplicate,
        Long taskId
) {
}
