package com.devmind.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void log(Long userId, String action, String targetType, Long targetId, String detail) {
        log(userId, action, targetType, targetId, detail, null);
    }

    public void log(Long userId, String action, String targetType, Long targetId, String detail, Long teamId) {
        jdbcTemplate.update("""
                INSERT INTO audit_log (user_id, action, target_type, target_id, detail, team_id, created_time)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, userId, action, targetType, targetId, detail, teamId);
    }

    public List<AuditLog> listByUser(Long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, user_id, action, target_type, target_id, detail, team_id, created_time
                FROM audit_log
                WHERE user_id = ?
                ORDER BY created_time DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new AuditLog(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("action"),
                rs.getString("target_type"),
                (Long) rs.getObject("target_id"),
                rs.getString("detail"),
                (Long) rs.getObject("team_id"),
                toOffset(rs.getTimestamp("created_time"))
        ), userId, limit);
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
