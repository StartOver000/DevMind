package com.devmind.document;

import com.devmind.document.dto.DocumentItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DocumentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<Document> findById(Long id) {
        List<Document> result = jdbcTemplate.query("""
                SELECT id, knowledge_base_id, file_name, file_type, file_size, file_path,
                       content_hash, status, error_message, created_by, created_time, updated_time, metadata
                FROM document
                WHERE id = ?
                """, (rs, rowNum) -> new Document(
                rs.getLong("id"),
                rs.getLong("knowledge_base_id"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getLong("file_size"),
                rs.getString("file_path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                rs.getString("error_message"),
                (Long) rs.getObject("created_by"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time")),
                readMetadata(rs.getString("metadata"))
        ), id);
        return result.stream().findFirst();
    }

    public Optional<Document> findByKbIdAndHash(Long knowledgeBaseId, String contentHash) {
        List<Document> result = jdbcTemplate.query("""
                SELECT id, knowledge_base_id, file_name, file_type, file_size, file_path,
                       content_hash, status, error_message, created_by, created_time, updated_time, metadata
                FROM document
                WHERE knowledge_base_id = ? AND content_hash = ?
                """, (rs, rowNum) -> new Document(
                rs.getLong("id"),
                rs.getLong("knowledge_base_id"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getLong("file_size"),
                rs.getString("file_path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                rs.getString("error_message"),
                (Long) rs.getObject("created_by"),
                toOffset(rs.getTimestamp("created_time")),
                toOffset(rs.getTimestamp("updated_time")),
                readMetadata(rs.getString("metadata"))
        ), knowledgeBaseId, contentHash);
        return result.stream().findFirst();
    }

    public Long insert(Document document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO document (knowledge_base_id, file_name, file_type, file_size,
                                          file_path, content_hash, status, error_message, created_by,
                                          created_time, updated_time, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?::jsonb)
                    """, new String[]{"id"});
            ps.setLong(1, document.knowledgeBaseId());
            ps.setString(2, document.fileName());
            ps.setString(3, document.fileType());
            ps.setLong(4, document.fileSize());
            ps.setString(5, document.filePath());
            ps.setString(6, document.contentHash());
            ps.setString(7, document.status());
            ps.setString(8, document.errorMessage());
            if (document.createdBy() == null) {
                ps.setObject(9, null);
            } else {
                ps.setLong(9, document.createdBy());
            }
            ps.setString(10, toJson(document.metadata() == null ? Map.of() : document.metadata()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void updateStatus(Long id, String status, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE document
                SET status = ?, error_message = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, status, errorMessage, id);
    }

    public void updateFile(
            Long id,
            String fileName,
            String fileType,
            Long fileSize,
            String filePath,
            String contentHash
    ) {
        jdbcTemplate.update("""
                UPDATE document
                SET file_name = ?, file_type = ?, file_size = ?, file_path = ?, content_hash = ?,
                    status = 'UPLOADED', error_message = NULL, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, fileName, fileType, fileSize, filePath, contentHash, id);
    }

    public int findVersion(Long id) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM document WHERE id = ?",
                Integer.class,
                id
        );
        return version == null ? 1 : version;
    }

    public void setVersion(Long id, int version) {
        jdbcTemplate.update(
                "UPDATE document SET version = ?, updated_time = CURRENT_TIMESTAMP WHERE id = ?",
                version,
                id
        );
    }

    public void resetForReupload(Long id, String fileName, Long fileSize) {
        jdbcTemplate.update("""
                UPDATE document
                SET status = 'UPLOADED', error_message = NULL,
                    file_name = ?, file_size = ?, updated_time = CURRENT_TIMESTAMP
                WHERE id = ?
                """, fileName, fileSize, id);
    }

    public boolean markDeleted(Long id) {
        return jdbcTemplate.update("""
                UPDATE document
                SET status = 'DELETED', updated_time = CURRENT_TIMESTAMP
                WHERE id = ? AND status <> 'DELETED'
                """, id) > 0;
    }

    public List<DocumentItem> listByKb(Long knowledgeBaseId, String status, int page, int pageSize) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.id, d.file_name, d.file_type, d.status, d.created_time, d.metadata,
                       (SELECT COUNT(*) FROM document_chunk c WHERE c.document_id = d.id) AS chunk_count
                FROM document d
                WHERE d.knowledge_base_id = ? AND d.status <> 'DELETED'
                """);
        List<Object> params = new ArrayList<>();
        params.add(knowledgeBaseId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY d.created_time DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((long) (page - 1) * pageSize);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new DocumentItem(
                rs.getLong("id"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                toOffset(rs.getTimestamp("created_time")),
                extractTags(readMetadata(rs.getString("metadata")))
        ), params.toArray());
    }

    public long countByKb(Long knowledgeBaseId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM document
                WHERE knowledge_base_id = ? AND status <> 'DELETED'
                """);
        List<Object> params = new ArrayList<>();
        params.add(knowledgeBaseId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /**
     * 按文件名模糊检索（C2）：跨知识库按名称/标题匹配文档。
     * 返回文件名、类型、状态、块数、上传时间（不含内容，内容检索用 kb_search）。
     */
    public List<DocumentItem> searchByName(String keyword, int limit) {
        String sql = """
                SELECT d.id, d.file_name, d.file_type, d.status, d.created_time, d.metadata,
                       (SELECT COUNT(*) FROM document_chunk c WHERE c.document_id = d.id) AS chunk_count
                FROM document d
                WHERE d.status <> 'DELETED'
                  AND d.file_name ILIKE ?
                ORDER BY d.created_time DESC
                LIMIT ?
                """;
        String pattern = "%" + keyword + "%";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new DocumentItem(
                rs.getLong("id"),
                rs.getString("file_name"),
                rs.getString("file_type"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                toOffset(rs.getTimestamp("created_time")),
                extractTags(readMetadata(rs.getString("metadata")))
        ), pattern, limit);
    }

    public int countChunks(Long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE document_id = ?",
                Integer.class,
                documentId
        );
        return count == null ? 0 : count;
    }

    private OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private List<String> extractTags(Map<String, Object> metadata) {
        if (metadata == null || metadata.get("tags") == null) {
            return List.of();
        }
        Object raw = metadata.get("tags");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private Map<String, Object> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new IllegalStateException("metadata 序列化失败", ex);
        }
    }
}
