package com.devmind.team;

import com.devmind.team.dto.AddTeamMemberRequest;
import com.devmind.team.dto.CreateTeamRequest;
import com.devmind.team.dto.TeamDetailResponse;
import com.devmind.team.dto.TeamListResponse;
import com.devmind.team.dto.TeamMemberResponse;
import com.devmind.team.dto.TeamResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    @PostMapping
    public TeamResponse create(
            @Valid @RequestBody CreateTeamRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.create(request, userId);
    }

    @GetMapping
    public TeamListResponse list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.list(userId);
    }

    @GetMapping("/{teamId}")
    public TeamDetailResponse detail(
            @PathVariable Long teamId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.detail(teamId, userId);
    }

    @PostMapping("/{teamId}/members")
    public TeamMemberResponse addMember(
            @PathVariable Long teamId,
            @Valid @RequestBody AddTeamMemberRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.addMember(
                teamId,
                request.userId(),
                request.role() == null || request.role().isBlank() ? "MEMBER" : request.role(),
                userId
        );
    }

    @GetMapping("/{teamId}/members")
    public List<TeamMemberResponse> listMembers(
            @PathVariable Long teamId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.listMembers(teamId, userId);
    }

    @DeleteMapping("/{teamId}/members/{memberUserId}")
    public void removeMember(
            @PathVariable Long teamId,
            @PathVariable Long memberUserId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        service.removeMember(teamId, memberUserId, userId);
    }

    @DeleteMapping("/{teamId}")
    public void delete(
            @PathVariable Long teamId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        service.delete(teamId, userId);
    }
}
