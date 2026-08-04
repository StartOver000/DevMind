package com.devmind.document.dto;

import java.util.List;

public record BatchUploadResponse(
        int total,
        int failed,
        List<DocumentUploadResponse> items
) {
}
