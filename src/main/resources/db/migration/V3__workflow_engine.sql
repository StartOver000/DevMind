-- 二期·双向互联（docs/architecture/05 §3）：工作流引擎选择。
-- 引擎标记跟随 workflow 定义，缺省 builtin；未接入的引擎由代码层 fail-fast（见 WorkflowService）。
ALTER TABLE workflow ADD COLUMN IF NOT EXISTS engine VARCHAR(32) NOT NULL DEFAULT 'builtin';
