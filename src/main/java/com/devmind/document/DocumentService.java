package com.devmind.document;

import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.common.HashUtils;
import com.devmind.config.DevMindProperties;
import com.devmind.document.dto.BatchUploadResponse;
import com.devmind.document.dto.DeleteDocumentResponse;
import com.devmind.document.dto.DocumentDetailResponse;
import com.devmind.document.dto.DocumentItem;
import com.devmind.document.dto.DocumentListResponse;
import com.devmind.document.dto.DocumentUploadResponse;
import com.devmind.document.dto.DocumentVersionListResponse;
import com.devmind.document.dto.DocumentVersionResponse;
import com.devmind.document.dto.VersionCompareResponse;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.retrieval.ChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DocumentService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final DocumentTaskRepository taskRepository;
    private final DocumentTaskService taskService;
    private final AuditLogService auditLogService;
    private final DocumentVersionRepository versionRepository;
    private final DevMindProperties properties;

    public DocumentService(
            KnowledgeBaseService knowledgeBaseService,
            DocumentRepository documentRepository,
            ChunkRepository chunkRepository,
            DocumentTaskRepository taskRepository,
            DocumentTaskService taskService,
            AuditLogService auditLogService,
            DocumentVersionRepository versionRepository,
            DevMindProperties properties
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.auditLogService = auditLogService;
        this.versionRepository = versionRepository;
        this.properties = properties;
    }

    public DocumentUploadResponse upload(Long knowledgeBaseId, MultipartFile file, String tags, Long userId) {
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        String fileName = StringUtils.cleanPath(Optional.ofNullable(file.getOriginalFilename()).orElse("unknown"));
        String extension = extension(fileName);
        if (!isAllowed(extension)) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "仅支持 Markdown 和 PDF 文件");
        }
        long maxSize = properties.maxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过大小限制");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }

        Map<String, Object> metadata = buildMetadata(tags);
        String contentHash = HashUtils.sha256(bytes);
        Optional<Document> existing = documentRepository.findByKbIdAndHash(knowledgeBaseId, contentHash);
        if (existing.isPresent()) {
            Document document = existing.get();
            if ("DELETED".equals(document.status())) {
                saveFile(knowledgeBaseId, contentHash, extension, bytes);
                documentRepository.resetForReupload(document.id(), fileName, (long) bytes.length);
                Long taskId = taskRepository.create(document.id(), properties.taskMaxRetries());
                taskService.submitTask(taskId);
                auditLogService.log(userId, "UPLOAD_DOCUMENT", "document", document.id(), fileName);
                return new DocumentUploadResponse(
                        document.id(),
                        knowledgeBaseId,
                        fileName,
                        "UPLOADED",
                        false,
                        taskId
                );
            }
            return new DocumentUploadResponse(
                    document.id(),
                    knowledgeBaseId,
                    document.fileName(),
                    document.status(),
                    true,
                    null
            );
        }

        String fileType = normalizeFileType(extension);
        String filePath = saveFile(knowledgeBaseId, contentHash, extension, bytes);
        Document document = new Document(
                null,
                knowledgeBaseId,
                fileName,
                fileType,
                (long) bytes.length,
                filePath,
                contentHash,
                "UPLOADED",
                null,
                null,
                null,
                null,
                metadata
        );
        Long documentId = documentRepository.insert(document);

        Long taskId;
        try {
            taskId = taskRepository.create(documentId, properties.taskMaxRetries());
        } catch (Exception ex) {
            documentRepository.updateStatus(documentId, "FAILED", "创建处理任务失败");
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "创建处理任务失败");
        }
        taskService.submitTask(taskId);
        auditLogService.log(userId, "UPLOAD_DOCUMENT", "document", documentId, fileName);
        return new DocumentUploadResponse(documentId, knowledgeBaseId, fileName, "UPLOADED", false, taskId);
    }

    public DocumentListResponse list(Long knowledgeBaseId, String status, int page, int pageSize, Long userId) {
        knowledgeBaseService.requireKnowledgeBaseAccess(knowledgeBaseId, userId);
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        List<DocumentItem> items = documentRepository.listByKb(
                knowledgeBaseId,
                status,
                safePage,
                safePageSize
        );
        long total = documentRepository.countByKb(knowledgeBaseId, status);
        return new DocumentListResponse(items, safePage, safePageSize, total);
    }

    public DocumentDetailResponse detail(Long documentId, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        return new DocumentDetailResponse(
                document.id(),
                document.knowledgeBaseId(),
                document.fileName(),
                document.fileType(),
                document.fileSize(),
                document.filePath(),
                document.contentHash(),
                document.status(),
                document.errorMessage(),
                chunkRepository.countByDocument(documentId),
                document.createdTime(),
                document.updatedTime()
        );
    }

    @Transactional
    public DeleteDocumentResponse delete(Long documentId, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        documentRepository.markDeleted(documentId);
        chunkRepository.deleteByDocument(documentId);
        taskRepository.cancelByDocument(documentId);
        auditLogService.log(userId, "DELETE_DOCUMENT", "document", documentId, document.fileName());
        return new DeleteDocumentResponse(documentId, "DELETED");
    }

    public DocumentUploadResponse updateDocument(Long documentId, MultipartFile file, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        String fileName = StringUtils.cleanPath(Optional.ofNullable(file.getOriginalFilename()).orElse("unknown"));
        String extension = extension(fileName);
        if (!isAllowed(extension)) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_SUPPORTED, "仅支持 Markdown 和 PDF 文件");
        }
        long maxSize = properties.maxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文件超过大小限制");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
        String contentHash = HashUtils.sha256(bytes);
        if (document.contentHash().equals(contentHash)) {
            return new DocumentUploadResponse(
                    document.id(),
                    document.knowledgeBaseId(),
                    document.fileName(),
                    document.status(),
                    true,
                    null
            );
        }
        String fileType = normalizeFileType(extension);
        String filePath = saveFile(document.knowledgeBaseId(), contentHash, extension, bytes);
        int currentVersion = documentRepository.findVersion(documentId);
        versionRepository.saveSnapshot(
                documentId,
                currentVersion,
                document.fileName(),
                document.fileType(),
                document.fileSize(),
                document.filePath(),
                document.contentHash(),
                userId
        );
        documentRepository.updateFile(documentId, fileName, fileType, (long) bytes.length, filePath, contentHash);
        documentRepository.setVersion(documentId, currentVersion + 1);
        return reprocess(
                documentId,
                document.knowledgeBaseId(),
                fileName,
                fileType,
                (long) bytes.length,
                filePath,
                contentHash,
                userId,
                "UPDATE_DOCUMENT"
        );
    }

    public DocumentUploadResponse rollback(Long documentId, int version, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        DocumentVersion target = versionRepository.find(documentId, version)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.DOCUMENT_VERSION_NOT_FOUND,
                        "文档版本不存在"
                ));
        int currentVersion = documentRepository.findVersion(documentId);
        versionRepository.saveSnapshot(
                documentId,
                currentVersion,
                document.fileName(),
                document.fileType(),
                document.fileSize(),
                document.filePath(),
                document.contentHash(),
                userId
        );
        documentRepository.updateFile(
                documentId,
                target.fileName(),
                target.fileType(),
                target.fileSize(),
                target.filePath(),
                target.contentHash()
        );
        documentRepository.setVersion(documentId, target.version());
        return reprocess(
                documentId,
                document.knowledgeBaseId(),
                target.fileName(),
                target.fileType(),
                target.fileSize(),
                target.filePath(),
                target.contentHash(),
                userId,
                "ROLLBACK_DOCUMENT"
        );
    }

    public DocumentVersionListResponse listVersions(Long documentId, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        int currentVersion = documentRepository.findVersion(documentId);
        List<DocumentVersionResponse> items = versionRepository.listByDocument(documentId).stream()
                .map(version -> new DocumentVersionResponse(
                        version.documentId(),
                        version.version(),
                        version.fileName(),
                        version.fileType(),
                        version.fileSize(),
                        version.contentHash(),
                        version.createdTime()
                ))
                .toList();
        return new DocumentVersionListResponse(currentVersion, items);
    }

    public BatchUploadResponse batchUpload(Long knowledgeBaseId, List<MultipartFile> files, String tags, Long userId) {
        List<DocumentUploadResponse> items = new ArrayList<>();
        int failed = 0;
        for (MultipartFile file : files) {
            try {
                items.add(upload(knowledgeBaseId, file, tags, userId));
            } catch (ApiException ex) {
                failed++;
                items.add(new DocumentUploadResponse(
                        null,
                        knowledgeBaseId,
                        file.getOriginalFilename(),
                        "FAILED",
                        false,
                        null
                ));
            }
        }
        return new BatchUploadResponse(files.size(), failed, items);
    }

    public String previewContent(Long documentId, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        if ("pdf".equalsIgnoreCase(document.fileType())) {
            return "（PDF 暂不支持在线文本预览）";
        }
        return readFileContent(document.filePath());
    }

    public VersionCompareResponse compareVersions(Long documentId, int fromVersion, int toVersion, Long userId) {
        Document document = requireDocument(documentId);
        knowledgeBaseService.requireKnowledgeBaseAccess(document.knowledgeBaseId(), userId);
        String fromContent = contentOfVersion(document, fromVersion);
        String toContent = contentOfVersion(document, toVersion);
        return new VersionCompareResponse(
                fromVersion,
                toVersion,
                fromContent,
                toContent
        );
    }

    private String contentOfVersion(Document document, int version) {
        int current = documentRepository.findVersion(document.id());
        if (version == current) {
            return readFileContent(document.filePath());
        }
        DocumentVersion snapshot = versionRepository.find(document.id(), version)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_VERSION_NOT_FOUND, "版本不存在: " + version));
        return readFileContent(snapshot.filePath());
    }

    public byte[] exportKnowledgeBase(Long knowledgeBaseId, Long userId) {
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        List<DocumentItem> documents = documentRepository.listByKb(knowledgeBaseId, null, 1, 1000);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (DocumentItem item : documents) {
                Document document = documentRepository.findById(item.id()).orElse(null);
                if (document == null || "DELETED".equals(document.status())) {
                    continue;
                }
                String content = readFileContent(document.filePath());
                zos.putNextEntry(new ZipEntry(document.fileName()));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "导出知识库失败");
        }
        return baos.toByteArray();
    }

    private String readFileContent(String filePath) {
        try {
            return Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "读取文件内容失败");
        }
    }

    private DocumentUploadResponse reprocess(
            Long documentId,
            Long knowledgeBaseId,
            String fileName,
            String fileType,
            Long fileSize,
            String filePath,
            String contentHash,
            Long userId,
            String action
    ) {
        chunkRepository.deleteByDocument(documentId);
        taskRepository.upsert(documentId, properties.taskMaxRetries());
        Long taskId = taskRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "创建处理任务失败"))
                .id();
        taskService.submitTask(taskId);
        auditLogService.log(userId, action, "document", documentId, fileName);
        return new DocumentUploadResponse(
                documentId,
                knowledgeBaseId,
                fileName,
                "UPLOADED",
                false,
                taskId
        );
    }

    private Document requireDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND, "文档不存在"));
    }

    private String saveFile(Long knowledgeBaseId, String contentHash, String extension, byte[] bytes) {
        try {
            Path directory = Paths.get(properties.storagePath(), String.valueOf(knowledgeBaseId));
            Path target = directory.resolve(contentHash + "." + extension);
            Files.createDirectories(directory);
            Files.write(target, bytes);
            return target.toAbsolutePath().normalize().toString().replace('\\', '/');
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "保存上传文件失败");
        }
    }

    private Map<String, Object> buildMetadata(String tags) {
        if (tags == null || tags.isBlank()) {
            return Map.of();
        }
        List<String> list = Arrays.stream(tags.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(20)
                .toList();
        return list.isEmpty() ? Map.of() : Map.of("tags", list);
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isAllowed(String extension) {
        return Arrays.stream(properties.allowedFileTypes().split(","))
                .map(String::trim)
                .anyMatch(extension::equals);
    }

    private String normalizeFileType(String extension) {
        if (extension.equals("md") || extension.equals("markdown")) {
            return "markdown";
        }
        if (extension.equals("pdf")) {
            return "pdf";
        }
        return extension;
    }

}
