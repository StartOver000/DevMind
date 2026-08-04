package com.devmind.sqldiagnosis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class SqlDiagnosisRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqlDiagnosisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Long save(
            Long userId,
            String sqlText,
            String dataSource,
            List<SqlExplainRow> plan,
            String riskLevel,
            List<SqlRisk> risks,
            String advice,
            Long knowledgeBaseId
    ) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO sql_diagnosis
                        (user_id, sql_text, data_source, explain_json, risk_level, risks_json, advice, knowledge_base_id, created_time)
                    VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, sqlText);
            ps.setString(3, dataSource);
            ps.setString(4, toJson(plan));
            ps.setString(5, riskLevel);
            ps.setString(6, toJson(risks));
            ps.setString(7, advice);
            if (knowledgeBaseId == null) {
                ps.setObject(8, null);
            } else {
                ps.setLong(8, knowledgeBaseId);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SqlDiagnosis> findById(Long id) {
        List<SqlDiagnosis> result = jdbcTemplate.query("""
                SELECT id, user_id, sql_text, data_source, explain_json, risk_level,
                       risks_json, advice, knowledge_base_id, created_time
                FROM sql_diagnosis
                WHERE id = ?
                """, (rs, rowNum) -> new SqlDiagnosis(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("sql_text"),
                rs.getString("data_source"),
                rs.getString("explain_json"),
                rs.getString("risk_level"),
                rs.getString("risks_json"),
                rs.getString("advice"),
                (Long) rs.getObject("knowledge_base_id"),
                toOffset(rs.getTimestamp("created_time"))
        ), id);
        return result.stream().findFirst();
    }

    public List<SqlDiagnosis> listByUser(Long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, user_id, sql_text, data_source, explain_json, risk_level,
                       risks_json, advice, knowledge_base_id, created_time
                FROM sql_diagnosis
                WHERE user_id = ?
                ORDER BY created_time DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new SqlDiagnosis(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("sql_text"),
                rs.getString("data_source"),
                rs.getString("explain_json"),
                rs.getString("risk_level"),
                rs.getString("risks_json"),
                rs.getString("advice"),
                (Long) rs.getObject("knowledge_base_id"),
                toOffset(rs.getTimestamp("created_time"))
        ), userId, limit);
    }

    public List<SqlExplainRow> parsePlan(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SqlExplainRow>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    public List<SqlRisk> parseRisks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SqlRisk>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
