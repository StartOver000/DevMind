package com.devmind.config;

import com.devmind.auth.AuthService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据库迁移器集成测试（独立 devmind_migration_test 库，避免与其他集成测试互踩）：
 * baseline + V2 应用、幂等、校验和漂移快速失败、迁移列被业务代码实际写入。
 * 前置条件：devmind-postgres 容器运行于 localhost:5432（devmind/devmind123，superuser）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/devmind_migration_test",
        "spring.datasource.username=devmind",
        "spring.datasource.password=devmind123",
        "devmind.model-mode=mock",
        "devmind.embedding-dimensions=8",
        "devmind.auth-enabled=false",
        "devmind.retrieval-min-score=0.0",
        "devmind.max-file-size-mb=1",
        "devmind.storage-path=./target/test-files-migration",
        "devmind.task-scan-interval-ms=600000",
        "devmind.task-scan-initial-delay-ms=600000"
})
@SuppressWarnings("null")
class SchemaMigratorIntegrationTest {

    @Autowired
    private SchemaMigrator schemaMigrator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthService authService;

    @BeforeAll
    static void prepareTestDatabase() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/postgres", "devmind", "devmind123");
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS devmind_migration_test");
            st.execute("CREATE DATABASE devmind_migration_test");
        }
    }

    @Test
    void baselineAndV2Applied() {
        List<Map<String, Object>> versions = jdbcTemplate.queryForList(
                "SELECT version, description FROM schema_version ORDER BY version");
        assertThat(versions).extracting(r -> ((Number) r.get("version")).intValue())
                .containsExactly(1, 2);

        Integer colCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'app_user' AND column_name = 'last_login_time'
                """, Integer.class);
        assertThat(colCount).isEqualTo(1);
    }

    @Test
    void migrateIsIdempotent() {
        int before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
        List<SchemaMigrator.AppliedMigration> result = schemaMigrator.migrate();
        int after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
        assertThat(after).isEqualTo(before);
        assertThat(result).hasSize(before);
    }

    @Test
    void checksumDriftFailsFast() throws Exception {
        jdbcTemplate.update("UPDATE schema_version SET checksum = 'tampered' WHERE version = 2");
        try {
            assertThatThrownBy(() -> schemaMigrator.migrate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("校验和漂移");
        } finally {
            // 恢复真实校验和，避免影响其他测试
            String sql = new ClassPathResource("db/migration/V2__app_user_last_login_time.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            jdbcTemplate.update(
                    "UPDATE schema_version SET checksum = ? WHERE version = 2",
                    SchemaMigrator.sha256(sql)
            );
        }
    }

    @Test
    void loginWritesMigratedColumn() {
        authService.login("demo", "demo123");
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM app_user
                WHERE username = 'demo' AND last_login_time IS NOT NULL
                """, Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
