package com.devmind.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能匹配器（Guide-51 §5.2）：在 Agent 请求时，按关键词粗筛命中当前请求场景的
 * 团队/个人技能，返回需注入的规范文本。P1 用关键词粗筛（零成本），P3 升级为语义检索。
 */
@Component
public class SkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillMatcher.class);

    /** 单次最多注入的技能数 */
    private static final int MAX_INJECT = 2;
    /** 单个技能内容截断（防上下文膨胀） */
    private static final int MAX_CONTENT_CHARS = 1500;

    private final SkillRepository repository;

    public SkillMatcher(SkillRepository repository) {
        this.repository = repository;
    }

    /**
     * 匹配当前请求命中的技能规范文本（团队 + 本人 personal）。
     * 匹配规则：问题文本包含 apply_to 中的任一关键词（按 | 分隔）；未命中不注入。
     */
    public List<String> match(String question, Long tenantId, Long userId) {
        List<String> result = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return result;
        }
        try {
            List<Skill> skills = repository.listEnabledForUser(tenantId, userId);
            String q = question.toLowerCase();
            for (Skill skill : skills) {
                if (result.size() >= MAX_INJECT) {
                    break;
                }
                if (matches(skill, q)) {
                    String content = skill.content();
                    if (content.length() > MAX_CONTENT_CHARS) {
                        content = content.substring(0, MAX_CONTENT_CHARS);
                    }
                    result.add("【技能：" + skill.name() + "】\n" + content);
                }
            }
        } catch (Exception ex) {
            log.warn("技能匹配失败，跳过注入: {}", ex.getMessage());
        }
        return result;
    }

    /** apply_to 用 | 分隔多个关键词/场景；任一命中即匹配；空 apply_to 不匹配 */
    private boolean matches(Skill skill, String lowerQuestion) {
        String applyTo = skill.applyTo();
        if (applyTo == null || applyTo.isBlank()) {
            return false;
        }
        for (String token : applyTo.split("\\|")) {
            String t = token.trim().toLowerCase();
            if (!t.isEmpty() && lowerQuestion.contains(t)) {
                return true;
            }
        }
        return false;
    }
}
