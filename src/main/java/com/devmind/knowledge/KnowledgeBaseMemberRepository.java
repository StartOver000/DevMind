package com.devmind.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class KnowledgeBaseMemberRepository {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseMemberRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertOwner(Long knowledgeBaseId, Long userId) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_base_member (knowledge_base_id, user_id, role, created_time)
                VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP)
                ON CONFLICT (knowledge_base_id, user_id) DO NOTHING
                """, knowledgeBaseId, userId);
    }

    public void addMember(Long knowledgeBaseId, Long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO knowledge_base_member (knowledge_base_id, user_id, role, created_time)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (knowledge_base_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """, knowledgeBaseId, userId, role);
    }

    public boolean existsMember(Long knowledgeBaseId, Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_base_member
                WHERE knowledge_base_id = ? AND user_id = ?
                """, Integer.class, knowledgeBaseId, userId);
        return count != null && count > 0;
    }

    public boolean isOwner(Long knowledgeBaseId, Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_base_member
                WHERE knowledge_base_id = ? AND user_id = ? AND role = 'OWNER'
                """, Integer.class, knowledgeBaseId, userId);
        return count != null && count > 0;
    }

    public List<KnowledgeBaseMember> listMembers(Long knowledgeBaseId) {
        return jdbcTemplate.query("""
                SELECT knowledge_base_id, user_id, role, created_time
                FROM knowledge_base_member
                WHERE knowledge_base_id = ?
                ORDER BY created_time, user_id
                """, (rs, rowNum) -> new KnowledgeBaseMember(
                rs.getLong("knowledge_base_id"),
                rs.getLong("user_id"),
                rs.getString("role"),
                toOffset(rs.getTimestamp("created_time"))
        ), knowledgeBaseId);
    }

    public boolean removeMember(Long knowledgeBaseId, Long userId) {
        return jdbcTemplate.update("""
                DELETE FROM knowledge_base_member
                WHERE knowledge_base_id = ? AND user_id = ? AND role <> 'OWNER'
                """, knowledgeBaseId, userId) > 0;
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
