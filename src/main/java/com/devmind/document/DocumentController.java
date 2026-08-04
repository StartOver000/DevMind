package com.devmind.document;

import com.devmind.document.dto.BatchUploadResponse;
import com.devmind.document.dto.DeleteDocumentResponse;
import com.devmind.document.dto.DocumentDetailResponse;
import com.devmind.document.dto.DocumentListResponse;
import com.devmind.document.dto.DocumentUploadResponse;
import com.devmind.document.dto.DocumentVersionListResponse;
import com.devmind.document.dto.VersionCompareResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public DocumentUploadResponse upload(
            @PathVariable Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String tags,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.upload(knowledgeBaseId, file, tags, userId);
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/batch")
    public BatchUploadResponse batchUpload(
            @PathVariable Long knowledgeBaseId,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) String tags,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.batchUpload(knowledgeBaseId, files, tags, userId);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/export")
    public ResponseEntity<byte[]> export(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        byte[] data = documentService.exportKnowledgeBase(knowledgeBaseId, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=kb-" + knowledgeBaseId + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public DocumentListResponse list(
            @PathVariable Long knowledgeBaseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.list(knowledgeBaseId, status, page, pageSize, userId);
    }

    @GetMapping("/documents/{documentId}")
    public DocumentDetailResponse detail(
            @PathVariable Long documentId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.detail(documentId, userId);
    }

    @GetMapping("/documents/{documentId}/content")
    public String preview(
            @PathVariable Long documentId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.previewContent(documentId, userId);
    }

    @GetMapping("/documents/{documentId}/compare")
    public VersionCompareResponse compare(
            @PathVariable Long documentId,
            @RequestParam int from,
            @RequestParam int to,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.compareVersions(documentId, from, to, userId);
    }

    @DeleteMapping("/documents/{documentId}")
    public DeleteDocumentResponse delete(
            @PathVariable Long documentId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.delete(documentId, userId);
    }

    @PostMapping("/documents/{documentId}/versions")
    public DocumentUploadResponse updateDocument(
            @PathVariable Long documentId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.updateDocument(documentId, file, userId);
    }

    @GetMapping("/documents/{documentId}/versions")
    public DocumentVersionListResponse listVersions(
            @PathVariable Long documentId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.listVersions(documentId, userId);
    }

    @PostMapping("/documents/{documentId}/rollback/{version}")
    public DocumentUploadResponse rollback(
            @PathVariable Long documentId,
            @PathVariable int version,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return documentService.rollback(documentId, version, userId);
    }
}
