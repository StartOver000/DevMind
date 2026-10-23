-- 执行期授权与操作证据链（G1/G3）：新库由 DatabaseInitializer 建表，存量库走本迁移脚本。
-- 与 DatabaseInitializer 中的 DDL 必须保持一致（baseline V1 已含最新结构，脚本只负责老库升级）。

-- G1：工具调用审计补授权结论（参数只落 SHA-256 摘要，不落原文）
ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS args_hash VARCHAR(64);
ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS decision VARCHAR(10) NOT NULL DEFAULT 'ALLOW';
ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS policy_id VARCHAR(40);

-- G3：操作证据链（审批决定 / 执行结果各一行，record_hash 由前序哈希串成链）
CREATE TABLE IF NOT EXISTS operation_evidence (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    run_id BIGINT NOT NULL,
    approval_id BIGINT,
    action VARCHAR(20) NOT NULL,
    node_title VARCHAR(500),
    decided_by VARCHAR(255),
    comment VARCHAR(1000),
    before_hash VARCHAR(64),
    after_hash VARCHAR(64),
    prev_hash VARCHAR(64) NOT NULL,
    record_hash VARCHAR(64) NOT NULL,
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_operation_evidence_run ON operation_evidence(tenant_id, run_id);
