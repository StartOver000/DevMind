package com.devmind.knowledge;

import com.devmind.ai.AiModelGateway;
import com.devmind.chat.dto.Reference;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.dto.KnowledgeBaseSearchRequest;
import com.devmind.knowledge.dto.KnowledgeBaseSearchResponse;
import com.devmind.retrieval.QueryRouter;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 纯检索端点（#4 平台自身压测基线 & 对外能力）：
 * 只做语义检索（问题向量化 + 向量/关键词混合检索），不调 LLM。
 * 外部使用者可先检索确认命中，再决定是否走问答（省模型成本）；也是平台检索链路的可压测入口。
 */
@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/search")
public class KnowledgeBaseSearchController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSearchController.class);
    /** 响应里片段内容截断长度（命中预览，避免大块内容撑爆响应） */
    private static final int CONTENT_PREVIEW_CHARS = 500;

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final DevMindProperties properties;

    public KnowledgeBaseSearchController(
            KnowledgeBaseService knowledgeBaseService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            DevMindProperties properties
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.properties = properties;
    }

    @PostMapping
    public KnowledgeBaseSearchResponse search(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody KnowledgeBaseSearchRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        String question = request.question().trim();
        int topK = request.topK() == null
                ? properties.retrievalTopK()
                : Math.min(Math.max(request.topK(), 1), properties.retrievalMaxTopK());
        Map<String, Object> metadataFilter = buildMetadataFilter(request.tags());

        // 问题向量化；失败降级纯关键词检索（与 ChatService 一致，保证链路可用）
        List<Double> queryVector = null;
        try {
            queryVector = modelGateway.embed(List.of(question)).get(0);
        } catch (Exception ex) {
            log.warn("检索向量化失败，降级关键词检索: {}", ex.getMessage());
        }
        QueryRouter.Route route = QueryRouter.route(question);
        List<RetrievalResult> results = queryVector == null
                ? retrievalService.searchByKeywords(knowledgeBaseId, question, topK, metadataFilter)
                : retrievalService.searchHybrid(
                        knowledgeBaseId,
                        queryVector,
                        question,
                        topK,
                        properties.retrievalMinScore(),
                        route.vectorWeight(),
                        route.keywordWeight(),
                        properties.retrievalHybridEnabled(),
                        metadataFilter
                );
        List<Reference> references = results.stream()
                .map(result -> new Reference(
                        result.documentId(),
                        result.documentName(),
                        result.chunkId(),
                        truncate(result.content(), CONTENT_PREVIEW_CHARS),
                        result.similarityScore(),
                        result.metadata()
                ))
                .toList();
        return new KnowledgeBaseSearchResponse(references);
    }

    private Map<String, Object> buildMetadataFilter(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        List<String> cleaned = tags.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return cleaned.isEmpty() ? Map.of() : Map.of("tags", cleaned);
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
