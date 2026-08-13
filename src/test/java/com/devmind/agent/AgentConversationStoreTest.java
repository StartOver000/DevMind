package com.devmind.agent;

import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.common.ApiException;
import com.devmind.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
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
 * P2 拆分后的会话存储独立单测：权限校验、会话复用/创建、消息与轨迹持久化。
 */
@ExtendWith(MockitoExtension.class)
class AgentConversationStoreTest {

    @Mock
    private AgentConversationRepository conversationRepository;
    @Mock
    private UserService userService;

    private AgentConversationStore store;

    @BeforeEach
    void setUp() {
        store = new AgentConversationStore(conversationRepository, userService);
    }

    // ---------- 历史查询（权限校验） ----------

    @Test
    void messagesRejectsConversationNotOwned() {
        when(conversationRepository.existsForUser(10L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> store.messages(10L, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void messagesReturnsHistoryForOwnedConversation() {
        when(conversationRepository.existsForUser(10L, 1L)).thenReturn(true);
        when(conversationRepository.listMessages(10L))
                .thenReturn(List.of(new AgentMessage("user", "hi", OffsetDateTime.now())));

        assertThat(store.messages(10L, 1L)).hasSize(1);
    }

    @Test
    void traceReturnsToolTraces() {
        when(conversationRepository.existsForUser(10L, 1L)).thenReturn(true);
        when(conversationRepository.listTraces(10L))
                .thenReturn(List.of(new ToolTraceItem("kb_search", "{}", true, 3L)));

        assertThat(store.trace(10L, 1L)).hasSize(1);
    }

    // ---------- 会话创建/复用 ----------

    @Test
    void resolveConversationReusesExistingId() {
        when(conversationRepository.existsForUser(10L, 1L)).thenReturn(true);

        Long id = store.resolveConversation(10L, "问题", 1L);

        assertThat(id).isEqualTo(10L);
        verify(conversationRepository, never()).create(anyLong(), anyString());
    }

    @Test
    void resolveConversationRejectsConversationNotOwned() {
        when(conversationRepository.existsForUser(10L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> store.resolveConversation(10L, "问题", 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("会话不存在");
        verify(conversationRepository, never()).listMessages(anyLong());
    }

    @Test
    void resolveConversationCreatesNewWithTitle() {
        String longQuestion = "超长问题" + "x".repeat(200);
        // 标题截断到 100 字符（4 个中文 + 96 个 x）
        when(conversationRepository.create(1L, "超长问题" + "x".repeat(96))).thenReturn(5L);

        Long id = store.resolveConversation(null, longQuestion, 1L);

        assertThat(id).isEqualTo(5L);
        verify(conversationRepository).create(1L, "超长问题" + "x".repeat(96));
    }

    // ---------- 持久化（失败静默） ----------

    @Test
    void saveMessagesSkipsInvalidConversation() {
        store.saveMessages(0L, "q", "a");

        verify(conversationRepository, never()).saveMessage(anyLong(), anyString(), anyString());
    }

    @Test
    void saveMessagesPersistsUserAndAssistant() {
        store.saveMessages(10L, "问题", "回答");

        verify(conversationRepository).saveMessage(10L, "user", "问题");
        verify(conversationRepository).saveMessage(10L, "assistant", "回答");
    }

    @Test
    void saveMessagesSilentOnRepositoryFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(conversationRepository).saveMessage(eq(10L), eq("user"), anyString());

        store.saveMessages(10L, "q", "a");

        // 不抛异常（静默），assistant 不再尝试
        verify(conversationRepository, never()).saveMessage(10L, "assistant", "a");
    }

    @Test
    void persistTraceSilentOnRepositoryFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(conversationRepository).saveTrace(eq(10L), anyString(), anyString(), eq(true), anyLong());

        store.persistTrace(10L, "kb_search", "{}", true, 3L);

        // 不抛异常（静默）
        verify(conversationRepository).saveTrace(10L, "kb_search", "{}", true, 3L);
    }
}
