package com.devmind.modelusage.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ModelUsageResponse(
        Long id,
        Long userId,
        String scene,
        String model,
        int promptTokens,
        int completionTokens,
        BigDecimal estimatedCost,
        OffsetDateTime createdTime
) {
}
