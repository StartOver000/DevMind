package com.devmind.workflow.dto;

import java.util.List;

/**
 * 对话式生成的工作流步骤草案（展示/确认用）。
 * 递归结构：kind=step 普通步骤 / kind=if 条件分支 / kind=parallel 并行组。
 */
public record WorkflowStepDraft(
        String kind,            // step | if | parallel
        String tool,            // step 用
        String paramsJson,      // step 用
        String outputVar,       // step 用
        String goal,            // step 用
        String condition,       // if 用
        List<WorkflowStepDraft> thenBranch,   // if 用
        List<WorkflowStepDraft> elseBranch,   // if 用
        List<WorkflowStepDraft> parallelSteps // parallel 用
) {

    public static WorkflowStepDraft step(String tool, String paramsJson, String outputVar, String goal) {
        return new WorkflowStepDraft("step", tool, paramsJson, outputVar, goal, null, null, null, null);
    }

    public static WorkflowStepDraft ifNode(String condition, List<WorkflowStepDraft> thenBranch, List<WorkflowStepDraft> elseBranch) {
        return new WorkflowStepDraft("if", null, null, null, null, condition, thenBranch, elseBranch, null);
    }

    public static WorkflowStepDraft parallel(List<WorkflowStepDraft> steps) {
        return new WorkflowStepDraft("parallel", null, null, null, null, null, null, null, steps);
    }
}
