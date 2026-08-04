package com.devmind.document.dto;

public record VersionCompareResponse(
        int fromVersion,
        int toVersion,
        String fromContent,
        String toContent
) {
}
