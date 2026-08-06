package com.devmind.skill;

import com.devmind.skill.dto.SkillRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 技能 API：个人/团队技能的 CRUD + 从工作流沉淀（Guide-51 P1） */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    /** 技能列表（scope=team|personal|all，默认 all，按可见性过滤） */
    @GetMapping
    public List<Skill> list(
            @RequestParam(required = false) String scope,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return skillService.list(userId, scope);
    }

    @GetMapping("/{id}")
    public Skill get(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return skillService.get(userId, id);
    }

    @PostMapping
    public Skill create(
            @RequestBody SkillRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return skillService.create(userId, request.scope(), request.name(), request.description(),
                request.applyTo(), request.content(), null, null);
    }

    @PutMapping("/{id}")
    public Skill update(
            @PathVariable Long id,
            @RequestBody SkillRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return skillService.update(userId, id, request.scope(), request.name(), request.description(),
                request.applyTo(), request.content(), request.enabled());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        skillService.delete(userId, id);
        return Map.of("deleted", true);
    }

    /** 启用/停用 */
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(
            @PathVariable Long id,
            @RequestParam boolean enabled,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        skillService.toggle(userId, id, enabled);
        return Map.of("enabled", enabled);
    }

    /** 从工作流沉淀技能草稿（LLM 生成规范文本，前端编辑确认后 create） */
    @PostMapping("/from-workflow/{workflowId}")
    public SkillService.SkillDraft draftFromWorkflow(
            @PathVariable Long workflowId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return skillService.draftFromWorkflow(userId, workflowId);
    }
}
