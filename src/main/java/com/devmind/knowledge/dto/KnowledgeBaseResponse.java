package com.devmind.knowledge.dto;

import java.time.OffsetDateTime;

public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        String status,
        Long ownerId,
        Long teamId,
        OffsetDateTime createdTime
) {
}
