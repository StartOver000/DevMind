package com.devmind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量版数据库迁移器（mini-Flyway，零新依赖，P0-2）。
 *
 * <p>背景：schema 原由 {@link DatabaseInitializer} 用 CREATE TABLE IF NOT EXISTS 硬编码创建——
 * 无版本记录、IF NOT EXISTS 会掩盖漂移、无法做破坏性/数据迁移。本类引入版本化迁移：
 *
 * <ul>
 *   <li><b>baseline V1</b> = DatabaseInitializer 现状。首次启动登记进 schema_version，
 *       校验和为 DatabaseInitializer.class 字节哈希（历史遗留基线，漂移仅告警不阻塞）</li>
 *   <li><b>迁移脚本</b> classpath:db/migration/V{n}__{desc}.sql（n ≥ 2），启动时按版本升序应用一次</li>
 *   <li><b>schema_version</b> 表记录 version / description / checksum / applied_at</li>
 *   <li><b>校验和漂移检测</b>：已应用迁移的脚本文件被改动 → 启动失败（禁止"偷改已生效迁移"，
 *       新变更必须新增更高版本脚本）</li>
 *   <li><b>事务</b>：每个迁移一个事务，失败整体回滚 → 启动失败（生产安全）</li>
 *   <li><b>幂等</b>：可重复调用；多实例（app/app2）同时启动安全</li>
 * </ul>
 *
 * <p>并发取舍：双实例同时启动可能都读到"待应用"。采用
 * {@code INSERT ... ON CONFLICT (version) DO NOTHING} + 脚本内 DDL 用 {@code IF NOT EXISTS}
 * 保证并发下不报错、不重复记录——这是用"轻微放宽 once-only 语义"换"多实例无锁启动正确性"的显式设计决策。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@SuppressWarnings("null")
