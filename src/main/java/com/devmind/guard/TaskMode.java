package com.devmind.guard;

import java.util.Locale;

/**
 * 任务模式：声明「这一次对话/任务允许做多大范围的事」。
 *
 * <p>READ_ONLY 表示本任务只做读操作，任何有副作用的写操作都会被
 * {@link ToolGuard} 在执行前拦下；未声明时按 WRITE 处理（保持既有行为）。</p>
 */
public enum TaskMode {

    /** 只读任务：禁止一切写操作 */
    READ_ONLY,

    /** 普通任务：允许写操作（仍需通过角色等其余策略） */
    WRITE;

    /**
     * 解析外部传入的模式字符串：null/空白 = WRITE（默认放行旧行为）；
     * 兼容 read_only / readonly / read-only / read。
     *
     * <p>非空但无法识别的值直接抛 {@link IllegalArgumentException}（fail-closed），
     * 避免客户端把 {@code read_only} 拼错后静默降级为可写。</p>
     */
    public static TaskMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WRITE;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "READ_ONLY", "READONLY", "READ-ONLY", "READ" -> READ_ONLY;
            case "WRITE" -> WRITE;
            default -> throw new IllegalArgumentException(
                    "未知的 taskMode: " + raw + "（可用值：read_only / write）");
        };
    }

    public boolean readOnly() {
        return this == READ_ONLY;
    }
}
