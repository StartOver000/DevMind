package com.devmind.document.dto;

import java.util.List;

public record DocumentVersionListResponse(
        int currentVersion,
        List<DocumentVersionResponse> items
) {
}
