package com.devmind.chat;

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
public class ChatRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long createConversation(Long knowledgeBaseId, String title, Long userId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO chat_conversation (knowledge_base_id, user_id, title, created_time, updated_time)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setLong(1, knowledgeBaseId);
            ps.setLong(2, userId);
            ps.setString(3, title);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Conversation> findConversationById(Long id) {
        List<Conversation> result = jdbcTemplate.query("""
                SELECT id, knowledge_base_id, user_id, title, created_time, updated_time
                FROM chat_conversation
                WHERE id = ?
                """, (rs, rowNum) -> new Conversation(
                rs.getLong("id"),
                rs.getLong("knowledge_base_id"),
                (Long) rs.getObject("user_id"),
                rs.getString("title"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        ), id);
        return result.stream().findFirst();
    }

    public List<Conversation> listByUser(Long userId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, knowledge_base_id, user_id, title, created_time, updated_time
                FROM chat_conversation
                WHERE user_id = ?
                ORDER BY updated_time DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new Conversation(
                rs.getLong("id"),
                rs.getLong("knowledge_base_id"),
                (Long) rs.getObject("user_id"),
                rs.getString("title"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        ), userId, limit);
    }

    public boolean deleteConversation(Long id, Long userId) {
        return jdbcTemplate.update(
                "DELETE FROM chat_conversation WHERE id = ? AND user_id = ?",
                id,
                userId
        ) > 0;
    }

    public void insertMessage(Long conversationId, String role, String content, Integer promptTokens, Integer completionTokens) {
        jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, role, content, prompt_tokens, completion_tokens, created_time)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, conversationId, role, content, promptTokens, completionTokens);
    }

    public List<ChatMessage> listMessages(Long conversationId) {
        return jdbcTemplate.query("""
                SELECT id, conversation_id, role, content, prompt_tokens, completion_tokens, created_time
                FROM chat_message
                WHERE conversation_id = ?
                ORDER BY created_time, id
                """, (rs, rowNum) -> new ChatMessage(
                rs.getLong("id"),
                rs.getLong("conversation_id"),
                rs.getString("role"),
                rs.getString("content"),
                (Integer) rs.getObject("prompt_tokens"),
                (Integer) rs.getObject("completion_tokens"),
                toOffset(rs.getTimestamp("created_time"))
        ), conversationId);
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
