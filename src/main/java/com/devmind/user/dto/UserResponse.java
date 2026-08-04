package com.devmind.user.dto;

import java.time.OffsetDateTime;

public record UserResponse(
        Long id,
        String username,
        String displayName,
        OffsetDateTime createdTime
) {
}
