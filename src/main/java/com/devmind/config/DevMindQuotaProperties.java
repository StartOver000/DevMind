package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 用量配额配置（阶段 Q）：按用户限制每日模型调用次数/费用，0 表示不限制。
 */
@ConfigurationProperties(prefix = "devmind.quota")
public record DevMindQuotaProperties(
        @DefaultValue("0") int dailyCallsLimit,
        @DefaultValue("0.0") double dailyCostLimit
) {
}
