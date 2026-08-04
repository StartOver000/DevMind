package com.devmind.sqldiagnosis;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockSqlExplainService implements SqlExplainService {

    @Override
    public List<SqlExplainRow> explain(String sql, String dataSource) {
        String lower = sql.toLowerCase();
        if (lower.matches("(?s).*limit\\s+\\d+\\s*,\\s*\\d+.*")
                || lower.matches("(?s).*limit\\s+\\d+\\s+offset\\s+\\d+.*")) {
            return List.of(new SqlExplainRow(
                    "1", "SIMPLE", "orders", "ALL", null, null, "1000000", "Using filesort"
            ));
        }
        if (lower.contains("like '%")) {
            return List.of(new SqlExplainRow(
                    "1", "SIMPLE", "product", "ALL", null, null, "500000", "Using where"
            ));
        }
        if (lower.matches("(?s).*select\\s+\\*.*")) {
            return List.of(new SqlExplainRow(
                    "1", "SIMPLE", "user", "ALL", null, null, "100000", "Using where"
            ));
        }
        if (lower.contains(" join ") && !lower.contains(" where ")) {
            return List.of(new SqlExplainRow(
                    "1", "SIMPLE", "orders", "ALL", null, null, "200000", "Using join buffer (Block Nested Loop)"
            ));
        }
        return List.of(new SqlExplainRow(
                "1", "SIMPLE", "orders", "range", "idx_order_no", "idx_order_no", "100", "Using index condition"
        ));
    }
}
