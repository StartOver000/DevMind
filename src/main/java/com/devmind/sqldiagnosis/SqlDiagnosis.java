package com.devmind.sqldiagnosis;

import java.time.OffsetDateTime;

public record SqlDiagnosis(
        Long id,
        Long userId,
        String sqlText,
        String dataSource,
        String explainJson,
        String riskLevel,
        String risksJson,
        String advice,
        Long knowledgeBaseId,
        OffsetDateTime createdTime
) {
}
