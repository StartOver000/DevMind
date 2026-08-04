package com.devmind.sqldiagnosis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SqlDiagnosisRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000")
        String sql,
        String dataSource,
        Long knowledgeBaseId
) {
}
