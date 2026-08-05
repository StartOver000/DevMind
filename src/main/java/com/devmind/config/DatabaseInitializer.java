package com.devmind.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbcTemplate;
    private final DevMindProperties properties;
    private final PasswordEncoder passwordEncoder;

    public DatabaseInitializer(
            JdbcTemplate jdbcTemplate,
            DevMindProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        int dimensions = properties.embeddingDimensions();
        if (dimensions <= 0) {
            throw new IllegalStateException("devmind.embedding-dimensions 必须大于 0");
        }

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_user (
                    id BIGSERIAL PRIMARY KEY,
                    username VARCHAR(50) NOT NULL UNIQUE,
                    display_name VARCHAR(100),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100)");
        jdbcTemplate.execute("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'USER'");
        Integer demoCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = 'demo'",
                Integer.class
        );
        if (demoCount == null || demoCount == 0) {
            jdbcTemplate.update("""
                    INSERT INTO app_user (username, display_name, password_hash, created_time)
                    VALUES ('demo', '演示用户', ?, CURRENT_TIMESTAMP)
                    """, passwordEncoder.encode("demo123"));
        } else {
            Integer withPassword = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE username = 'demo' AND password_hash IS NOT NULL",
                    Integer.class
            );
            if (withPassword == null || withPassword == 0) {
                jdbcTemplate.update(
                        "UPDATE app_user SET password_hash = ? WHERE username = 'demo'",
                        passwordEncoder.encode("demo123")
                );
            }
        }
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE username = 'demo' AND role <> 'ADMIN'");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    action VARCHAR(50) NOT NULL,
                    target_type VARCHAR(50),
                    target_id BIGINT,
                    detail VARCHAR(1000),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_user ON audit_log(user_id, created_time)");
        jdbcTemplate.execute("ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS team_id BIGINT");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_audit_log_team ON audit_log(team_id, created_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS auth_token (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    token VARCHAR(64) NOT NULL UNIQUE,
                    expires_at TIMESTAMP NOT NULL,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_auth_token_user ON auth_token(user_id, expires_at)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_base (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL UNIQUE,
                    description VARCHAR(500),
                    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED',
                    created_by BIGINT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS team_id BIGINT");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_knowledge_base_team ON knowledge_base(team_id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document (
                    id BIGSERIAL PRIMARY KEY,
                    knowledge_base_id BIGINT NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    file_type VARCHAR(50) NOT NULL,
                    file_size BIGINT NOT NULL,
                    file_path VARCHAR(500) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    error_message VARCHAR(2000),
                    created_by BIGINT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        // 软删文档（DELETED 标记）保留行用于版本快照/审计，因此唯一约束只对非 DELETED 生效，
        // 避免同内容文档重新上传/更新时撞 uq_document_kb_hash。
        jdbcTemplate.execute("ALTER TABLE document DROP CONSTRAINT IF EXISTS uq_document_kb_hash");
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_document_kb_hash
                ON document(knowledge_base_id, content_hash)
                WHERE status <> 'DELETED'
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_document_kb_status ON document(knowledge_base_id, status)");
        jdbcTemplate.execute("ALTER TABLE document ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1");
        jdbcTemplate.execute("ALTER TABLE document ADD COLUMN IF NOT EXISTS metadata JSONB");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_version (
                    id BIGSERIAL PRIMARY KEY,
                    document_id BIGINT NOT NULL,
                    version INT NOT NULL,
                    file_name VARCHAR(255) NOT NULL,
                    file_type VARCHAR(50) NOT NULL,
                    file_size BIGINT NOT NULL,
                    file_path VARCHAR(500) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    created_by BIGINT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_document_version UNIQUE (document_id, version)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_document_version_document ON document_version(document_id, version)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS knowledge_base_member (
                    knowledge_base_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (knowledge_base_id, user_id)
                )
                """);
        jdbcTemplate.execute("""
                INSERT INTO knowledge_base_member (knowledge_base_id, user_id, role, created_time)
                SELECT id, created_by, 'OWNER', CURRENT_TIMESTAMP
                FROM knowledge_base
                WHERE created_by IS NOT NULL
                ON CONFLICT (knowledge_base_id, user_id) DO NOTHING
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_task (
                    id BIGSERIAL PRIMARY KEY,
                    document_id BIGINT NOT NULL UNIQUE,
                    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    retry_count INT NOT NULL DEFAULT 0,
                    max_retries INT NOT NULL DEFAULT 3,
                    error_message VARCHAR(2000),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_document_task_status ON document_task(status, updated_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS document_chunk (
                    id BIGSERIAL PRIMARY KEY,
                    document_id BIGINT NOT NULL,
                    chunk_index INT NOT NULL,
                    content TEXT NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    metadata JSONB,
                    embedding vector(%d) NOT NULL,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_chunk_doc_index UNIQUE (document_id, chunk_index)
                )
                """.formatted(dimensions));
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON document_chunk(document_id)");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
                ON document_chunk USING hnsw (embedding vector_cosine_ops)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_conversation (
                    id BIGSERIAL PRIMARY KEY,
                    knowledge_base_id BIGINT NOT NULL,
                    user_id BIGINT,
                    title VARCHAR(200),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    prompt_tokens INT,
                    completion_tokens INT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_message_conversation ON chat_message(conversation_id, created_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_conversation (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    title VARCHAR(200) NOT NULL DEFAULT '',
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_conversation_user ON agent_conversation(user_id, updated_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_message (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL,
                    content TEXT NOT NULL,
                    tool_name VARCHAR(100),
                    tool_call_id VARCHAR(100),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_message_conversation ON agent_message(conversation_id, id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace (
                    id BIGSERIAL PRIMARY KEY,
                    conversation_id BIGINT NOT NULL,
                    tool VARCHAR(100) NOT NULL,
                    args TEXT,
                    ok BOOLEAN NOT NULL DEFAULT TRUE,
                    cost_ms BIGINT NOT NULL DEFAULT 0,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_conversation ON agent_trace(conversation_id, id)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_memory (
                    user_id BIGINT NOT NULL,
                    memory_key VARCHAR(100) NOT NULL,
                    memory_value VARCHAR(500) NOT NULL,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (user_id, memory_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sql_diagnosis (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    sql_text TEXT NOT NULL,
                    data_source VARCHAR(100),
                    explain_json JSONB,
                    risk_level VARCHAR(20),
                    risks_json JSONB,
                    advice TEXT,
                    knowledge_base_id BIGINT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_sql_diagnosis_user ON sql_diagnosis(user_id, created_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_usage (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    scene VARCHAR(50) NOT NULL,
                    model VARCHAR(100),
                    prompt_tokens INT NOT NULL DEFAULT 0,
                    completion_tokens INT NOT NULL DEFAULT 0,
                    estimated_cost NUMERIC(12, 6) NOT NULL DEFAULT 0,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_model_usage_user ON model_usage(user_id, created_time)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS embedding_cache (
                    content_hash VARCHAR(64) PRIMARY KEY,
                    embedding vector(%d),
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.formatted(dimensions));
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS team (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL UNIQUE,
                    description VARCHAR(500),
                    created_by BIGINT,
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS team_member (
                    team_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
                    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (team_id, user_id)
                )
                """);
        jdbcTemplate.execute("""
                INSERT INTO team (name, description, created_by, created_time, updated_time)
                SELECT '演示团队', '系统演示团队', id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM app_user
                WHERE username = 'demo'
                  AND NOT EXISTS (SELECT 1 FROM team WHERE name = '演示团队')
                """);
        jdbcTemplate.execute("""
                INSERT INTO team_member (team_id, user_id, role, created_time)
                SELECT t.id, u.id, 'OWNER', CURRENT_TIMESTAMP
                FROM team t
                JOIN app_user u ON u.username = 'demo'
                WHERE t.name = '演示团队'
                ON CONFLICT (team_id, user_id) DO NOTHING
                """);
        jdbcTemplate.execute("""
                UPDATE knowledge_base
                SET team_id = (SELECT id FROM team WHERE name = '演示团队')
                WHERE team_id IS NULL
                  AND created_by = (SELECT id FROM app_user WHERE username = 'demo')
                """);
        log.info("database initialized, embedding dimension={}", dimensions);
    }
}
