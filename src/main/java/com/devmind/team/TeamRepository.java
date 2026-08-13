package com.devmind.team;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class TeamRepository {

    private final JdbcTemplate jdbcTemplate;

    public TeamRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long create(String name, String description, Long ownerId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO team (name, description, created_by, created_time, updated_time)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setLong(3, ownerId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<Team> findById(Long id) {
        List<Team> result = jdbcTemplate.query("""
                SELECT id, name, description, created_by, created_time, updated_time
                FROM team
                WHERE id = ?
                """, (rs, rowNum) -> toTeam(rs), id);
        return result.stream().findFirst();
    }

    public boolean existsByName(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team WHERE name = ?",
                Integer.class, name);
        return count != null && count > 0;
    }

    public List<Team> listByMember(Long userId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.name, t.description, t.created_by, t.created_time, t.updated_time
                FROM team t
                WHERE EXISTS (
                    SELECT 1 FROM team_member m WHERE m.team_id = t.id AND m.user_id = ?
                )
                ORDER BY t.created_time DESC, t.id DESC
                """, (rs, rowNum) -> toTeam(rs), userId);
    }

    public List<Team> listAll() {
        return jdbcTemplate.query("""
                SELECT id, name, description, created_by, created_time, updated_time
                FROM team
                ORDER BY created_time DESC, id DESC
                """, (rs, rowNum) -> toTeam(rs));
    }

    public void addMember(Long teamId, Long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO team_member (team_id, user_id, role, created_time)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (team_id, user_id) DO UPDATE SET role = EXCLUDED.role
                """, teamId, userId, role);
    }

    public boolean removeMember(Long teamId, Long userId) {
        return jdbcTemplate.update("""
                DELETE FROM team_member
                WHERE team_id = ? AND user_id = ? AND role <> 'OWNER'
                """, teamId, userId) > 0;
    }

    public boolean existsMember(Long teamId, Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM team_member WHERE team_id = ? AND user_id = ?
                """, Integer.class, teamId, userId);
        return count != null && count > 0;
    }

    public boolean isOwner(Long teamId, Long userId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM team_member
                WHERE team_id = ? AND user_id = ? AND role = 'OWNER'
                """, Integer.class, teamId, userId);
        return count != null && count > 0;
    }

    public List<TeamMemberView> listMembers(Long teamId) {
        return jdbcTemplate.query("""
                SELECT m.team_id, m.user_id, u.username, m.role, m.created_time
                FROM team_member m
                JOIN app_user u ON u.id = m.user_id
                WHERE m.team_id = ?
                ORDER BY m.created_time, m.user_id
                """, (rs, rowNum) -> new TeamMemberView(
                rs.getLong("team_id"),
                rs.getLong("user_id"),
                rs.getString("username"),
                rs.getString("role"),
                toOffset(rs.getTimestamp("created_time"))
        ), teamId);
    }

    public int countMembers(Long teamId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM team_member WHERE team_id = ?",
                Integer.class, teamId);
        return count == null ? 0 : count;
    }

    /** 删除团队（同时清理成员，避免孤儿 team_member 数据） */
    public void deleteMembers(Long teamId) {
        jdbcTemplate.update("DELETE FROM team_member WHERE team_id = ?", teamId);
    }

    public boolean delete(Long teamId) {
        return jdbcTemplate.update("DELETE FROM team WHERE id = ?", teamId) > 0;
    }

    private Team toTeam(ResultSet rs) throws SQLException {
        return new Team(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                (Long) rs.getObject("created_by"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        );
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
