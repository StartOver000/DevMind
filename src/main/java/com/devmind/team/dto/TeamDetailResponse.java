package com.devmind.team.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TeamDetailResponse(
        Long id,
        String name,
        String description,
        Long ownerId,
        OffsetDateTime createdTime,
        List<TeamMemberResponse> members
) {
}
