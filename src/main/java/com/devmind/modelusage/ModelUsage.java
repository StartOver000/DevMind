package com.devmind.modelusage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ModelUsage(
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
