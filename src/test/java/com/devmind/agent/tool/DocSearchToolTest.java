package com.devmind.agent.tool;

import com.devmind.document.DocumentRepository;
import com.devmind.document.dto.DocumentItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocSearchToolTest {

    @Test
    void returnsMatchedDocuments() throws Exception {
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.searchByName("索引", 10)).thenReturn(List.of(
                new DocumentItem(1L, "MySQL索引专题.md", "markdown", "COMPLETED", 5,
                        OffsetDateTime.parse("2026-08-01T10:00:00+00:00"), List.of("mysql")),
                new DocumentItem(2L, "索引优化指南.pdf", "pdf", "COMPLETED", 3,
                        OffsetDateTime.parse("2026-08-02T10:00:00+00:00"), List.of())
        ));

        DocSearchTool tool = new DocSearchTool(repository, new ObjectMapper());
        String output = tool.execute("{\"keyword\":\"索引\"}", 1L);

        assertThat(output).contains("MySQL索引专题.md").contains("索引优化指南.pdf");
        assertThat(output).contains("\"matched\":2");
        assertThat(output).doesNotContain("error");
    }

    @Test
    void returnsEmptyMessageWhenNoMatch() throws Exception {
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.searchByName("不存在", 10)).thenReturn(List.of());

        DocSearchTool tool = new DocSearchTool(repository, new ObjectMapper());
        String output = tool.execute("{\"keyword\":\"不存在\"}", 1L);

        assertThat(output).contains("\"matched\": 0");
        assertThat(output).contains("不存在");
    }

    @Test
    void returnsErrorWhenKeywordMissing() throws Exception {
        DocumentRepository repository = mock(DocumentRepository.class);

        DocSearchTool tool = new DocSearchTool(repository, new ObjectMapper());
        String output = tool.execute("{}", 1L);

        assertThat(output).contains("error").contains("keyword");
    }

    @Test
    void clampsLimitTo20() throws Exception {
        DocumentRepository repository = mock(DocumentRepository.class);
        when(repository.searchByName("x", 20)).thenReturn(List.of());

        DocSearchTool tool = new DocSearchTool(repository, new ObjectMapper());
        String output = tool.execute("{\"keyword\":\"x\",\"limit\":999}", 1L);

        // 不抛异常即通过；limit 被截到 20
        assertThat(output).isNotNull();
        // 验证传给 repository 的是 20 而非 999
        org.mockito.Mockito.verify(repository).searchByName("x", 20);
    }
}
