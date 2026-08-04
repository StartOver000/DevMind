package com.devmind.security;

import java.util.regex.Pattern;

/**
 * 敏感字段脱敏工具：把日志/错误消息中的密钥、Token 替换为掩码。
 */
public final class SensitiveDataMasker {

    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9._~+/=-]+");
    private static final Pattern SK_TOKEN = Pattern.compile("(?i)(sk-[A-Za-z0-9_]{4})[A-Za-z0-9_-]+");

    private SensitiveDataMasker() {
    }

    public static String mask(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }
        String masked = BEARER.matcher(message).replaceAll("$1***");
        masked = API_KEY.matcher(masked).replaceAll("$1***");
        masked = SK_TOKEN.matcher(masked).replaceAll("$1***");
        return masked;
    }
}
