package com.devmind.team;

import java.time.OffsetDateTime;

public record TeamMemberView(
        Long teamId,
        Long userId,
        String username,
        String role,
        OffsetDateTime createdTime
) {
}
