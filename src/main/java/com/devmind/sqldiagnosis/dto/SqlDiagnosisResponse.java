package com.devmind.sqldiagnosis.dto;

import com.devmind.sqldiagnosis.SqlExplainRow;
import com.devmind.sqldiagnosis.SqlRisk;

import java.time.OffsetDateTime;
import java.util.List;

public record SqlDiagnosisResponse(
        Long id,
        String sql,
        String dataSource,
        String riskLevel,
        List<SqlRisk> risks,
        List<SqlExplainRow> plan,
        String advice,
        Long knowledgeBaseId,
        OffsetDateTime createdTime
) {
}
