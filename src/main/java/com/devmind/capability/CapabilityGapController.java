package com.devmind.capability;

import com.devmind.capability.CapabilityGapService.CapabilityAnalysis;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 缺失能力反推 API（接口能力治理）：描述业务需求 → 语义检索现有接口 +
 * LLM 能力盘点（覆盖/缺口）→ 缺失能力清单，补全 OpenAPI 后闭环。
 */
@RestController
@RequestMapping("/api/capabilities")
public class CapabilityGapController {

    private final CapabilityGapService capabilityGapService;

    public CapabilityGapController(CapabilityGapService capabilityGapService) {
        this.capabilityGapService = capabilityGapService;
    }

    /** 能力盘点：需求 → 命中接口 + 步骤覆盖分析 + 缺失能力清单 */
    @PostMapping("/analyze")
    public CapabilityAnalysis analyze(
            @RequestBody Map<String, String> request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        String description = request == null ? null : request.get("description");
        return capabilityGapService.analyze(userId, description);
    }
}
