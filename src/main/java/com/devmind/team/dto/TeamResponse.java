package com.devmind.team.dto;

import java.time.OffsetDateTime;

public record TeamResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        OffsetDateTime createdTime
) {
}
