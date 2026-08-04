package com.devmind.document.chunker;

/**
 * 切块策略枚举。
 * BOUNDARY：按标题/列表等语义边界切块（默认）；
 * FIXED：按固定字符大小切块。
 */
public enum ChunkerStrategy {
    BOUNDARY,
    FIXED;

    public static ChunkerStrategy from(String value) {
        if (value == null) {
            return BOUNDARY;
        }
        return switch (value.trim().toLowerCase()) {
            case "fixed" -> FIXED;
            default -> BOUNDARY;
        };
    }
}
