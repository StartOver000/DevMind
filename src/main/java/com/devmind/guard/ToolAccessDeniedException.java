package com.devmind.guard;

/**
 * 工具调用在执行前被授权策略拒绝（G1）。
 *
 * <p>抛出方是 {@code ToolRegistry.execute}；调用方（Agent / 工作流）可以捕获它把
 * 拒绝原因回填给模型或用户，也可以让它中断流程。无论哪种，审计已经落库。</p>
 */
public class ToolAccessDeniedException extends RuntimeException {

    private final String toolName;
    private final String policyId;

    public ToolAccessDeniedException(String toolName, GuardDecision decision) {
        super("工具调用被拒绝: tool=" + toolName
                + ", policy=" + decision.policyId()
                + ", reason=" + decision.reason());
        this.toolName = toolName;
        this.policyId = decision.policyId();
    }

    public String toolName() {
        return toolName;
    }

    public String policyId() {
        return policyId;
    }
}
