package com.devmind.skill.dto;

/** 创建/更新技能请求 */
public record SkillRequest(
        String scope,        // personal | team
        String name,
        String description,
        String applyTo,
        String content,
        String references,   // 引用资源 JSON（可选）
        boolean enabled
) {
}
