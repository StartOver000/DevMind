-- V2: app_user 记录最近登录时间（登录安全/审计：与 LoginLockedBurst 告警配套的活跃度数据）
--
-- 说明：
-- 1) 本脚本由 SchemaMigrator 按版本应用一次，结果记录在 schema_version 表；
-- 2) 项目双实例（app/app2）可能同时启动并并发执行同一迁移，DDL 用 IF NOT EXISTS 保证并发安全
--    （应用次数由 schema_version 唯一约束兜底）；
-- 3) 已应用的迁移脚本禁止修改（校验和漂移检测会拒绝启动），新变更请新增 V3/V4... 脚本。
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS last_login_time TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_app_user_last_login ON app_user(last_login_time);
