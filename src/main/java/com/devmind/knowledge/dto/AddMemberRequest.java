package com.devmind.knowledge.dto;

import jakarta.validation.constraints.NotNull;

public record AddMemberRequest(
        @NotNull(message = "不能为空")
        Long userId,
        String role
) {
}
