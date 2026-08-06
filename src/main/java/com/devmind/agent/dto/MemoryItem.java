package com.devmind.agent.dto;

/**
 * 长期记忆条目（用户偏好/事实，跨会话保留）。
 * 可追溯：id 定位单条、source 区分来源（auto 自动提取 / manual 手动编辑）、
 * createdTime/updatedTime 记录时间戳（Guide-52 记忆升级）。
 */
public record MemoryItem(
        Long id,
        String key,
        String value,
        String source,     // auto | manual
        String createdTime,
        String updatedTime
) {

    /** 手动编辑/批量写入用：仅 key+value */
    public static MemoryItem of(String key, String value) {
        return new MemoryItem(null, key, value, "manual", null, null);
    }
}
