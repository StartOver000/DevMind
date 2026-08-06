package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.ai.AiModelGateway;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * kb_search：知识库混合检索工具。
 * 返回与问题最相关的 Top-K 文档片段（含相似度），供模型引用。
 */
@Component
public class KbSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(KbSearchTool.class);

    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final DevMindProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public KbSearchTool(
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            DevMindProperties properties,
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper
    ) {
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.properties = properties;
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "kb_search";
    }

    @Override
    public String description() {
        return "按语义检索研发知识库内容，返回与问题最相关的文档片段（含文件名、内容、相似度分数）。"
                + "当用户问题需要从文档内容中找答案/依据/方案时调用（不知道具体文档名时用它最合适）。"
                + "若用户明确提到具体文档名/标题，先用 doc_search 按文件名定位再结合本工具取内容。"
                + "参数：knowledgeBaseId(可选，知识库ID，缺省用第一个可用库), "
                + "question(必填，检索问题), topK(可选，返回条数，默认5)";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "knowledgeBaseId":{"type":"integer","description":"知识库ID，可选"},
                  "question":{"type":"string","description":"检索问题"},
                  "topK":{"type":"integer","description":"返回条数，默认5"}
                },"required":["question"]}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        Map<String, Object> args = parseArgs(argumentsJson);
        String question = args.get("question") == null ? "" : String.valueOf(args.get("question")).trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("kb_search 缺少 question 参数");
        }
        int topK = args.get("topK") == null
                ? properties.retrievalTopK()
                : Math.min(Math.max(Integer.parseInt(String.valueOf(args.get("topK"))), 1), 10);
        Long kbId = args.get("knowledgeBaseId") == null
                ? firstAccessibleKnowledgeBase(userId)
                : Long.valueOf(String.valueOf(args.get("knowledgeBaseId")));
        if (kbId == null) {
            return "{\"error\": \"没有可访问的知识库\"}";
        }

        List<RetrievalResult> results = search(kbId, question, topK);
        List<Map<String, Object>> items = results.stream()
                .map(result -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("documentName", result.documentName());
                    item.put("chunkIndex", result.chunkIndex());
                    item.put("similarityScore", Math.round(result.similarityScore() * 10000.0) / 10000.0);
                    item.put("content", result.content());
                    return item;
                })
                .toList();
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化检索结果失败", ex);
        }
    }

    private List<RetrievalResult> search(Long kbId, String question, int topK) {
        try {
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            return retrievalService.searchHybrid(
                    kbId,
                    vector,
                    question,
                    topK,
                    properties.retrievalMinScore(),
                    properties.retrievalVectorWeight(),
                    properties.retrievalKeywordWeight(),
                    properties.retrievalHybridEnabled()
            );
        } catch (Exception ex) {
            log.warn("kb_search embedding 不可用，降级关键词检索: {}", ex.getMessage());
            return retrievalService.searchByKeywords(kbId, question, topK, Map.of());
        }
    }

    private Long firstAccessibleKnowledgeBase(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        if (list.items() == null || list.items().isEmpty()) {
            return null;
        }
        // 优先选有文档的库，避免检索空库
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
