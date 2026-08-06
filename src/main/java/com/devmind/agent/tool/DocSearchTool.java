package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.document.DocumentRepository;
import com.devmind.document.dto.DocumentItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * doc_search：按文件名/标题模糊检索知识库文档（C2）。
 * 与 kb_search（按内容语义检索）互补：用户明确知道要找哪份文档/什么主题的文档时，
 * 按名称检索更精准、零模型开销。
 */
@Component
public class DocSearchTool implements AgentTool {

    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public DocSearchTool(DocumentRepository documentRepository, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "doc_search";
    }

    @Override
    public String description() {
        return "按文件名/标题模糊检索知识库中的文档（返回文件名、类型、状态、文本块数、上传时间，不含内容）。"
                + "当用户提到具体文档名、或想知道库里有没有某主题的文档时调用，比 kb_search 更精确；"
                + "参数：keyword(必填，文件名关键词，如'索引'、'周报')，limit(可选，返回条数，默认10，最多20)。"
                + "需要文档具体内容时再配合 kb_search 检索片段。";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "keyword":{"type":"string","description":"文件名关键词，必填"},
                  "limit":{"type":"integer","description":"返回条数，默认10，最多20"}
                },"required":["keyword"]}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            String keyword = args.path("keyword").asText("").trim();
            if (keyword.isEmpty()) {
                return "{\"error\": \"doc_search 缺少 keyword 参数\"}";
            }
            int limit = Math.max(1, Math.min(args.path("limit").asInt(10), 20));
            List<DocumentItem> items = documentRepository.searchByName(keyword, limit);
            if (items.isEmpty()) {
                return "{\"matched\": 0, \"message\": \"没有文件名包含「" + keyword + "」的文档\"}";
            }
            List<Map<String, Object>> list = items.stream()
                    .map(item -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", item.id());
                        map.put("fileName", item.fileName());
                        map.put("fileType", item.fileType());
                        map.put("status", item.status());
                        map.put("chunkCount", item.chunkCount());
                        map.put("createdTime", item.createdTime() == null ? null : item.createdTime().toString());
                        return map;
                    })
                    .toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matched", list.size());
            result.put("documents", list);
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"error\": \"doc_search 执行失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + "\"}";
        }
    }
}
