package com.devmind.retrieval;

import com.devmind.common.HashUtils;
import com.devmind.document.chunker.TextChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void insertChunks(
            Long documentId,
            List<TextChunk> chunks,
            List<List<Double>> embeddings
    ) {
        insertChunks(documentId, chunks, embeddings, Map.of());
    }

    @Transactional
    public void insertChunks(
            Long documentId,
            List<TextChunk> chunks,
            List<List<Double>> embeddings,
            Map<String, Object> documentMetadata
    ) {
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            List<Double> vector = embeddings.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (chunk.heading() != null) {
                metadata.put("heading", chunk.heading());
            }
            if (documentMetadata != null) {
                metadata.putAll(documentMetadata);
            }
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (document_id, chunk_index, content, content_hash, metadata, embedding, created_time)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::vector, CURRENT_TIMESTAMP)
                    """,
                    documentId,
                    chunk.index(),
                    chunk.content(),
                    HashUtils.sha256(chunk.content()),
                    toJson(metadata),
                    toVector(vector));
        }
    }

    /** 查询文档现有片段的全部内容哈希（供增量 diff 使用） */
    public Set<String> findHashSetByDocument(Long documentId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT content_hash FROM document_chunk WHERE document_id = ?",
                String.class,
                documentId
        ));
    }

    /**
     * 增量替换片段（同一事务内完成，原子可见）：
     * 删除 {@code removedHashes} 对应的旧片段，再插入 {@code changedChunks} 的新片段；
     * 未变更片段不动（不重算 embedding）。检索要么看到全旧、要么看到全新。
     */
    @Transactional
    public void updateChunksIncremental(
            Long documentId,
            List<TextChunk> changedChunks,
            List<List<Double>> embeddings,
            List<String> removedHashes,
            Map<String, Object> documentMetadata
    ) {
        if (removedHashes != null) {
            for (String hash : removedHashes) {
                jdbcTemplate.update(
                        "DELETE FROM document_chunk WHERE document_id = ? AND content_hash = ?",
                        documentId,
                        hash
                );
            }
        }
        for (int i = 0; i < changedChunks.size(); i++) {
            TextChunk chunk = changedChunks.get(i);
            List<Double> vector = embeddings.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (chunk.heading() != null) {
                metadata.put("heading", chunk.heading());
            }
            if (documentMetadata != null) {
                metadata.putAll(documentMetadata);
            }
            jdbcTemplate.update("""
                    INSERT INTO document_chunk
                        (document_id, chunk_index, content, content_hash, metadata, embedding, created_time)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::vector, CURRENT_TIMESTAMP)
                    """,
                    documentId,
                    chunk.index(),
                    chunk.content(),
                    HashUtils.sha256(chunk.content()),
                    toJson(metadata),
                    toVector(vector));
        }
    }

    public List<RetrievalResult> search(
            Long knowledgeBaseId,
            List<Double> queryVector,
            int topK,
            double minScore,
            Map<String, Object> metadataFilter
    ) {
        String vector = toVector(queryVector);
        String filterJson = toJson(metadataFilter);
        return jdbcTemplate.query("""
                SELECT c.id, c.document_id, c.chunk_index, c.content, c.metadata,
                       d.file_name, c.embedding <=> ?::vector AS distance
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.knowledge_base_id = ? AND d.status = 'COMPLETED'
                  AND (?::jsonb IS NULL OR c.metadata @> ?::jsonb)
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """, (rs, rowNum) -> {
            double score = 1.0 - rs.getDouble("distance");
            return new RetrievalResult(
                    rs.getLong("id"),
                    rs.getLong("document_id"),
                    rs.getString("file_name"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    readMetadata(rs.getString("metadata")),
                    score
            );
        }, vector, knowledgeBaseId, filterJson, filterJson, vector, topK * 3)
                .stream()
                .filter(result -> result.similarityScore() >= minScore)
                .limit(topK)
                .toList();
    }

    public List<RetrievalResult> search(
            Long knowledgeBaseId,
            List<Double> queryVector,
            int topK,
            double minScore
    ) {
        return search(knowledgeBaseId, queryVector, topK, minScore, Map.of());
    }

    public List<RetrievalResult> searchHybrid(
            Long knowledgeBaseId,
            List<Double> queryVector,
            String question,
            int topK,
            double minScore,
            double vectorWeight,
            double keywordWeight,
            boolean hybridEnabled
    ) {
        return searchHybrid(
                knowledgeBaseId,
                queryVector,
                question,
                topK,
                minScore,
                vectorWeight,
                keywordWeight,
                hybridEnabled,
                Map.of()
        );
    }

    public List<RetrievalResult> searchHybrid(
            Long knowledgeBaseId,
            List<Double> queryVector,
            String question,
            int topK,
            double minScore,
            double vectorWeight,
            double keywordWeight,
            boolean hybridEnabled,
            Map<String, Object> metadataFilter
    ) {
        List<RetrievalResult> vectorResults = search(knowledgeBaseId, queryVector, topK * 2, minScore, metadataFilter);
        if (!hybridEnabled) {
            return vectorResults.stream().limit(topK).toList();
        }

        List<String> keywords = KeywordExtractor.extract(question, 10);
        if (keywords.isEmpty()) {
            return vectorResults.stream().limit(topK).toList();
        }

        List<RetrievalResult> keywordResults = searchByKeywords(knowledgeBaseId, keywords, topK * 2, metadataFilter);
        Map<Long, RetrievalResult> merged = new LinkedHashMap<>();
        for (RetrievalResult result : vectorResults) {
            merged.put(result.chunkId(), result);
        }
        for (RetrievalResult result : keywordResults) {
            RetrievalResult existing = merged.get(result.chunkId());
            if (existing == null) {
                merged.put(result.chunkId(), result);
            } else {
                double combined = vectorWeight * existing.similarityScore()
                        + keywordWeight * result.similarityScore();
                merged.put(result.chunkId(), new RetrievalResult(
                        existing.chunkId(),
                        existing.documentId(),
                        existing.documentName(),
                        existing.chunkIndex(),
                        existing.content(),
                        existing.metadata(),
                        combined
                ));
            }
        }

        return merged.values().stream()
                .filter(result -> result.similarityScore() >= minScore)
                .sorted(Comparator.comparingDouble(RetrievalResult::similarityScore).reversed())
                .limit(topK)
                .toList();
    }

    public List<RetrievalResult> searchByKeywords(
            Long knowledgeBaseId,
            List<String> keywords,
            int topK,
            Map<String, Object> metadataFilter
    ) {
        String filterJson = toJson(metadataFilter);
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.document_id, c.chunk_index, c.content, c.metadata, d.file_name
                FROM document_chunk c
                JOIN document d ON d.id = c.document_id
                WHERE d.knowledge_base_id = ? AND d.status = 'COMPLETED'
                  AND (?::jsonb IS NULL OR c.metadata @> ?::jsonb) AND (
                """);
        List<Object> params = new ArrayList<>();
        params.add(knowledgeBaseId);
        params.add(filterJson);
        params.add(filterJson);
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("c.content ILIKE ?");
            params.add("%" + keywords.get(i) + "%");
        }
        sql.append(") LIMIT ?");
        params.add(topK);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            String content = rs.getString("content");
            return new RetrievalResult(
                    rs.getLong("id"),
                    rs.getLong("document_id"),
                    rs.getString("file_name"),
                    rs.getInt("chunk_index"),
                    content,
                    readMetadata(rs.getString("metadata")),
                    keywordScore(content, keywords)
            );
        }, params.toArray());
    }

    private double keywordScore(String content, List<String> keywords) {
        String lower = content.toLowerCase(Locale.ROOT);
        double matches = 0;
        for (String keyword : keywords) {
            int from = 0;
            while (from < lower.length()) {
                int idx = lower.indexOf(keyword, from);
                if (idx < 0) {
                    break;
                }
                matches++;
                from = idx + keyword.length();
            }
        }
        return Math.min(1.0, matches / keywords.size());
    }

    public int countByDocument(Long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = ?",
                Integer.class,
                documentId
        );
        return count == null ? 0 : count;
    }

    public int deleteByDocument(Long documentId) {
        return jdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("metadata 序列化失败", ex);
        }
    }

    private String toVector(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
