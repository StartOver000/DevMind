package com.devmind.metrics;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 产品运营指标 API：
 * - GET /api/admin/product-metrics：管理员查看全租户激活漏斗与活跃概览（产品数据页）；
 * - GET /api/me/summary：当前用户资产快照（留存钩子，知识库页展示"我的资产"）。
 */
@RestController
public class ProductMetricsController {

    private final ProductMetricsService service;

    public ProductMetricsController(ProductMetricsService service) {
        this.service = service;
    }

    @GetMapping("/api/admin/product-metrics")
    public Map<String, Object> productMetrics(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        return service.productMetrics(operatorId);
    }

    @GetMapping("/api/me/summary")
    public Map<String, Object> mySummary(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.mySummary(userId);
    }
}
