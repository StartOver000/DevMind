package com.devmind.document;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveSnapshot(
            Long documentId,
            int version,
            String fileName,
            String fileType,
            Long fileSize,
            String filePath,
            String contentHash,
            Long createdBy
    ) {
        jdbcTemplate.update("""
                INSERT INTO document_version
                    (document_id, version, file_name, file_type, file_size, file_path, content_hash, created_by, created_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (document_id, version) DO UPDATE SET
                    file_name = EXCLUDED.file_name,
                    file_type = EXCLUDED.file_type,
                    file_size = EXCLUDED.file_size,
                    file_path = EXCLUDED.file_path,
                    content_hash = EXCLUDED.content_hash,
                    created_by = EXCLUDED.created_by
                """, documentId, version, fileName, fileType, fileSize, filePath, contentHash, createdBy);
    }

    public Optional<DocumentVersion> find(Long documentId, int version) {
        List<DocumentVersion> result = jdbcTemplate.query("""
                SELECT id, document_id, version, file_name, file_type, file_size, file_path, content_hash, created_time
                FROM document_version
                WHERE document_id = ? AND version = ?
                """, (rs, rowNum) -> new DocumentVersion(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getInt("version"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getLong("file_size"),
                rs.getString("file_path"),
                rs.getString("content_hash"),
                toOffset(rs.getTimestamp("created_time"))
        ), documentId, version);
        return result.stream().findFirst();
    }

    public List<DocumentVersion> listByDocument(Long documentId) {
        return jdbcTemplate.query("""
                SELECT id, document_id, version, file_name, file_type, file_size, file_path, content_hash, created_time
                FROM document_version
                WHERE document_id = ?
                ORDER BY version DESC
                """, (rs, rowNum) -> new DocumentVersion(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getInt("version"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getLong("file_size"),
                rs.getString("file_path"),
                rs.getString("content_hash"),
                toOffset(rs.getTimestamp("created_time"))
        ), documentId);
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
