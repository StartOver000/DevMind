package com.devmind.evaluation.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record EvaluationRequest(
        @NotNull(message = "不能为空")
        Long knowledgeBaseId,
        List<String> tags,
        String rerankMode
) {
}
