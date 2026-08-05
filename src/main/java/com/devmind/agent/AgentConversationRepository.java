package com.devmind.agent;

import com.devmind.agent.dto.AgentConversationItem;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class AgentConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long create(Long userId, String title) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO agent_conversation (user_id, title, created_time, updated_time) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    new String[]{"id"}
            );
            ps.setLong(1, userId);
            ps.setString(2, title);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? null : key.longValue();
    }

    public List<AgentConversationItem> listByUser(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return jdbcTemplate.query(
                "SELECT id, title, created_time FROM agent_conversation WHERE user_id = ? ORDER BY updated_time DESC LIMIT ?",
                (rs, rowNum) -> new AgentConversationItem(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_time").toLocalDateTime()
                ),
                userId, safeLimit
        );
    }

    public void delete(Long id, Long userId) {
        int updated = jdbcTemplate.update(
                "DELETE FROM agent_conversation WHERE id = ? AND user_id = ?",
                id, userId
        );
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
    }
}
