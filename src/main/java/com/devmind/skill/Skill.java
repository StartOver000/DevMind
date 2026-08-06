package com.devmind.skill;

/**
 * 技能（Skill）：个人/团队"做事规范"（对应表 skill，Guide-51）。
 * scope: personal（仅本人注入）| team（同租户全员注入）。
 * source: manual（手动创建）| from_workflow（工作流沉淀）| from_chat（对话沉淀）。
 */
public record Skill(
        Long id,
        Long tenantId,
        String scope,          // personal | team
        String name,
        String description,
        String applyTo,        // 匹配关键词/场景描述
        String content,        // 行为准则文本
        String source,         // manual | from_workflow | from_chat
        Long sourceWorkflowId, // 从工作流沉淀时的来源
        boolean enabled,
        Long hitCount,         // 命中次数（Agent 注入统计）
        Long createdBy,
        String createdTime
) {

    public static Skill forInsert(
            Long tenantId, String scope, String name, String description,
            String applyTo, String content, String source, Long sourceWorkflowId, Long createdBy
    ) {
        return new Skill(null, tenantId, scope, name, description, applyTo,
                content, source, sourceWorkflowId, true, 0L, createdBy, null);
    }
}
