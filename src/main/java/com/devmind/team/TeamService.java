package com.devmind.team;

import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.team.dto.CreateTeamRequest;
import com.devmind.team.dto.TeamDetailResponse;
import com.devmind.team.dto.TeamListResponse;
import com.devmind.team.dto.TeamMemberResponse;
import com.devmind.team.dto.TeamResponse;
import com.devmind.user.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TeamService {

    private final TeamRepository repository;
    private final UserService userService;
    private final AuditLogService auditLogService;

    public TeamService(
            TeamRepository repository,
            UserService userService,
            AuditLogService auditLogService
    ) {
        this.repository = repository;
        this.userService = userService;
        this.auditLogService = auditLogService;
    }

    public TeamResponse create(CreateTeamRequest request, Long userId) {
        userService.requireUser(userId);
        String name = request.name().trim();
        String description = request.description() == null ? null : request.description().trim();
        try {
            Long id = repository.create(name, description, userId);
            repository.addMember(id, userId, "OWNER");
            auditLogService.log(userId, "CREATE_TEAM", "team", id, name, id);
            return toResponse(requireTeam(id));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "团队名称已存在");
        }
    }

    public TeamListResponse list(Long userId) {
        userService.requireUser(userId);
        List<TeamResponse> items = (userService.isAdmin(userId)
                ? repository.listAll()
                : repository.listByMember(userId)).stream()
                .map(this::toResponse)
                .toList();
        return new TeamListResponse(items);
    }

    public TeamDetailResponse detail(Long teamId, Long userId) {
        requireTeamAccess(teamId, userId);
        Team team = requireTeam(teamId);
        List<TeamMemberResponse> members = repository.listMembers(teamId).stream()
                .map(m -> new TeamMemberResponse(m.userId(), m.username(), m.role(), m.createdTime()))
                .toList();
        return new TeamDetailResponse(
                team.id(), team.name(), team.description(), team.ownerId(), team.createdTime(), members
        );
    }

    public TeamMemberResponse addMember(Long teamId, Long memberUserId, String role, Long userId) {
        userService.requireUser(userId);
        userService.requireUser(memberUserId);
        requireTeamManage(teamId, userId);
        String safeRole = "OWNER".equalsIgnoreCase(role) ? "OWNER" : "MEMBER";
        repository.addMember(teamId, memberUserId, safeRole);
        auditLogService.log(userId, "ADD_TEAM_MEMBER", "team", teamId, "user=" + memberUserId, teamId);
        return new TeamMemberResponse(memberUserId, null, safeRole, null);
    }

    public List<TeamMemberResponse> listMembers(Long teamId, Long userId) {
        requireTeamAccess(teamId, userId);
        return repository.listMembers(teamId).stream()
                .map(m -> new TeamMemberResponse(m.userId(), m.username(), m.role(), m.createdTime()))
                .toList();
    }

    public void removeMember(Long teamId, Long memberUserId, Long userId) {
        userService.requireUser(userId);
        requireTeamManage(teamId, userId);
        if (repository.isOwner(teamId, memberUserId)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "不能移除团队所有者");
        }
        repository.removeMember(teamId, memberUserId);
        auditLogService.log(userId, "REMOVE_TEAM_MEMBER", "team", teamId, "user=" + memberUserId, teamId);
    }

    public void delete(Long teamId, Long userId) {
        userService.requireUser(userId);
        requireTeamManage(teamId, userId);
        Team team = requireTeam(teamId);
        repository.delete(teamId);
        auditLogService.log(userId, "DELETE_TEAM", "team", teamId, team.name(), teamId);
    }

    public Team requireTeam(Long teamId) {
        return repository.findById(teamId)
                .orElseThrow(() -> new ApiException(ErrorCode.TEAM_NOT_FOUND, "团队不存在"));
    }

    public void requireTeamAccess(Long teamId, Long userId) {
        Team team = requireTeam(teamId);
        if (!canAccessTeam(team.id(), userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该团队");
        }
    }

    public void requireTeamManage(Long teamId, Long userId) {
        requireTeam(teamId);
        if (!repository.isOwner(teamId, userId) && !userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只有团队所有者或管理员可以执行该操作");
        }
    }

    public boolean canAccessTeam(Long teamId, Long userId) {
        return repository.existsMember(teamId, userId) || userService.isAdmin(userId);
    }

    public boolean isTeamOwner(Long teamId, Long userId) {
        return repository.isOwner(teamId, userId);
    }

    private TeamResponse toResponse(Team team) {
        return new TeamResponse(team.id(), team.name(), team.description(), team.ownerId(), team.createdTime());
    }
}
