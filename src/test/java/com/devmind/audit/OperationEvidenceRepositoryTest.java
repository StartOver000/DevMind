package com.devmind.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationEvidenceRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private OperationEvidenceRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new OperationEvidenceRepository(jdbcTemplate);
    }

    @Test
    void insertPassesAllColumns() {
        OperationEvidence evidence = new OperationEvidence(1L, 100L, 999L, "APPROVE", "财务审批",
                "7", "同意", "before", null, "prev", "record");

        repository.insert(evidence);

        verify(jdbcTemplate).update(anyString(),
                eq(1L), eq(100L), eq(999L), eq("APPROVE"), eq("财务审批"),
                eq("7"), eq("同意"), eq("before"), eq(null), eq("prev"), eq("record"));
    }

    @Test
    void findLastHashReturnsNullWhenNoEvidence() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of());

        assertThat(repository.findLastHash(1L, 100L)).isNull();
    }

    @Test
    void findLastHashReturnsNewestRecordHash() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of("hash-2"));

        assertThat(repository.findLastHash(1L, 100L)).isEqualTo("hash-2");
    }

    @Test
    void findLastHashOrdersByIdDescending() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of());

        repository.findLastHash(1L, 100L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class), eq(1L), eq(100L));
        assertThat(sql.getValue()).contains("ORDER BY id DESC").contains("LIMIT 1");
    }
}
