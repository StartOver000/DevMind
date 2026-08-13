package com.devmind.knowledge.dto;

import com.devmind.chat.dto.Reference;

import java.util.List;

/** 纯检索响应：命中片段列表（含来源文档与相似度，不含 LLM 生成内容）。 */
public record KnowledgeBaseSearchResponse(
        List<Reference> results
) {
}
