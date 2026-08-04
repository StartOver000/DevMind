package com.devmind.knowledge;

import com.devmind.knowledge.dto.CreateKnowledgeBaseRequest;
import com.devmind.knowledge.dto.DeleteKnowledgeBaseResponse;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.knowledge.dto.KnowledgeBaseResponse;
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
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    public KnowledgeBaseResponse create(
            @Valid @RequestBody CreateKnowledgeBaseRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.create(request, userId);
    }

    @GetMapping
    public KnowledgeBaseListResponse list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.list(userId);
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public DeleteKnowledgeBaseResponse delete(
            @PathVariable Long knowledgeBaseId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.delete(knowledgeBaseId, userId);
    }
}
