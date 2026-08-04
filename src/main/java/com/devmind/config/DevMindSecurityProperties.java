package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 安全与稳定性相关配置（阶段 O）。
 */
@ConfigurationProperties(prefix = "devmind.security")
public record DevMindSecurityProperties(
        @DefaultValue("5") int loginMaxFailures,
        @DefaultValue("10") int loginLockMinutes,
        @DefaultValue("120") int rateLimitPerMinute,
        @DefaultValue("10") int rateLimitLoginPerMinute,
        @DefaultValue("7") int tokenTtlDays,
        @DefaultValue("2") int tokenRefreshThresholdDays,
        @DefaultValue("") String masterKey
) {
}
