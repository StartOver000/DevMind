package com.devmind.performance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RetrievalBenchmarkRequest(
        @NotNull(message = "不能为空")
        Long knowledgeBaseId,
        @NotBlank(message = "不能为空")
        String question,
        Integer iterations
) {
}
