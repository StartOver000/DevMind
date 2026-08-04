package com.devmind.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 2000, message = "长度不能超过 2000")
        String question,
        Integer topK,
        Long conversationId,
        List<String> tags
) {
}
