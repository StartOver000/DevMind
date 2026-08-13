package com.devmind.agent;

import com.devmind.agent.dto.MemoryItem;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 拆分后的记忆管理独立单测：CRUD 权限校验 + 会话后自动提取。
 */
@ExtendWith(MockitoExtension.class)
class AgentMemoryManagerTest {

    @Mock
    private AgentMemoryRepository memoryRepository;
    @Mock
    private ChatRouter chatRouter;
    @Mock
    private UserService userService;

    private AgentMemoryManager manager;

    @BeforeEach
    void setUp() {
        manager = new AgentMemoryManager(memoryRepository, chatRouter, userService);
    }

    // ---------- CRUD ----------

    @Test
    void memoryReturnsUserItems() {
        when(memoryRepository.listByUser(1L)).thenReturn(
                List.of(new MemoryItem(1L, "stack", "java", "auto", null, null)));

        assertThat(manager.memory(1L)).hasSize(1);
        verify(userService).requireUser(1L);
    }

    @Test
    void updateMemoryReplacesAll() {
        List<MemoryItem> items = List.of(new MemoryItem(1L, "stack", "java", "manual", null, null));

        manager.updateMemory(new MemoryUpdateRequest(items), 1L);

        verify(memoryRepository).replaceAll(eq(1L), eq(items));
    }

    @Test
    void deleteMemoryWithInvalidIdThrows() {
        assertThatThrownBy(() -> manager.deleteMemory(0L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("记忆 ID");
        verify(memoryRepository, never()).deleteById(anyLong(), anyLong());
    }

    @Test
    void deleteMemoryNonexistentThrows() {
        when(memoryRepository.deleteById(1L, 99L)).thenReturn(0);

        assertThatThrownBy(() -> manager.deleteMemory(99L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不存在或无权删除");
    }

    @Test
    void deleteMemorySucceeds() {
        when(memoryRepository.deleteById(1L, 7L)).thenReturn(1);

        manager.deleteMemory(7L, 1L);

        verify(memoryRepository).deleteById(1L, 7L);
    }

    // ---------- extractMemory ----------

    @Test
    void extractMemoryParsesKeyValueAndUpserts() {
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("语言: java\n风格: 简洁", "mock", 0, 0));

        manager.extractMemory(1L, "问题", "回答");

        verify(memoryRepository).upsert(1L, "语言", "java");
        verify(memoryRepository).upsert(1L, "风格", "简洁");
    }

    @Test
    void extractMemorySkipsMalformedLines() {
        when(chatRouter.chat(anyString(), anyString()))
                .thenReturn(new AiModelGateway.ChatResult("没有冒号的行\nk: v", "mock", 0, 0));

        manager.extractMemory(1L, "q", "a");

        verify(memoryRepository).upsert(1L, "k", "v");
        verify(memoryRepository, never()).upsert(eq(1L), eq("没有冒号的行"), anyString());
    }

    @Test
    void extractMemorySilentWhenModelUnavailable() {
        when(chatRouter.chat(anyString(), anyString())).thenThrow(new RuntimeException("限流"));

        manager.extractMemory(1L, "q", "a");

        verify(memoryRepository, never()).upsert(anyLong(), anyString(), anyString());
    }
}
