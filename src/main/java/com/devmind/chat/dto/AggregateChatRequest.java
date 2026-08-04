package com.devmind.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AggregateChatRequest(
        @NotEmpty(message = "请选择至少一个知识库")
        List<Long> knowledgeBaseIds,
        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000")
        String question,
        Integer topK,
        List<String> tags
) {
}
