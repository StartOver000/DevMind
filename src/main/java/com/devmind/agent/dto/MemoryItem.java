package com.devmind.agent.dto;

/** 长期记忆条目（用户偏好/事实，跨会话保留） */
public record MemoryItem(
        String key,
        String value
) {
}
