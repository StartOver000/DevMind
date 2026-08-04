package com.devmind.document;

import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.document.dto.DocumentUploadResponse;
import com.devmind.knowledge.KnowledgeBase;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.retrieval.ChunkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ChunkRepository chunkRepository;

    @Mock
    private DocumentTaskRepository taskRepository;

    @Mock
    private DocumentTaskService taskService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DocumentVersionRepository versionRepository;

    @Test
    void duplicateUploadReturnsExistingDocumentWithoutEmbedding() {
        DevMindProperties properties = new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        DocumentService service = new DocumentService(
                knowledgeBaseService,
                documentRepository,
                chunkRepository,
                taskRepository,
                taskService,
                auditLogService,
                versionRepository,
                properties
        );
        KnowledgeBase knowledgeBase = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        Document existing = new Document(
                10L, 1L, "a.md", "markdown", 5L, "path", "hash", "COMPLETED", null, null, null, null, Map.of()
        );
        when(knowledgeBaseService.requireEnabledKnowledgeBaseAccess(1L, 1L)).thenReturn(knowledgeBase);
        when(documentRepository.findByKbIdAndHash(eq(1L), anyString())).thenReturn(Optional.of(existing));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.md",
                "text/markdown",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        DocumentUploadResponse response = service.upload(1L, file, null, 1L);

        assertThat(response.duplicate()).isTrue();
        assertThat(response.id()).isEqualTo(10L);
        verify(taskService, never()).submitTask(anyLong());
    }

    @Test
    void rejectsUnsupportedFileType() {
        DevMindProperties properties = new DevMindProperties(
                "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        DocumentService service = new DocumentService(
                knowledgeBaseService,
                documentRepository,
                chunkRepository,
                taskRepository,
                taskService,
                auditLogService,
                versionRepository,
                properties
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.upload(1L, file, null, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_SUPPORTED);
    }

    @Test
    void deletedDocumentCanBeReuploaded(@TempDir Path tempDir) {
        DevMindProperties properties = new DevMindProperties(
                "mock", tempDir.toString(), 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
                4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
                0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true
        );
        DocumentService service = new DocumentService(
                knowledgeBaseService,
                documentRepository,
                chunkRepository,
                taskRepository,
                taskService,
                auditLogService,
                versionRepository,
                properties
        );
        KnowledgeBase knowledgeBase = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        Document deleted = new Document(
                10L, 1L, "a.md", "markdown", 5L, "old-path", "hash", "DELETED", null, null, null, null, Map.of()
        );
        when(knowledgeBaseService.requireEnabledKnowledgeBaseAccess(1L, 1L)).thenReturn(knowledgeBase);
        when(documentRepository.findByKbIdAndHash(eq(1L), anyString())).thenReturn(Optional.of(deleted));
        when(taskRepository.create(10L, 3)).thenReturn(99L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "a.md",
                "text/markdown",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        DocumentUploadResponse response = service.upload(1L, file, null, 1L);

        assertThat(response.duplicate()).isFalse();
        assertThat(response.status()).isEqualTo("UPLOADED");
        assertThat(response.taskId()).isEqualTo(99L);
        verify(documentRepository).resetForReupload(eq(10L), eq("a.md"), eq(5L));
        verify(taskService).submitTask(99L);
    }
}
