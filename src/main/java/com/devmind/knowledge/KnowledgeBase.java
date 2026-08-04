package com.devmind.knowledge;

import java.time.OffsetDateTime;

public record KnowledgeBase(
        Long id,
        String name,
        String description,
        String status,
        Long ownerId,
        Long teamId,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}