public class SchemaMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    /** baseline（DatabaseInitializer 现状）版本号；迁移脚本必须从 V2 开始 */
    static final int BASELINE_VERSION = 1;

    private static final String MIGRATION_DIR = "db/migration";
    private static final Pattern SCRIPT_NAME = Pattern.compile("^V(\\d+)__(.+)\\.sql$");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate txTemplate;

    public SchemaMigrator(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** 应用待执行迁移（幂等，可重复调用）。返回当前已应用迁移列表。 */
    public List<AppliedMigration> migrate() {
        ensureVersionTable();
        ensureBaseline();
        Map<Integer, MigrationScript> scripts = loadScripts();
        validateScripts(scripts);
        validateAppliedChecksums(scripts);
        for (MigrationScript script : scripts.values()) {
            if (isApplied(script.version())) {
                continue;
            }
            apply(script);
        }
        List<AppliedMigration> applied = listApplied();
        log.info("schema migrations done, total applied={}", applied.size());
        return applied;
    }

    /** 已应用的迁移记录 */
    public record AppliedMigration(int version, String description, String checksum, OffsetDateTime appliedAt) {
    }

    private void ensureVersionTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INT PRIMARY KEY,
                    description VARCHAR(200) NOT NULL,
                    checksum VARCHAR(64) NOT NULL,
                    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    /** 首次启动登记 baseline；若 DatabaseInitializer 类字节变化，仅告警（历史遗留基线允许小修） */
    private void ensureBaseline() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class);
        if (count != null && count > 0) {
            String recorded = String.valueOf(jdbcTemplate.queryForObject(
                    "SELECT checksum FROM schema_version WHERE version = ?", String.class, BASELINE_VERSION));
            String current = baselineChecksum();
            if (!recorded.equals(current)) {
                log.warn("baseline(DatabaseInitializer) 校验和漂移：已登记={} 当前={} —— 历史遗留基线允许修改，"
                        + "但新的 schema 变更请走 db/migration 迁移脚本", recorded, current);
            }
            return;
        }
        String checksum = baselineChecksum();
        jdbcTemplate.update("""
                INSERT INTO schema_version (version, description, checksum, applied_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (version) DO NOTHING
                """, BASELINE_VERSION, "baseline (DatabaseInitializer)", checksum);
        log.info("schema baseline V{} registered (checksum={})", BASELINE_VERSION, checksum);
    }

    private String baselineChecksum() {
        String checksum = "baseline-v1";
        try (var in = SchemaMigrator.class.getResourceAsStream("/com/devmind/config/DatabaseInitializer.class")) {
            if (in != null) {
                checksum = sha256(in.readAllBytes());
            }
        } catch (Exception e) {
            log.warn("读取 DatabaseInitializer.class 失败，baseline 校验和使用占位值", e);
        }
        return checksum;
    }

    /** 从 classpath:db/migration/ 加载迁移脚本，按版本号升序 */
    private Map<Integer, MigrationScript> loadScripts() {
        Map<Integer, MigrationScript> scripts = new TreeMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + MIGRATION_DIR + "/*.sql");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher matcher = SCRIPT_NAME.matcher(filename);
                if (!matcher.matches()) {
                    throw new IllegalStateException("迁移脚本命名非法（须 V{n}__desc.sql）: " + filename);
                }
                int version = Integer.parseInt(matcher.group(1));
                String description = matcher.group(2).replace('_', ' ');
                String sql = resource.getContentAsString(StandardCharsets.UTF_8);
                MigrationScript script = new MigrationScript(version, description, filename, sql);
                if (scripts.putIfAbsent(version, script) != null) {
                    throw new IllegalStateException("迁移版本号重复: V" + version + "（" + filename + "）");
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("加载 db/migration 迁移脚本失败", e);
        }
        return scripts;
    }

    private void validateScripts(Map<Integer, MigrationScript> scripts) {
        for (int version : scripts.keySet()) {
            if (version <= BASELINE_VERSION) {
                throw new IllegalStateException(
                        "迁移版本必须 > " + BASELINE_VERSION + "（V1 为 DatabaseInitializer baseline）: V" + version);
            }
        }
    }

    /** 校验已应用迁移的校验和；脚本被改动或文件缺失 → 启动失败 */
    private void validateAppliedChecksums(Map<Integer, MigrationScript> scripts) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT version, checksum FROM schema_version WHERE version > ?", BASELINE_VERSION);
        for (Map<String, Object> row : rows) {
            int version = ((Number) row.get("version")).intValue();
            String recorded = String.valueOf(row.get("checksum"));
            MigrationScript script = scripts.get(version);
            if (script == null) {
                throw new IllegalStateException(
                        "已应用迁移 V" + version + " 的脚本文件缺失（被删除？），无法校验，拒绝启动");
            }
            String current = script.checksum();
            if (!current.equals(recorded)) {
                throw new IllegalStateException(
                        "迁移校验和漂移: V" + version + "（" + script.description()
                                + "）已应用后脚本被修改，拒绝启动。禁止修改已生效迁移，请新增更高版本迁移脚本");
            }
        }
    }

    private boolean isApplied(int version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_version WHERE version = ?", Integer.class, version);
        return count != null && count > 0;
    }

    private void apply(MigrationScript script) {
        txTemplate.executeWithoutResult(status -> {
            log.info("applying migration V{} ({})", script.version(), script.description());
            jdbcTemplate.execute(script.sql());
            int rows = jdbcTemplate.update("""
                    INSERT INTO schema_version (version, description, checksum, applied_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (version) DO NOTHING
                    """, script.version(), script.description(), script.checksum());
            if (rows == 0) {
                // 另一实例并发应用了同一版本：回滚本次（脚本内 DDL 幂等，无副作用）
                log.info("migration V{} 已被并发实例应用，本次回滚", script.version());
                status.setRollbackOnly();
            }
        });
    }

    private List<AppliedMigration> listApplied() {
        return jdbcTemplate.query("""
                SELECT version, description, checksum, applied_at
                FROM schema_version ORDER BY version
                """, (rs, rowNum) -> new AppliedMigration(
                rs.getInt("version"),
                rs.getString("description"),
                rs.getString("checksum"),
                rs.getTimestamp("applied_at").toInstant().atOffset(ZoneOffset.UTC)
        ));
    }

    /** 迁移脚本（文件名解析结果 + 内容） */
    private record MigrationScript(int version, String description, String filename, String sql) {
        String checksum() {
            return sha256(sql);
        }
    }

    static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
