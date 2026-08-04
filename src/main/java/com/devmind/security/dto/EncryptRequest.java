package com.devmind.security.dto;

import jakarta.validation.constraints.NotBlank;

public record EncryptRequest(
        @NotBlank(message = "不能为空")
        String value
) {
}
