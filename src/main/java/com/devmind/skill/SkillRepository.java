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
import java.util.Map;

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
     * sort：hot（按命中次数降序）/ zombie（命中最少在前）/ 默认（创建时间降序）。
     */
    public List<Skill> listVisible(Long tenantId, Long userId, String scope, String sort, Integer limit) {
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
        if ("hot".equals(sort)) {
            sql.append(" ORDER BY hit_count DESC, created_time DESC, id DESC");
        } else if ("zombie".equals(sort)) {
            sql.append(" ORDER BY hit_count ASC, created_time DESC, id DESC");
        } else {
            sql.append(" ORDER BY created_time DESC, id DESC");
        }
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ?");
            params.add(limit);
        }
        return jdbcTemplate.query(sql.toString(), this::map, params.toArray());
    }

    /**
     * 技能健康度统计（Guide-55 高优先级：热门/僵尸一目了然）：
     * total 可见技能总数、enabled 启用数、hitTotal 命中总数、hot 热门 Top5、zombie 僵尸技能（启用但从未命中）。
     */
    public Map<String, Object> stats(Long tenantId, Long userId) {
        Map<String, Object> result = new java.util.HashMap<>();
        // 总数与命中总数
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        StringBuilder where = new StringBuilder("WHERE tenant_id = ? AND (scope = 'team' OR created_by = ?)");
        params.add(userId);
        Map<String, Object> agg = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(SUM(hit_count), 0) AS hit_total FROM skill " + where,
                params.toArray());
        result.put("total", ((Number) agg.get("total")).longValue());
        result.put("hitTotal", ((Number) agg.get("hit_total")).longValue());
        // 启用数
        Long enabled = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill WHERE tenant_id = ? AND enabled = TRUE AND (scope = 'team' OR created_by = ?)",
                Long.class, tenantId, userId);
        result.put("enabled", enabled == null ? 0L : enabled);
        // 热门 Top5
        result.put("hot", listVisible(tenantId, userId, "all", "hot", 5));
        // 僵尸：启用但从未命中（前 10）
        result.put("zombie", listVisible(tenantId, userId, "all", "zombie", 10).stream()
                .filter(s -> s.enabled() && s.hitCount() == 0)
                .toList());
        return result;
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
