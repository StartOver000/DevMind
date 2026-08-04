package com.devmind.team.dto;

import java.time.OffsetDateTime;

public record TeamMemberResponse(
        Long userId,
        String username,
        String role,
        OffsetDateTime createdTime
) {
}
