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
                """
                SELECT id, memory_key, memory_value, source,
                       to_char(created_time, 'YYYY-MM-DD HH24:MI:SS') AS created_time,
                       to_char(updated_time, 'YYYY-MM-DD HH24:MI:SS') AS updated_time
                FROM agent_memory WHERE user_id = ? ORDER BY updated_time DESC, id DESC
                """,
                (rs, rowNum) -> new MemoryItem(
                        rs.getLong("id"),
                        rs.getString("memory_key"),
                        rs.getString("memory_value"),
                        rs.getString("source"),
                        rs.getString("created_time"),
                        rs.getString("updated_time")
                ),
                userId
        );
    }

    /** 单条删除（可追溯记忆：按 id 删除一条；返回受影响行数） */
    public int deleteById(Long userId, Long id) {
        return jdbcTemplate.update(
                "DELETE FROM agent_memory WHERE user_id = ? AND id = ?",
                userId, id
        );
    }

    /** 全量覆盖用户记忆（简单可靠，避免逐条增删的边界问题）；手动编辑 source=manual */
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
                    """
                    INSERT INTO agent_memory (user_id, memory_key, memory_value, source)
                    VALUES (?, ?, ?, 'manual')
                    """,
                    userId, item.key().trim(), item.value() == null ? "" : item.value().trim()
            );
        }
    }

    /** 单条合并：key 已存在则更新值，不存在则插入（供自动提取增量写入，不覆盖用户其他记忆）；source=auto */
    public void upsert(Long userId, String key, String value) {
        if (userId == null || key == null || key.isBlank()) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO agent_memory (user_id, memory_key, memory_value, source)
                VALUES (?, ?, ?, 'auto')
                ON CONFLICT (user_id, memory_key)
                DO UPDATE SET memory_value = EXCLUDED.memory_value, updated_time = CURRENT_TIMESTAMP
                """,
                userId, key.trim(), value == null ? "" : value.trim()
        );
    }
}
