package com.devmind.agent;

import com.devmind.agent.dto.MemoryItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 长期记忆仓库：按用户保存 key-value 记忆，跨会话保留 */
@Repository
public class AgentMemoryRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentMemoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MemoryItem> listByUser(Long userId) {
        return jdbcTemplate.query(
                "SELECT memory_key, memory_value FROM agent_memory WHERE user_id = ? ORDER BY memory_key",
                (rs, rowNum) -> new MemoryItem(rs.getString("memory_key"), rs.getString("memory_value")),
                userId
        );
    }

    /** 全量覆盖用户记忆（简单可靠，避免逐条增删的边界问题） */
    public void replaceAll(Long userId, List<MemoryItem> items) {
        jdbcTemplate.update("DELETE FROM agent_memory WHERE user_id = ?", userId);
        if (items == null) {
            return;
        }
        for (MemoryItem item : items) {
            if (item.key() == null || item.key().isBlank()) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO agent_memory (user_id, memory_key, memory_value) VALUES (?, ?, ?)",
                    userId, item.key().trim(), item.value() == null ? "" : item.value().trim()
            );
        }
    }

    /** 单条合并：key 已存在则更新值，不存在则插入（供自动提取增量写入，不覆盖用户其他记忆） */
    public void upsert(Long userId, String key, String value) {
        if (userId == null || key == null || key.isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO agent_memory (user_id, memory_key, memory_value)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, memory_key)
                DO UPDATE SET memory_value = EXCLUDED.memory_value, updated_time = CURRENT_TIMESTAMP
                """,
                userId, key.trim(), value == null ? "" : value.trim()
        );
    }
}
