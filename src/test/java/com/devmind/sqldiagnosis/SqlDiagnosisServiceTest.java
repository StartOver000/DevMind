package com.devmind.sqldiagnosis;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.RetrievalService;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisRequest;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisResponse;
import com.devmind.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlDiagnosisServiceTest {

    @Mock
    private UserService userService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private RetrievalService retrievalService;

    @Mock
    private AiModelGateway modelGateway;

    @Mock
    private ChatRouter chatRouter;

    @Mock
    private MockSqlExplainService mockExplainService;

    @Mock
    private JdbcSqlExplainService jdbcExplainService;

    @Mock
    private SqlRuleEngine ruleEngine;

    @Mock
    private SqlDiagnosisRepository repository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ModelUsageService modelUsageService;

    private final DevMindProperties properties = new DevMindProperties(
            "mock", "./data", 20, "md,markdown,pdf", 1500, 200, "boundary", 8, 5, 10, 0.1,
            4, 3, 5000, 5, 60000, 60000, 0.7, 0.3, true, "mock", "mysql", "", "", "", 2000, "heuristic", 5,
            0.00015, 0.0006, "", "", "", "", "", "glm-4.7-flash", "embedding-2", 2000, false, true, "", "", ""
    );

    @Test
    void diagnoseReturnsAdviceAndSavesRecord() {
        SqlDiagnosisService service = new SqlDiagnosisService(
                userService,
                knowledgeBaseService,
                retrievalService,
                modelGateway,
                chatRouter,
                mockExplainService,
                jdbcExplainService,
                ruleEngine,
                repository,
                auditLogService,
                modelUsageService,
                properties
        );
        when(mockExplainService.explain(anyString(), anyString())).thenReturn(List.of(new SqlExplainRow(
                "1", "SIMPLE", "orders", "ALL", null, null, "1000000", "Using filesort"
        )));
        when(ruleEngine.analyze(anyList(), anyString())).thenReturn(List.of(new SqlRisk(
                "FULL_TABLE_SCAN", "HIGH", "全表扫描", "orders type=ALL"
        )));
        when(ruleEngine.maxLevel(anyList())).thenReturn("HIGH");
        when(chatRouter.chat(anyString(), anyString())).thenReturn(new AiModelGateway.ChatResult("建议", "mock", 0, 0));
        when(repository.save(
                anyLong(), anyString(), anyString(), anyList(), anyString(), anyList(), anyString(), isNull()
        )).thenReturn(1L);

        SqlDiagnosisResponse response = service.diagnose(
                new SqlDiagnosisRequest("SELECT * FROM orders", "mysql", null),
                1L
        );

        assertThat(response.riskLevel()).isEqualTo("HIGH");
        assertThat(response.advice()).isEqualTo("建议");
        assertThat(response.plan()).hasSize(1);
        verify(auditLogService).log(eq(1L), eq("SQL_DIAGNOSIS"), eq("sql_diagnosis"), eq(1L), anyString());
    }

    @Test
    void rejectsDmlBeforeRunningExplain() {
        SqlDiagnosisService service = new SqlDiagnosisService(
                userService,
                knowledgeBaseService,
                retrievalService,
                modelGateway,
                chatRouter,
                mockExplainService,
                jdbcExplainService,
                ruleEngine,
                repository,
                auditLogService,
                modelUsageService,
                properties
        );

        assertThatThrownBy(() -> service.diagnose(
                new SqlDiagnosisRequest("DELETE FROM orders", "mysql", null),
                1L
        ))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        verify(mockExplainService, never()).explain(anyString(), anyString());
    }
}
