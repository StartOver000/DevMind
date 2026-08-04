package com.devmind.team;

import java.time.OffsetDateTime;

public record Team(
        Long id,
        String name,
        String description,
        Long ownerId,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}
