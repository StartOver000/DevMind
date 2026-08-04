package com.devmind.sqldiagnosis;

public record SqlRisk(
        String rule,
        String level,
        String message,
        String evidence
) {
}
