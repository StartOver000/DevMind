package com.devmind.workflow;

/**
 * 工作流定义：业务人员编排的多步骤自动化（steps_json 为唯一事实源）。
 * 对应表 workflow。
 */
public record Workflow(
        Long id,
        Long tenantId,
        String name,
        String description,
        String stepsJson,   // [{tool, params, output_var}]
        String triggerType, // manual | cron | webhook
        String cronExpr,
        String scope,       // private | team
        String status,      // ENABLED | DISABLED
        Long createdBy,
        String createdTime
) {
    public static Workflow forInsert(
            Long tenantId, String name, String description, String stepsJson,
            String triggerType, String cronExpr, String scope, String status, Long createdBy
    ) {
        return new Workflow(null, tenantId, name, description, stepsJson,
                triggerType, cronExpr, scope, status, createdBy, null);
    }
}
