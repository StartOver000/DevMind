package com.devmind.agent.tool;

import com.devmind.document.DocumentService;
import com.devmind.document.dto.DocumentItem;
import com.devmind.document.dto.DocumentListResponse;
import com.devmind.knowledge.KnowledgeBaseItem;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocListToolTest {

    @Test
    void returnsDocumentListForDefaultKnowledgeBase() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        when(knowledgeBaseService.list(1L)).thenReturn(new KnowledgeBaseListResponse(
                List.of(new KnowledgeBaseItem(1L, "kb", "ENABLED", 2L, null))
        ));
        when(documentService.list(eq(1L), any(), anyInt(), anyInt(), any())).thenReturn(new DocumentListResponse(
                List.of(
                        new DocumentItem(1L, "a.md", "markdown", "COMPLETED", 5,
                                OffsetDateTime.parse("2026-08-01T10:00:00+00:00"), List.of("mysql")),
                        new DocumentItem(2L, "b.md", "markdown", "COMPLETED", 3,
                                OffsetDateTime.parse("2026-08-02T10:00:00+00:00"), List.of())
                ),
                1, 100, 2
        ));

        DocListTool tool = new DocListTool(documentService, knowledgeBaseService, new ObjectMapper());
        String output = tool.execute("{}", 1L);

        assertThat(output).contains("a.md").contains("COMPLETED").contains("chunkCount").contains("b.md");
        assertThat(output).doesNotContain("error");
    }

    @Test
    void returnsErrorWhenNoAccessibleKnowledgeBase() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        when(knowledgeBaseService.list(1L)).thenReturn(new KnowledgeBaseListResponse(List.of()));

        DocListTool tool = new DocListTool(documentService, knowledgeBaseService, new ObjectMapper());
        String output = tool.execute("{}", 1L);

        assertThat(output).contains("error");
    }
}
