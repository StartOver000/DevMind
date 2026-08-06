package com.devmind.audit;

import com.devmind.audit.ToolCallLogRepository.ToolCallLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolCallLogRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ToolCallLogRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ToolCallLogRepository(jdbcTemplate);
    }

    @Test
    void insertPassesAllFields() {
        ToolCallLog log = new ToolCallLog(1L, 2L, "kb_search", "builtin", "agent", null, "success", 12, null);

        repository.insert(log);

        verify(jdbcTemplate).update(anyString(),
                eq(1L), eq(2L), eq("kb_search"), eq("builtin"), eq("agent"),
                isNull(), eq("success"), eq(12L), isNull());
    }

    @Test
    void statsWithoutUserHasNoUserFilter() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        List<Map<String, Object>> rows = repository.stats(1L, null, 7);

        assertThat(rows).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("GROUP BY tool_name").doesNotContain("user_id = ?");
    }

    @Test
    void statsWithUserAddsUserFilter() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        repository.stats(1L, 2L, 7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("AND user_id = ?");
    }

    @Test
    void logsCapLimitAt500() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        repository.logs(1L, null, 7, 9999);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), args.capture());
        assertThat(args.getValue()[2]).isEqualTo(500);
    }
}
