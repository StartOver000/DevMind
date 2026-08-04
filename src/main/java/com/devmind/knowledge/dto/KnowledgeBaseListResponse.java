package com.devmind.knowledge.dto;

import com.devmind.knowledge.KnowledgeBaseItem;

import java.util.List;

public record KnowledgeBaseListResponse(List<KnowledgeBaseItem> items) {
}
