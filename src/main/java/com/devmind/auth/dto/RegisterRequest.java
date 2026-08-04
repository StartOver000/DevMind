package com.devmind.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 50, message = "长度不能超过 50")
        String username,
        @NotBlank(message = "不能为空")
        @Size(min = 6, max = 100, message = "长度 6-100")
        String password,
        @Size(max = 100, message = "长度不能超过 100")
        String displayName
) {
}
