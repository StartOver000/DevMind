package com.devmind.knowledge.dto;

import java.time.OffsetDateTime;

public record MemberResponse(
        Long knowledgeBaseId,
        Long userId,
        String role,
        OffsetDateTime createdTime
) {
}
