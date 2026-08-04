package com.devmind.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AuthTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Long userId, String token, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                INSERT INTO auth_token (user_id, token, expires_at, created_time)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, userId, token, Timestamp.from(expiresAt.toInstant()));
    }

    public Optional<AuthToken> findByToken(String token) {
        List<AuthToken> result = jdbcTemplate.query("""
                SELECT id, user_id, token, expires_at, created_time
                FROM auth_token
                WHERE token = ?
                """, (rs, rowNum) -> new AuthToken(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("token"),
                toOffset(rs.getTimestamp("expires_at")),
                toOffset(rs.getTimestamp("created_time"))
        ), token);
        return result.stream().findFirst();
    }

    public void deleteByToken(String token) {
        jdbcTemplate.update("DELETE FROM auth_token WHERE token = ?", token);
    }

    public void extendExpiry(String token, OffsetDateTime newExpiresAt) {
        jdbcTemplate.update(
                "UPDATE auth_token SET expires_at = ? WHERE token = ?",
                Timestamp.from(newExpiresAt.toInstant()),
                token
        );
    }

    public void deleteExpired(OffsetDateTime now) {
        jdbcTemplate.update(
                "DELETE FROM auth_token WHERE expires_at < ?",
                Timestamp.from(now.toInstant())
        );
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
