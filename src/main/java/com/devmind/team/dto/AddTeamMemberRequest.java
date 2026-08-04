package com.devmind.team.dto;

import jakarta.validation.constraints.NotNull;

public record AddTeamMemberRequest(
        @NotNull(message = "用户不能为空")
        Long userId,
        String role
) {
}
