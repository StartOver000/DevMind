package com.devmind.tool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具授权仓储：管理员把动态接口工具授权给成员/团队（tool_grant 表）。
 * 用户可用 = 个人授权 ∪ 所属团队授权。
 */
@Repository
public class ToolGrantRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolGrantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void grant(Long tenantId, String subjectType, Long subjectId, Long toolId, Long grantedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO tool_grant (tenant_id, subject_type, subject_id, tool_id, granted_by)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, subject_type, subject_id, tool_id) DO NOTHING
                """,
                tenantId, subjectType, subjectId, toolId, grantedBy
        );
    }

    public void revoke(Long tenantId, String subjectType, Long subjectId, Long toolId) {
        jdbcTemplate.update(
                "DELETE FROM tool_grant WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? AND tool_id = ?",
                tenantId, subjectType, subjectId, toolId
        );
    }

    /** 用户可用的工具 id 集合：个人授权 + 所属团队授权 */
    public Set<Long> findToolIdsForUser(Long tenantId, Long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT tool_id FROM tool_grant
                WHERE tenant_id = ?
                  AND ((subject_type = 'user' AND subject_id = ?)
                    OR (subject_type = 'team' AND subject_id IN (
                          SELECT team_id FROM team_member WHERE user_id = ?
                        )))
                """,
                Long.class,
                tenantId, userId, userId
        );
        return new HashSet<>(ids);
    }

    /** 某用户是否被授权某工具 */
    public boolean hasGrant(Long tenantId, Long userId, Long toolId) {
        Set<Long> granted = findToolIdsForUser(tenantId, userId);
        return granted.contains(toolId);
    }
}
