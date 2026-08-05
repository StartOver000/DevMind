package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.document.DocumentService;
import com.devmind.document.dto.DocumentItem;
import com.devmind.document.dto.DocumentListResponse;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * doc_list：查询知识库内的文档清单，帮助 Agent 了解库里有什么文档。
 */
@Component
public class DocListTool implements AgentTool {

    private final DocumentService documentService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public DocListTool(
            DocumentService documentService,
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper
    ) {
        this.documentService = documentService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "doc_list";
    }

    @Override
    public String description() {
        return "查询知识库内的文档清单（文件名、处理状态、文本块数、上传时间）。当用户想了解某个知识库里有哪些文档、有没有某主题文档时调用。"
                + "参数：knowledgeBaseId(可选，知识库ID，缺省用第一个可用库)";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "knowledgeBaseId":{"type":"integer","description":"知识库ID，可选"}
                }}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        Map<String, Object> args = parseArgs(argumentsJson);
        Long kbId = args.get("knowledgeBaseId") == null
                ? firstAccessibleKnowledgeBase(userId)
                : Long.valueOf(String.valueOf(args.get("knowledgeBaseId")));
        if (kbId == null) {
            return "{\"error\": \"没有可访问的知识库\"}";
        }
        DocumentListResponse response = documentService.list(kbId, null, 1, 100, userId);
        List<Map<String, Object>> items = response.items().stream()
                .map(this::toMap)
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化文档列表失败", ex);
        }
    }

    private Map<String, Object> toMap(DocumentItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileName", item.fileName());
        map.put("status", item.status());
        map.put("chunkCount", item.chunkCount());
        map.put("createdTime", item.createdTime() == null ? null : item.createdTime().toString());
        return map;
    }

    private Long firstAccessibleKnowledgeBase(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        if (list.items() == null || list.items().isEmpty()) {
            return null;
        }
        return list.items().stream()
                .filter(item -> "ENABLED".equals(item.status()) && item.documentCount() != null && item.documentCount() > 0)
                .findFirst()
                .map(item -> item.id())
                .orElseGet(() -> list.items().stream()
                        .filter(item -> "ENABLED".equals(item.status()))
                        .findFirst()
                        .map(item -> item.id())
                        .orElse(null));
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("工具参数解析失败: " + ex.getMessage());
        }
    }
}
