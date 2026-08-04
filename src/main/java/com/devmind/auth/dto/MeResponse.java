package com.devmind.auth.dto;

public record MeResponse(
        Long userId,
        String username,
        String displayName
) {
}
