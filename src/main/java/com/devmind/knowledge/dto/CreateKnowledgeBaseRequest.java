package com.devmind.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateKnowledgeBaseRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 100, message = "长度不能超过 100")
        String name,
        @Size(max = 500, message = "长度不能超过 500")
        String description,
        Long teamId
) {
}
