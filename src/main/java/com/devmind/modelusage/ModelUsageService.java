package com.devmind.modelusage;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindQuotaProperties;
import com.devmind.modelusage.dto.ModelUsageListResponse;
import com.devmind.modelusage.dto.ModelUsageResponse;
import com.devmind.modelusage.dto.ModelUsageSummaryResponse;
import com.devmind.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ModelUsageService {

    private final ModelUsageRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final UserService userService;
    private final DevMindProperties properties;
    private final DevMindQuotaProperties quota;
    private final MeterRegistry meterRegistry;

    public ModelUsageService(
            ModelUsageRepository repository,
            JdbcTemplate jdbcTemplate,
            UserService userService,
            DevMindProperties properties,
            DevMindQuotaProperties quota,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.userService = userService;
        this.properties = properties;
        this.quota = quota;
        this.meterRegistry = meterRegistry;
    }

    public void record(
            Long userId,
            String scene,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            String promptText,
            String completionText
    ) {
        checkQuota(userId, scene);
        int prompt = promptTokens != null ? promptTokens : estimateTokens(promptText);
        int completion = completionTokens != null ? completionTokens : estimateTokens(completionText);
        double cost = prompt / 1000.0 * properties.costPromptPer1k()
                + completion / 1000.0 * properties.costCompletionPer1k();
        meterRegistry.counter(
                "devmind.model.calls",
                "scene", scene,
                "model", model == null ? "unknown" : model
        ).increment();
        meterRegistry.counter(
                "devmind.model.tokens",
                "scene", scene,
                "model", model == null ? "unknown" : model
        ).increment(prompt + completion);
        repository.save(
                userId,
                scene,
                model == null ? "unknown" : model,
                prompt,
                completion,
                BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP)
        );
    }

    private void checkQuota(Long userId, String scene) {
        // 评估为批量任务场景，不参与用户配额
        if ("evaluation".equals(scene)) {
            return;
        }
        int callsLimit = quota.dailyCallsLimit();
        double costLimit = quota.dailyCostLimit();
        if (callsLimit <= 0 && costLimit <= 0) {
            return;
        }
        LocalDateTime since = LocalDate.now().atStartOfDay();
        if (callsLimit > 0) {
            Long calls = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM model_usage WHERE user_id = ? AND created_time >= ?",
                    Long.class,
                    userId,
                    since
            );
            if (calls != null && calls >= callsLimit) {
                throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "今日模型调用次数已达上限");
            }
        }
        if (costLimit > 0) {
            BigDecimal cost = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(estimated_cost), 0) FROM model_usage WHERE user_id = ? AND created_time >= ?",
                    BigDecimal.class,
                    userId,
                    since
            );
            if (cost != null && cost.doubleValue() >= costLimit) {
                throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "今日模型费用已达上限");
            }
        }
    }

    public ModelUsageListResponse list(Long userId, int limit) {
        userService.requireUser(userId);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<ModelUsageResponse> items = repository.listByUser(userId, safeLimit).stream()
                .map(this::toResponse)
                .toList();
        return new ModelUsageListResponse(items);
    }

    public ModelUsageListResponse listAll(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<ModelUsageResponse> items = repository.listAll(safeLimit).stream()
                .map(this::toResponse)
                .toList();
        return new ModelUsageListResponse(items);
    }

    public ModelUsageSummaryResponse summary(Long userId) {
        userService.requireUser(userId);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_usage WHERE user_id = ?",
                Long.class,
                userId
        );
        Long prompt = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens), 0) FROM model_usage WHERE user_id = ?",
                Long.class,
                userId
        );
        Long completion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(completion_tokens), 0) FROM model_usage WHERE user_id = ?",
                Long.class,
                userId
        );
        BigDecimal cost = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(estimated_cost), 0) FROM model_usage WHERE user_id = ?",
                BigDecimal.class,
                userId
        );
        return new ModelUsageSummaryResponse(
                count == null ? 0 : count,
                prompt == null ? 0 : prompt,
                completion == null ? 0 : completion,
                cost == null ? BigDecimal.ZERO : cost
        );
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private ModelUsageResponse toResponse(ModelUsage usage) {
        return new ModelUsageResponse(
                usage.id(),
                usage.userId(),
                usage.scene(),
                usage.model(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.estimatedCost(),
                usage.createdTime()
        );
    }
}
