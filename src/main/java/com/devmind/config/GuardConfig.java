package com.devmind.config;

import com.devmind.guard.ToolGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把 {@link DevMindGuardProperties} 转成纯逻辑的 {@link ToolGuard} Bean（G1）。
 */
@Configuration
public class GuardConfig {

    @Bean
    public ToolGuard toolGuard(DevMindGuardProperties properties) {
        return new ToolGuard(properties.enabled(), csv(properties.writeRoles()), csv(properties.deniedTools()));
    }

    private static Set<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return Set.copyOf(values);
    }
}
