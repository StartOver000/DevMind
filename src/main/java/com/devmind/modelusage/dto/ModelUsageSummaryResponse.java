package com.devmind.modelusage.dto;

import java.math.BigDecimal;

public record ModelUsageSummaryResponse(
        long totalCalls,
        long promptTokens,
        long completionTokens,
        BigDecimal estimatedCost
) {
}
