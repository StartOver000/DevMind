package com.devmind.knowledge;

import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.team.TeamService;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeBaseRepository repository;

    @Mock
    private UserService userService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private KnowledgeBaseMemberRepository memberRepository;

    @Mock
    private TeamService teamService;

    private KnowledgeBaseService service() {
        return new KnowledgeBaseService(repository, userService, auditLogService, memberRepository, teamService);
    }

    @Test
    void nonOwnerCannotDeleteKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));

        assertThatThrownBy(() -> service.delete(1L, 2L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repository, never()).disable(1L);
    }

    @Test
    void ownerCanDeleteKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, null, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));
        when(repository.disable(1L)).thenReturn(true);

        service.delete(1L, 1L);

        verify(repository).disable(1L);
        verify(auditLogService).log(1L, "DELETE_KNOWLEDGE_BASE", "knowledge_base", 1L, "kb", null);
    }

    @Test
    void teamMemberCanAccessTeamKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, 10L, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));
        when(memberRepository.existsMember(1L, 5L)).thenReturn(false);
        when(userService.isAdmin(5L)).thenReturn(false);
        when(teamService.canAccessTeam(10L, 5L)).thenReturn(true);

        service.requireKnowledgeBaseAccess(1L, 5L);
    }

    @Test
    void nonTeamMemberCannotAccessTeamKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, 10L, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));
        when(memberRepository.existsMember(1L, 5L)).thenReturn(false);
        when(userService.isAdmin(5L)).thenReturn(false);
        when(teamService.canAccessTeam(10L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.requireKnowledgeBaseAccess(1L, 5L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void adminCanAccessAnyKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, 10L, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));
        when(memberRepository.existsMember(1L, 99L)).thenReturn(false);
        when(userService.isAdmin(99L)).thenReturn(true);

        service.requireKnowledgeBaseAccess(1L, 99L);
    }

    @Test
    void teamOwnerCanDeleteTeamKnowledgeBase() {
        KnowledgeBaseService service = service();
        KnowledgeBase kb = new KnowledgeBase(1L, "kb", null, "ENABLED", 1L, 10L, null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(kb));
        when(memberRepository.isOwner(1L, 5L)).thenReturn(false);
        when(userService.isAdmin(5L)).thenReturn(false);
        when(teamService.isTeamOwner(10L, 5L)).thenReturn(true);
        when(repository.disable(1L)).thenReturn(true);

        service.delete(1L, 5L);

        verify(repository).disable(1L);
    }
}
