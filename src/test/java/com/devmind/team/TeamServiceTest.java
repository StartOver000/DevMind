package com.devmind.team;

import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.team.dto.CreateTeamRequest;
import com.devmind.team.dto.TeamResponse;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository repository;

    @Mock
    private UserService userService;

    @Mock
    private AuditLogService auditLogService;

    private TeamService service() {
        return new TeamService(repository, userService, auditLogService);
    }

    private Team team(Long id, Long ownerId) {
        return new Team(id, "团队" + id, null, ownerId, null, null);
    }

    @Test
    void createMakesCreatorOwnerAndLogsAudit() {
        TeamService service = service();
        when(repository.create("团队A", null, 1L)).thenReturn(100L);
        when(repository.findById(100L)).thenReturn(Optional.of(team(100L, 1L)));

        TeamResponse response = service.create(new CreateTeamRequest("团队A", null), 1L);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.ownerId()).isEqualTo(1L);
        verify(repository).addMember(100L, 1L, "OWNER");
        verify(auditLogService).log(1L, "CREATE_TEAM", "team", 100L, "团队A", 100L);
    }

    @Test
    void nonMemberCannotAccessTeam() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.existsMember(10L, 2L)).thenReturn(false);
        when(userService.isAdmin(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.detail(10L, 2L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void ownerCanAddMember() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 1L)).thenReturn(true);

        service.addMember(10L, 2L, "MEMBER", 1L);

        verify(repository).addMember(10L, 2L, "MEMBER");
        verify(auditLogService).log(1L, "ADD_TEAM_MEMBER", "team", 10L, "user=2", 10L);
    }

    @Test
    void nonOwnerCannotAddMember() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 2L)).thenReturn(false);
        when(userService.isAdmin(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.addMember(10L, 3L, "MEMBER", 2L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repository, never()).addMember(10L, 3L, "MEMBER");
    }

    @Test
    void cannotRemoveTeamOwner() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.removeMember(10L, 1L, 1L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void adminCanManageTeam() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 9L)).thenReturn(false);
        when(userService.isAdmin(9L)).thenReturn(true);

        service.addMember(10L, 2L, "MEMBER", 9L);

        verify(repository).addMember(10L, 2L, "MEMBER");
    }

    @Test
    void nonOwnerCannotDeleteTeam() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 2L)).thenReturn(false);
        when(userService.isAdmin(2L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(10L, 2L))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(repository, never()).delete(10L);
    }

    @Test
    void ownerDeletesTeamAndClearsMembersFirst() {
        TeamService service = service();
        when(repository.findById(10L)).thenReturn(Optional.of(team(10L, 1L)));
        when(repository.isOwner(10L, 1L)).thenReturn(true);

        service.delete(10L, 1L);

        // 先清成员再删团队，避免孤儿 team_member（A3 一致性）
        verify(repository).deleteMembers(10L);
        verify(repository).delete(10L);
    }
}
