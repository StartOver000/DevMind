package com.devmind.modelusage;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.config.DevMindQuotaProperties;
import com.devmind.user.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelUsageServiceTest {

    @Mock
    private ModelUsageRepository repository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private UserService userService;

    @Mock
    private DevMindProperties properties;

    private ModelUsageService service(DevMindQuotaProperties quota) {
        return new ModelUsageService(
                repository,
                jdbcTemplate,
                userService,
                properties,
                quota,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void blocksWhenDailyCallsExceeded() {
        ModelUsageService service = service(new DevMindQuotaProperties(5, 0.0));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.record(1L, "chat", "model", 10, 5, "p", "c"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    void allowsWhenQuotaDisabled() {
        ModelUsageService service = service(new DevMindQuotaProperties(0, 0.0));
        service.record(1L, "chat", "model", 10, 5, "prompt", "completion");
    }

    @Test
    void evaluationSceneNotQuotaChecked() {
        // 限额 1，但评估场景不参与配额
        ModelUsageService service = service(new DevMindQuotaProperties(1, 0.0));
        service.record(1L, "evaluation", "model", 10, 5, "prompt", "completion");
    }
}
