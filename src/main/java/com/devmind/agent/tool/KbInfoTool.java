package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kb_info：查询当前用户可访问的知识库列表（帮助 Agent 决定去哪里检索）。
 */
@Component
public class KbInfoTool implements AgentTool {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public KbInfoTool(KnowledgeBaseService knowledgeBaseService, ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "kb_info";
    }

    @Override
    public String description() {
        return "查询当前用户可访问的知识库列表（含 ID、名称、文档数）。当用户问题涉及知识库内容或需要选择知识库时调用。无参数。";
    }

    @Override
    public String parametersJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        List<Map<String, Object>> items = list.items().stream()
                .map(item -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", item.id());
                    map.put("name", item.name());
                    map.put("status", item.status());
                    map.put("documentCount", item.documentCount());
                    return map;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化知识库列表失败", ex);
        }
    }
}
