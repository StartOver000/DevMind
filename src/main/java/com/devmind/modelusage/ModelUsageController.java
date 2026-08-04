package com.devmind.modelusage;

import com.devmind.modelusage.dto.ModelUsageListResponse;
import com.devmind.modelusage.dto.ModelUsageSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-usage")
public class ModelUsageController {

    private final ModelUsageService service;

    public ModelUsageController(ModelUsageService service) {
        this.service = service;
    }

    @GetMapping
    public ModelUsageListResponse list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "false") boolean all,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return all ? service.listAll(limit) : service.list(userId, limit);
    }

    @GetMapping("/summary")
    public ModelUsageSummaryResponse summary(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.summary(userId);
    }
}
