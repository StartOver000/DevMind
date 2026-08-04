package com.devmind.knowledge;

import com.devmind.knowledge.dto.AddMemberRequest;
import com.devmind.knowledge.dto.MemberListResponse;
import com.devmind.knowledge.dto.MemberResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/members")
public class KnowledgeBaseMemberController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseMemberController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    public MemberResponse add(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody AddMemberRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.addMember(
                knowledgeBaseId,
                request.userId(),
                request.role() == null || request.role().isBlank() ? "MEMBER" : request.role(),
                userId
        );
    }

    @GetMapping
    public MemberListResponse list(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.listMembers(knowledgeBaseId, userId);
    }

    @DeleteMapping("/{memberUserId}")
    public void remove(
            @PathVariable Long knowledgeBaseId,
            @PathVariable Long memberUserId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        service.removeMember(knowledgeBaseId, memberUserId, userId);
    }
}
