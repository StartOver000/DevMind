package com.devmind.document.dto;

import java.util.List;

public record DocumentListResponse(
        List<DocumentItem> items,
        int page,
        int pageSize,
        long total
) {
}
