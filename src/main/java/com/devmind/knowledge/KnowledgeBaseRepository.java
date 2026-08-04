package com.devmind.knowledge;

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
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<KnowledgeBase> findById(Long id) {
        List<KnowledgeBase> result = jdbcTemplate.query("""
                SELECT id, name, description, status, created_by, team_id, created_time, updated_time
                FROM knowledge_base
                WHERE id = ?
                """, (rs, rowNum) -> new KnowledgeBase(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                (Long) rs.getObject("created_by"),
                (Long) rs.getObject("team_id"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        ), id);
        return result.stream().findFirst();
    }

    public Optional<KnowledgeBase> findEnabledById(Long id) {
        return findById(id).filter(kb -> "ENABLED".equals(kb.status()));
    }

    public boolean existsById(Long id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge_base WHERE id = ?",
                Integer.class,
                id
        );
        return count != null && count > 0;
    }

    public Long create(String name, String description, Long ownerId, Long teamId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO knowledge_base (name, description, status, created_by, team_id, created_time, updated_time)
                    VALUES (?, ?, 'ENABLED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, ownerId);
            if (teamId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, teamId);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<KnowledgeBaseItem> listAccessible(Long userId) {
        return jdbcTemplate.query("""
                SELECT DISTINCT kb.id, kb.name, kb.status, kb.team_id, kb.created_time,
                       (SELECT COUNT(*) FROM document d
                        WHERE d.knowledge_base_id = kb.id AND d.status <> 'DELETED') AS document_count
                FROM knowledge_base kb
                WHERE kb.status = 'ENABLED'
                  AND (
                      EXISTS (
                          SELECT 1 FROM knowledge_base_member m
                          WHERE m.knowledge_base_id = kb.id AND m.user_id = ?
                      )
                      OR EXISTS (
                          SELECT 1 FROM team_member tm
                          WHERE tm.team_id = kb.team_id AND tm.user_id = ?
                      )
                  )
                ORDER BY kb.created_time DESC
                """, (rs, rowNum) -> new KnowledgeBaseItem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getLong("document_count"),
                (Long) rs.getObject("team_id")
        ), userId, userId);
    }

    public List<KnowledgeBaseItem> listAllEnabled() {
        return jdbcTemplate.query("""
                SELECT kb.id, kb.name, kb.status, kb.team_id,
                       (SELECT COUNT(*) FROM document d
                        WHERE d.knowledge_base_id = kb.id AND d.status <> 'DELETED') AS document_count
                FROM knowledge_base kb
                WHERE kb.status = 'ENABLED'
                ORDER BY kb.created_time DESC
                """, (rs, rowNum) -> new KnowledgeBaseItem(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getLong("document_count"),
                (Long) rs.getObject("team_id")
        ));
    }

    public boolean disable(Long id) {
        return jdbcTemplate.update("""
                UPDATE knowledge_base
                SET status = 'DISABLED', updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'ENABLED'
                """, id) > 0;
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
