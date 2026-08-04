package com.devmind.document;

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
public class DocumentTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long create(Long documentId, int maxRetries) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO document_task
                        (document_id, status, retry_count, max_retries, error_message, created_time, updated_time)
                    VALUES (?, 'PENDING', 0, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            ps.setLong(1, documentId);
            ps.setInt(2, maxRetries);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void upsert(Long documentId, int maxRetries) {
        jdbcTemplate.update("""
                INSERT INTO document_task
                    (document_id, status, retry_count, max_retries, error_message, created_time, updated_time)
                VALUES (?, 'PENDING', 0, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id) DO UPDATE SET
                    status = 'PENDING',
                    retry_count = 0,
                    max_retries = EXCLUDED.max_retries,
                    error_message = NULL,
                    updated_time = CURRENT_TIMESTAMP
                """, documentId, maxRetries);
    }

    public Optional<DocumentTask> findById(Long id) {
        List<DocumentTask> result = jdbcTemplate.query("""
                SELECT id, document_id, status, retry_count, max_retries, error_message, created_time, updated_time
                FROM document_task
                WHERE id = ?
                """, (rs, rowNum) -> new DocumentTask(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                rs.getInt("max_retries"),
                rs.getString("error_message"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        ), id);
        return result.stream().findFirst();
    }

    public Optional<DocumentTask> findByDocumentId(Long documentId) {
        List<DocumentTask> result = jdbcTemplate.query("""
                SELECT id, document_id, status, retry_count, max_retries, error_message, created_time, updated_time
                FROM document_task
                WHERE document_id = ?
                """, (rs, rowNum) -> new DocumentTask(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getString("status"),
                rs.getInt("retry_count"),
                rs.getInt("max_retries"),
                rs.getString("error_message"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time"))
        ), documentId);
        return result.stream().findFirst();
    }

    public boolean claimForProcessing(Long id) {
        return jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'PROCESSING', error_message = NULL, updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING'
                """, id) > 0;
    }

    public void markSucceeded(Long id) {
        jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'SUCCEEDED', error_message = NULL, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, id);
    }

    public void markFailedForRetry(Long id, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'PENDING', retry_count = retry_count + 1,
                    error_message = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, errorMessage, id);
    }

    public void markFailedPermanent(Long id, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'FAILED', retry_count = retry_count + 1,
                    error_message = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, errorMessage, id);
    }

    public void cancelByDocument(Long documentId) {
        jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'FAILED', error_message = '文档已删除', updated_time = CURRENT_TIMESTAMP
                WHERE document_id = ? AND status IN ('PENDING', 'PROCESSING')
                """, documentId);
    }

    public List<Long> findPendingIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM document_task WHERE status = 'PENDING' ORDER BY id",
                Long.class
        );
    }

    public List<Long> findStuckProcessingIds(OffsetDateTime before) {
        return jdbcTemplate.queryForList("""
                SELECT id FROM document_task
                WHERE status = 'PROCESSING' AND updated_time < ?
                ORDER BY id
                """, Long.class, Timestamp.from(before.toInstant()));
    }

    public void resetToPending(Long id) {
        jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'PENDING', updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING'
                """, id);
    }

    public int resetAllProcessingToPending() {
        return jdbcTemplate.update("""
                UPDATE document_task
                SET status = 'PENDING', updated_time = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING'
                """);
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
