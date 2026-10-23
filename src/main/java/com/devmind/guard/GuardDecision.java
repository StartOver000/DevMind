package com.devmind.guard;

import java.util.Objects;

/**
 * 一次工具调用的授权判定结果（ALLOW / DENY + 命中的策略编号与人类可读原因）。
 * 策略编号用于审计落库，便于回放时说明「为什么允许/为什么拦住」。
 */
public record GuardDecision(Decision decision, String policyId, String reason) {

    public enum Decision {
        ALLOW,
        DENY
    }

    public GuardDecision {
        Objects.requireNonNull(decision, "decision");
    }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }

    public static GuardDecision allow(String policyId) {
        return new GuardDecision(Decision.ALLOW, policyId, null);
    }

    public static GuardDecision deny(String policyId, String reason) {
        return new GuardDecision(Decision.DENY, policyId, reason);
    }
}
