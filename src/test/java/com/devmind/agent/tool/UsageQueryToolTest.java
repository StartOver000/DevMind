package com.devmind.agent.tool;

import com.devmind.modelusage.ModelUsageService;
import com.devmind.modelusage.dto.ModelUsageSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageQueryToolTest {

    @Test
    void returnsUsageSummaryJson() throws Exception {
        ModelUsageService modelUsageService = mock(ModelUsageService.class);
        when(modelUsageService.summary(any())).thenReturn(new ModelUsageSummaryResponse(
                320, 8535, 30086, new BigDecimal("0.019278")
        ));

        UsageQueryTool tool = new UsageQueryTool(modelUsageService, new ObjectMapper());
        String output = tool.execute("{}", 1L);

        assertThat(output).contains("\"totalCalls\":320");
        assertThat(output).contains("\"promptTokens\":8535");
        assertThat(output).contains("\"completionTokens\":30086");
        assertThat(output).contains("0.019278");
    }
}
