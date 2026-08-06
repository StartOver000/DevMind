package com.devmind.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 技能仓储（skill 表） */
@Repository
public class SkillRepository {

    private final JdbcTemplate jdbcTemplate;

    public SkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final String COLUMNS = """
            id, tenant_id, scope, name, description, apply_to, content, "references", source,
            source_workflow_id, enabled, hit_count, created_by, created_time::text
            """;

    private Skill map(ResultSet rs, int rowNum) throws SQLException {
        return new Skill(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("scope"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("apply_to"),
                rs.getString("content"),
                rs.getString("references"),
                rs.getString("source"),
                (Long) rs.getObject("source_workflow_id"),
                rs.getBoolean("enabled"),
                rs.getLong("hit_count"),
                (Long) rs.getObject("created_by"),
                rs.getString("created_time")
        );
    }

    /**
     * 当前用户可见的技能：团队技能（同租户）+ 本人 personal。
     * scope 过滤：team / personal / all（默认 all）。
     */
    public List<Skill> listVisible(Long tenantId, Long userId, String scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM skill WHERE tenant_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if ("team".equals(scope)) {
            sql.append(" AND scope = 'team'");
        } else if ("personal".equals(scope)) {
            sql.append(" AND scope = 'personal' AND created_by = ?");
            params.add(userId);
        } else {
            sql.append(" AND (scope = 'team' OR created_by = ?)");
            params.add(userId);
        }
        sql.append(" ORDER BY created_time DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), this::map, params.toArray());
    }

    /** 加载注入 Agent 的启用技能：团队技能 + 本人 personal（Guide-51 P1 关键词粗筛由调用方做） */
    public List<Skill> listEnabledForUser(Long tenantId, Long userId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM skill WHERE tenant_id = ? AND enabled = TRUE "
                        + "AND (scope = 'team' OR (scope = 'personal' AND created_by = ?)) "
                        + "ORDER BY scope DESC, updated_time DESC",
                this::map, tenantId, userId
        );
    }

    public Skill findById(Long tenantId, Long id) {
        List<Skill> rows = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM skill WHERE tenant_id = ? AND id = ?",
                this::map, tenantId, id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Skill insert(Skill skill) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO skill (tenant_id, scope, name, description, apply_to, content,
                                       "references", source, source_workflow_id, enabled, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, skill.tenantId());
            ps.setString(2, skill.scope());
            ps.setString(3, skill.name());
            ps.setString(4, skill.description() == null ? "" : skill.description());
            ps.setString(5, skill.applyTo() == null ? "" : skill.applyTo());
            ps.setString(6, skill.content());
            ps.setString(7, skill.references() == null || skill.references().isBlank() ? "[]" : skill.references());
            ps.setString(8, skill.source() == null ? "manual" : skill.source());
            if (skill.sourceWorkflowId() != null) {
                ps.setLong(9, skill.sourceWorkflowId());
            } else {
                ps.setNull(9, java.sql.Types.BIGINT);
            }
            ps.setBoolean(10, skill.enabled());
            ps.setLong(11, skill.createdBy());
            return ps;
        }, keyHolder);
        Long id = keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
        return new Skill(
                id, skill.tenantId(), skill.scope(), skill.name(), skill.description(),
                skill.applyTo(), skill.content(), skill.references(), skill.source(), skill.sourceWorkflowId(),
                skill.enabled(), 0L, skill.createdBy(), null
        );
    }

    public void update(Skill skill) {
        jdbcTemplate.update("""
                UPDATE skill SET scope = ?, name = ?, description = ?, apply_to = ?,
                                  content = ?, "references" = ?, enabled = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """, skill.scope(), skill.name(), skill.description(),
                skill.applyTo(), skill.content(),
                skill.references() == null || skill.references().isBlank() ? "[]" : skill.references(),
                skill.enabled(), skill.id(), skill.tenantId());
    }

    /** 仅更新技能内容（对话式修正，保留名称/触发词/统计） */
    public void updateContent(Long tenantId, Long id, String content) {
        jdbcTemplate.update("""
                UPDATE skill SET content = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """, content, id, tenantId);
    }

    /** 命中次数自增（Agent 注入时统计，用于发现僵尸/热门技能） */
    public void incrementHit(Long tenantId, Long id) {
        jdbcTemplate.update("""
                UPDATE skill SET hit_count = hit_count + 1
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId);
    }

    public void toggle(Long tenantId, Long id, boolean enabled) {
        jdbcTemplate.update("""
                UPDATE skill SET enabled = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """, enabled, id, tenantId);
    }

    public void delete(Long tenantId, Long id) {
        jdbcTemplate.update("DELETE FROM skill WHERE id = ? AND tenant_id = ?", id, tenantId);
    }
}
