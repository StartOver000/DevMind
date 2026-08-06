package com.devmind.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("null")
class ToolGrantRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ToolGrantRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ToolGrantRepository(jdbcTemplate);
    }

    @Test
    void grantInsertsWithConflictIgnore() {
        repository.grant(1L, "user", 2L, 5L, 1L);

        verify(jdbcTemplate).update(
                anyString(), eq(1L), eq("user"), eq(2L), eq(5L), eq(1L)
        );
    }

    @Test
    void revokeDeletes() {
        repository.revoke(1L, "user", 2L, 5L);

        verify(jdbcTemplate).update(anyString(), eq(1L), eq("user"), eq(2L), eq(5L));
    }

    @Test
    void findToolIdsForUserReturnsDirectAndTeamGrants() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(5L, 7L, 7L));

        Set<Long> ids = repository.findToolIdsForUser(1L, 2L);

        assertThat(ids).containsExactlyInAnyOrder(5L, 7L);
    }

    @Test
    void hasGrantTrueWhenGranted() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), anyLong(), anyLong(), anyLong()))
                .thenReturn(List.of(5L));

        assertThat(repository.hasGrant(1L, 2L, 5L)).isTrue();
        assertThat(repository.hasGrant(1L, 2L, 9L)).isFalse();
    }
}
