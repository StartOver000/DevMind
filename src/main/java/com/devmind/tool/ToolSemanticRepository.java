package com.devmind.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 接口语义档案仓储（tool_semantic 表，P1 接口语义化）。
 * 每个已登记接口一条语义档案：检索文本 + 向量，支持
 * "自然语言 → 语义检索命中接口" 的 <code>embedding <=> ?::vector</code> 余弦距离查询。
 */
@Repository
public class ToolSemanticRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolSemanticRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 语义检索命中项（JOIN tool_definition 附带接口元数据） */
    public record SemanticHit(
            Long toolId,
            String name,
            String description,
            String endpointUrl,
            String httpMethod,
            double score
    ) {
    }

    private static final RowMapper<SemanticHit> HIT_MAPPER = (ResultSet rs, int rowNum) -> new SemanticHit(
            rs.getLong("tool_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("endpoint_url"),
            rs.getString("http_method"),
            rs.getDouble("score")
    );

    /** 写入/更新语义档案（同一 tool 幂等 upsert） */
    public void upsert(Long tenantId, Long toolId, String semanticText, List<Double> embedding) {
        jdbcTemplate.update("""
                INSERT INTO tool_semantic (tool_id, tenant_id, semantic_text, embedding, created_time, updated_time)
                VALUES (?, ?, ?, ?::vector, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (tool_id) DO UPDATE
                    SET semantic_text = EXCLUDED.semantic_text,
                        embedding = EXCLUDED.embedding,
                        updated_time = CURRENT_TIMESTAMP
                """,
                toolId, tenantId, semanticText, toVector(embedding));
    }

    public String findSemanticText(Long toolId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT semantic_text FROM tool_semantic WHERE tool_id = ?",
                (rs, rowNum) -> rs.getString("semantic_text"),
                toolId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteByToolId(Long toolId) {
        jdbcTemplate.update("DELETE FROM tool_semantic WHERE tool_id = ?", toolId);
    }

    /** 语义通道：向量余弦距离检索（<=>），仅返回 READY 状态的接口 */
    public List<SemanticHit> semanticSearch(Long tenantId, List<Double> queryVector, int topK, double minScore) {
        String vector = toVector(queryVector);
        return jdbcTemplate.query("""
                SELECT t.id AS tool_id, t.name, t.description, t.endpoint_url, t.http_method,
                       1 - (s.embedding <=> ?::vector) AS score
                FROM tool_semantic s
                JOIN tool_definition t ON t.id = s.tool_id
                WHERE s.tenant_id = ? AND t.status = 'READY'
                ORDER BY s.embedding <=> ?::vector
                LIMIT ?
                """, HIT_MAPPER, vector, tenantId, vector, topK)
                .stream()
                .filter(hit -> hit.score() >= minScore)
                .toList();
    }

    /** 关键词通道：name/description 模糊匹配（embedding 失败时的降级检索） */
    public List<SemanticHit> keywordSearch(Long tenantId, String query, int limit) {
        String like = "%" + query.toLowerCase() + "%";
        return jdbcTemplate.query("""
                SELECT id AS tool_id, name, description, endpoint_url, http_method, 1.0 AS score
                FROM tool_definition
                WHERE tenant_id = ? AND status = 'READY'
                  AND (LOWER(name) LIKE ? OR LOWER(COALESCE(description, '')) LIKE ?)
                ORDER BY id
                LIMIT ?
                """, HIT_MAPPER, tenantId, like, like, limit);
    }

    private String toVector(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            throw new IllegalArgumentException("语义向量为空，无法检索/入库");
        }
        return "[" + vector.stream()
                .map(v -> String.format("%.6f", v))
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "]";
    }
}
