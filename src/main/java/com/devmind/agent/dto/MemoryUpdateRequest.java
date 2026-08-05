package com.devmind.agent.dto;

import java.util.List;

/** 长期记忆批量更新请求（全量覆盖） */
public record MemoryUpdateRequest(
        List<MemoryItem> items
) {
}
