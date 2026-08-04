package com.devmind.sqldiagnosis;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.audit.AuditLogService;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisListResponse;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisRequest;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisResponse;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class SqlDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(SqlDiagnosisService.class);
    private final UserService userService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final ChatRouter chatRouter;
    private final MockSqlExplainService mockExplainService;
    private final JdbcSqlExplainService jdbcExplainService;
    private final SqlRuleEngine ruleEngine;
    private final SqlDiagnosisRepository repository;
    private final AuditLogService auditLogService;
    private final ModelUsageService modelUsageService;
    private final DevMindProperties properties;

    public SqlDiagnosisService(
            UserService userService,
            KnowledgeBaseService knowledgeBaseService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            ChatRouter chatRouter,
            MockSqlExplainService mockExplainService,
            JdbcSqlExplainService jdbcExplainService,
            SqlRuleEngine ruleEngine,
            SqlDiagnosisRepository repository,
            AuditLogService auditLogService,
            ModelUsageService modelUsageService,
            DevMindProperties properties
    ) {
        this.userService = userService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.chatRouter = chatRouter;
        this.mockExplainService = mockExplainService;
        this.jdbcExplainService = jdbcExplainService;
        this.ruleEngine = ruleEngine;
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.modelUsageService = modelUsageService;
        this.properties = properties;
    }

    public SqlDiagnosisResponse diagnose(SqlDiagnosisRequest request, Long userId) {
        userService.requireUser(userId);
        String sql = request.sql().trim();
        validateSql(sql);
        String dataSource = request.dataSource() == null || request.dataSource().isBlank()
                ? "default"
                : request.dataSource().trim();

        List<SqlExplainRow> plan = explainService().explain(sql, dataSource);
        List<SqlRisk> risks = ruleEngine.analyze(plan, sql);
        String riskLevel = ruleEngine.maxLevel(risks);
        String context = retrieveContext(request.knowledgeBaseId(), sql, userId);
        String advice = callModel(userId, sql, plan, risks, context);

        Long id = repository.save(
                userId,
                sql,
                dataSource,
                plan,
                riskLevel,
                risks,
                advice,
                request.knowledgeBaseId()
        );
        auditLogService.log(userId, "SQL_DIAGNOSIS", "sql_diagnosis", id, dataSource);
        return new SqlDiagnosisResponse(
                id,
                sql,
                dataSource,
                riskLevel,
                risks,
                plan,
                advice,
                request.knowledgeBaseId(),
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public SqlDiagnosisResponse getRecord(Long id, Long userId) {
        SqlDiagnosis record = repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.SQL_DIAGNOSIS_NOT_FOUND, "诊断记录不存在"));
        if (!record.userId().equals(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该诊断记录");
        }
        return toResponse(record);
    }

    public SqlDiagnosisListResponse list(Long userId, int limit) {
        userService.requireUser(userId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<SqlDiagnosisResponse> items = repository.listByUser(userId, safeLimit).stream()
                .map(this::toResponse)
                .toList();
        return new SqlDiagnosisListResponse(items);
    }

    private void validateSql(String sql) {
        if (sql.length() > properties.sqlDiagnosisMaxSqlLength()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "SQL 长度超过限制");
        }
        String lower = sql.toLowerCase();
        if (lower.matches("(?s)^\\s*(insert|update|delete|drop|alter|truncate|create|grant|revoke|call|replace|rename|set|lock|unlock)\\b.*")) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "只允许诊断 SELECT 或 EXPLAIN 语句");
        }
    }

    private SqlExplainService explainService() {
        return "jdbc".equalsIgnoreCase(properties.sqlDiagnosisMode())
                ? jdbcExplainService
                : mockExplainService;
    }

    private String retrieveContext(Long knowledgeBaseId, String sql, Long userId) {
        if (knowledgeBaseId == null) {
            return "";
        }
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        List<Double> vector = modelGateway.embed(List.of(sql)).get(0);
        List<RetrievalResult> results = retrievalService.searchHybrid(
                knowledgeBaseId,
                vector,
                sql,
                5,
                0.2,
                properties.retrievalVectorWeight(),
                properties.retrievalKeywordWeight(),
                properties.retrievalHybridEnabled()
        );
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            context.append('[').append(i + 1).append("] ")
                    .append(result.documentName()).append(":\n")
                    .append(result.content()).append('\n');
        }
        return context.toString();
    }

    private String callModel(Long userId, String sql, List<SqlExplainRow> plan, List<SqlRisk> risks, String context) {
        String systemPrompt = """
                你是一个 SQL 性能诊断专家。只能基于执行计划和参考资料给出诊断和建议。
                所有索引和 SQL 改写建议都必须标注“需人工验证”。
                禁止生成会修改数据库的语句，例如 UPDATE、DELETE、DROP、ALTER。
                """;
        StringBuilder userPrompt = new StringBuilder("需要诊断的 SQL：\n").append(sql).append("\n\n执行计划：\n");
        for (SqlExplainRow row : plan) {
            userPrompt.append("table=").append(row.table())
                    .append(", type=").append(row.type())
                    .append(", key=").append(row.key())
                    .append(", rows=").append(row.rows())
                    .append(", Extra=").append(row.extra())
                    .append('\n');
        }
        userPrompt.append("\n规则发现：\n");
        if (risks.isEmpty()) {
            userPrompt.append("未发现明显风险\n");
        } else {
            for (SqlRisk risk : risks) {
                userPrompt.append('[').append(risk.level()).append("] ")
                        .append(risk.message()).append("：").append(risk.evidence()).append('\n');
            }
        }
        if (!context.isBlank()) {
            userPrompt.append("\n参考资料：\n").append(context);
        }
        try {
            AiModelGateway.ChatResult result = chatRouter.chat(systemPrompt, userPrompt.toString());
            modelUsageService.record(
                    userId,
                    "sql",
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    userPrompt.toString(),
                    result.content()
            );
            return result.content();
        } catch (ApiException ex) {
            log.warn("sql diagnosis model call failed, fallback to rule-based advice: {}", ex.getMessage());
            return buildRuleBasedAdvice(risks);
        } catch (Exception ex) {
            log.warn("sql diagnosis model call failed, fallback to rule-based advice: {}", ex.getMessage());
            return buildRuleBasedAdvice(risks);
        }
    }

    private String buildRuleBasedAdvice(List<SqlRisk> risks) {
        if (risks.isEmpty()) {
            return "未发现明显风险。请结合执行计划进一步人工确认。";
        }
        StringBuilder advice = new StringBuilder("模型服务暂不可用，以下为基于规则的自动诊断建议（需人工验证）：\n");
        for (SqlRisk risk : risks) {
            advice.append("- [").append(risk.level()).append("] ")
                    .append(risk.message()).append("：").append(risk.evidence()).append('\n');
        }
        advice.append("建议结合执行计划人工优化索引或改写 SQL。");
        return advice.toString();
    }

    private SqlDiagnosisResponse toResponse(SqlDiagnosis record) {
        return new SqlDiagnosisResponse(
                record.id(),
                record.sqlText(),
                record.dataSource(),
                record.riskLevel(),
                repository.parseRisks(record.risksJson()),
                repository.parsePlan(record.explainJson()),
                record.advice(),
                record.knowledgeBaseId(),
                record.createdTime()
        );
    }
}
