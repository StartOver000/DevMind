package com.devmind.user;

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
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<User> findById(Long id) {
        List<User> result = jdbcTemplate.query("""
                SELECT id, tenant_id, username, display_name, role, created_time
                FROM app_user
                WHERE id = ?
                """, (rs, rowNum) -> new User(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                toOffset(rs.getTimestamp("created_time"))
        ), id);
        return result.stream().findFirst();
    }

    public Long create(String username, String displayName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO app_user (username, display_name, created_time)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, displayName);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Long createWithPassword(String username, String displayName, String passwordHash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO app_user (username, display_name, password_hash, created_time)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<User> findByUsername(String username) {
        List<User> result = jdbcTemplate.query("""
                SELECT id, tenant_id, username, display_name, role, created_time
                FROM app_user
                WHERE username = ?
                """, (rs, rowNum) -> new User(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                toOffset(rs.getTimestamp("created_time"))
        ), username);
        return result.stream().findFirst();
    }

    public String findPasswordHash(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?",
                String.class,
                id
        );
    }

    public List<User> list() {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, username, display_name, role, created_time
                FROM app_user
                ORDER BY id
                """, (rs, rowNum) -> new User(
                rs.getLong("id"),
                rs.getLong("tenant_id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                toOffset(rs.getTimestamp("created_time"))
        ));
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
