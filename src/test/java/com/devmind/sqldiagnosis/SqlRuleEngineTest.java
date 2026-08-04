package com.devmind.sqldiagnosis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRuleEngineTest {

    private final SqlRuleEngine engine = new SqlRuleEngine();

    @Test
    void detectsDeepPaginationAndFullTableScan() {
        List<SqlExplainRow> plan = List.of(new SqlExplainRow(
                "1", "SIMPLE", "orders", "ALL", null, null, "1000000", "Using filesort"
        ));

        List<SqlRisk> risks = engine.analyze(plan, "SELECT * FROM orders LIMIT 100000, 20");

        assertThat(risks)
                .anyMatch(risk -> "DEEP_PAGINATION".equals(risk.rule()) && "HIGH".equals(risk.level()))
                .anyMatch(risk -> "FULL_TABLE_SCAN".equals(risk.rule()) && "HIGH".equals(risk.level()));
        assertThat(engine.maxLevel(risks)).isEqualTo("HIGH");
    }

    @Test
    void marksSelectStarAsLowRisk() {
        List<SqlExplainRow> plan = List.of(new SqlExplainRow(
                "1", "SIMPLE", "user", "range", "idx_id", "idx_id", "10", "Using index condition"
        ));

        List<SqlRisk> risks = engine.analyze(plan, "SELECT * FROM user WHERE id = 1");

        assertThat(risks).anyMatch(risk -> "SELECT_STAR".equals(risk.rule()) && "LOW".equals(risk.level()));
        assertThat(engine.maxLevel(risks)).isEqualTo("LOW");
    }
}
