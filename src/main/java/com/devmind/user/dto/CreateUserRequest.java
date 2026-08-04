package com.devmind.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 50, message = "长度不能超过 50")
        String username,
        @Size(max = 100, message = "长度不能超过 100")
        String displayName
) {
}
