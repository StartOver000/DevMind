package com.devmind.modelusage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class ModelUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(
            Long userId,
            String scene,
            String model,
            int promptTokens,
            int completionTokens,
            BigDecimal estimatedCost
    ) {
        jdbcTemplate.update("""
                INSERT INTO model_usage
                    (user_id, scene, model, prompt_tokens, completion_tokens, estimated_cost, created_time)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, userId, scene, model, promptTokens, completionTokens, estimatedCost);
    }

    public List<ModelUsage> listByUser(Long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, user_id, scene, model, prompt_tokens, completion_tokens, estimated_cost, created_time
                FROM model_usage
                WHERE user_id = ?
                ORDER BY created_time DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new ModelUsage(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("scene"),
                rs.getString("model"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getBigDecimal("estimated_cost"),
                toOffset(rs.getTimestamp("created_time"))
        ), userId, limit);
    }

    public List<ModelUsage> listAll(int limit) {
        return jdbcTemplate.query("""
                SELECT id, user_id, scene, model, prompt_tokens, completion_tokens, estimated_cost, created_time
                FROM model_usage
                ORDER BY created_time DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new ModelUsage(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("scene"),
                rs.getString("model"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getBigDecimal("estimated_cost"),
                toOffset(rs.getTimestamp("created_time"))
        ), limit);
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
