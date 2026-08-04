package com.devmind.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Embedding 缓存表：按内容哈希缓存向量，避免重复调用嵌入模型。
 */
@Repository
public class EmbeddingCacheRepository {

    private final JdbcTemplate jdbcTemplate;

    public EmbeddingCacheRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<List<Double>> find(String contentHash) {
        List<List<Double>> rows = jdbcTemplate.query(
                "SELECT embedding FROM embedding_cache WHERE content_hash = ?",
                (rs, rowNum) -> parseVector(rs.getString("embedding")),
                contentHash
        );
        return rows.stream().findFirst();
    }

    public void put(String contentHash, List<Double> vector) {
        jdbcTemplate.update("""
                INSERT INTO embedding_cache (content_hash, embedding, created_time)
                VALUES (?, ?::vector, CURRENT_TIMESTAMP)
                ON CONFLICT (content_hash) DO NOTHING
                """, contentHash, toVectorString(vector));
    }

    public int count() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM embedding_cache",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private List<Double> parseVector(String vectorStr) {
        if (vectorStr == null || vectorStr.isBlank()) {
            return List.of();
        }
        String inner = vectorStr.trim();
        if (inner.startsWith("[")) {
            inner = inner.substring(1);
        }
        if (inner.endsWith("]")) {
            inner = inner.substring(0, inner.length() - 1);
        }
        if (inner.isBlank()) {
            return List.of();
        }
        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Double::parseDouble)
                .toList();
    }

    private String toVectorString(List<Double> vector) {
        return vector.stream()
                .map(d -> Double.toString(d))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
