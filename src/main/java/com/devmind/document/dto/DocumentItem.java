package com.devmind.document.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record DocumentItem(
        Long id,
        String fileName,
        String fileType,
        String status,
        Integer chunkCount,
        OffsetDateTime createdTime,
        List<String> tags
) {
}
