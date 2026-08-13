package com.devmind.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 纯检索请求（#4）：语义检索不需要 LLM 生成，只做"问题向量化 + 混合检索"。
 * 外部使用者可先检索确认命中，再决定是否走问答（省模型成本）。
 */
public record KnowledgeBaseSearchRequest(
        @NotBlank(message = "问题不能为空") String question,
        Integer topK,
        List<String> tags
) {
}
