package com.devmind.knowledge;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.audit.AuditLogService;
import com.devmind.knowledge.dto.CreateKnowledgeBaseRequest;
import com.devmind.knowledge.dto.DeleteKnowledgeBaseResponse;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.knowledge.dto.KnowledgeBaseResponse;
import com.devmind.knowledge.dto.MemberListResponse;
import com.devmind.knowledge.dto.MemberResponse;
import com.devmind.team.TeamService;
import com.devmind.user.UserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository repository;
    private final UserService userService;
    private final AuditLogService auditLogService;
    private final KnowledgeBaseMemberRepository memberRepository;
    private final TeamService teamService;

    public KnowledgeBaseService(
            KnowledgeBaseRepository repository,
            UserService userService,
            AuditLogService auditLogService,
            KnowledgeBaseMemberRepository memberRepository,
            TeamService teamService
    ) {
        this.repository = repository;
        this.userService = userService;
        this.auditLogService = auditLogService;
        this.memberRepository = memberRepository;
        this.teamService = teamService;
    }

    public KnowledgeBaseResponse create(CreateKnowledgeBaseRequest request, Long userId) {
        userService.requireUser(userId);
        Long teamId = request.teamId();
        if (teamId != null) {
            teamService.requireTeamManage(teamId, userId);
        }
        String name = request.name().trim();
        String description = request.description() == null ? null : request.description().trim();
        try {
            Long id = repository.create(name, description, userId, teamId);
            memberRepository.insertOwner(id, userId);
            auditLogService.log(userId, "CREATE_KNOWLEDGE_BASE", "knowledge_base", id, name, teamId);
            return toResponse(requireKnowledgeBase(id));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "知识库名称已存在");
        }
    }

    public KnowledgeBaseListResponse list(Long userId) {
        userService.requireUser(userId);
        List<KnowledgeBaseItem> items = userService.isAdmin(userId)
                ? repository.listAllEnabled()
                : repository.listAccessible(userId);
        return new KnowledgeBaseListResponse(items);
    }

    public DeleteKnowledgeBaseResponse delete(Long id, Long userId) {
        userService.requireUser(userId);
        KnowledgeBase kb = requireManageKnowledgeBase(id, userId);
        repository.disable(id);
        auditLogService.log(userId, "DELETE_KNOWLEDGE_BASE", "knowledge_base", id, kb.name(), kb.teamId());
        return new DeleteKnowledgeBaseResponse(id, "DISABLED");
    }

    public KnowledgeBase requireKnowledgeBase(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }

    public KnowledgeBase requireKnowledgeBaseAccess(Long id, Long userId) {
        KnowledgeBase kb = requireKnowledgeBase(id);
        if (!canAccessKnowledgeBase(kb, userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该知识库");
        }
        return kb;
    }

    private boolean canAccessKnowledgeBase(KnowledgeBase kb, Long userId) {
        if (memberRepository.existsMember(kb.id(), userId) || userService.isAdmin(userId)) {
            return true;
        }
        return kb.teamId() != null && teamService.canAccessTeam(kb.teamId(), userId);
    }

    public KnowledgeBase requireEnabledKnowledgeBaseAccess(Long id, Long userId) {
        KnowledgeBase kb = requireKnowledgeBaseAccess(id, userId);
        if (!"ENABLED".equals(kb.status())) {
            throw new ApiException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在或已禁用");
        }
        return kb;
    }

    public MemberResponse addMember(Long knowledgeBaseId, Long memberUserId, String role, Long userId) {
        userService.requireUser(userId);
        userService.requireUser(memberUserId);
        KnowledgeBase kb = requireManageKnowledgeBase(knowledgeBaseId, userId);
        String safeRole = "OWNER".equalsIgnoreCase(role) ? "OWNER" : "MEMBER";
        memberRepository.addMember(knowledgeBaseId, memberUserId, safeRole);
        auditLogService.log(userId, "ADD_KB_MEMBER", "knowledge_base", knowledgeBaseId, "user=" + memberUserId, kb.teamId());
        return new MemberResponse(knowledgeBaseId, memberUserId, safeRole, null);
    }

    public MemberListResponse listMembers(Long knowledgeBaseId, Long userId) {
        requireKnowledgeBaseAccess(knowledgeBaseId, userId);
        List<MemberResponse> items = memberRepository.listMembers(knowledgeBaseId).stream()
                .map(member -> new MemberResponse(
                        member.knowledgeBaseId(),
                        member.userId(),
                        member.role(),
                        member.createdTime()
                ))
                .toList();
        return new MemberListResponse(items);
    }

    public void removeMember(Long knowledgeBaseId, Long memberUserId, Long userId) {
        userService.requireUser(userId);
        KnowledgeBase kb = requireManageKnowledgeBase(knowledgeBaseId, userId);
        if (kb.ownerId() != null && kb.ownerId().equals(memberUserId)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "不能移除知识库所有者");
        }
        memberRepository.removeMember(knowledgeBaseId, memberUserId);
        auditLogService.log(userId, "REMOVE_KB_MEMBER", "knowledge_base", knowledgeBaseId, "user=" + memberUserId, kb.teamId());
    }

    private KnowledgeBase requireManageKnowledgeBase(Long knowledgeBaseId, Long userId) {
        KnowledgeBase kb = requireKnowledgeBase(knowledgeBaseId);
        boolean manage = kb.ownerId() != null && kb.ownerId().equals(userId)
                || memberRepository.isOwner(knowledgeBaseId, userId)
                || userService.isAdmin(userId)
                || (kb.teamId() != null && teamService.isTeamOwner(kb.teamId(), userId));
        if (!manage) {
            throw new ApiException(ErrorCode.FORBIDDEN, "只有所有者或团队管理员可以执行该操作");
        }
        return kb;
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        return new KnowledgeBaseResponse(
                kb.id(),
                kb.name(),
                kb.description(),
                kb.status(),
                kb.ownerId(),
                kb.teamId(),
                kb.createdTime()
        );
    }
}
