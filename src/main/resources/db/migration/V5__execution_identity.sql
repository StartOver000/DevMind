-- 执行身份（G6）：接口工具可「以调用人身份执行」，审计记录实际执行身份。
-- 新库由 DatabaseInitializer 建表时直接带该列，存量库走本迁移脚本。

ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS executed_as VARCHAR(64) NOT NULL DEFAULT 'service';
