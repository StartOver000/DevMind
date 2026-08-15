import { test, expect } from '@playwright/test';

/**
 * 核心页面 E2E（免登录模式，后端 http://localhost:8080）。
 * 覆盖：导航/页面加载、知识库创建与列表、问答页交互、SQL 诊断页交互。
 * 注意：问答/诊断会触发真实模型链路（当前限流时走本地降级），耗时不确定，
 *       因此只验证页面元素与交互入口，不依赖模型返回内容。
 */

test('顶部导航包含 9 个功能入口', async ({ page }) => {
  await page.goto('/#/kb');
  const tabs = page.locator('header nav a');
  await expect(tabs).toHaveCount(9);
  await expect(tabs.nth(0)).toHaveText('知识库');
  await expect(tabs.nth(1)).toHaveText('问答');
  await expect(tabs.nth(2)).toHaveText('接口');
  await expect(tabs.nth(3)).toHaveText('流程');
  await expect(tabs.nth(4)).toHaveText('技能');
  await expect(tabs.nth(5)).toHaveText('SQL 诊断');
  await expect(tabs.nth(6)).toHaveText('用量');
  await expect(tabs.nth(7)).toHaveText('团队');
  await expect(tabs.nth(8)).toHaveText('评估');
});

test('知识库页：创建知识库并出现在列表', async ({ page }) => {
  await page.goto('/#/kb');
  const name = `E2E库-${Date.now()}`;
  await page.getByPlaceholder('知识库名称').fill(name);
  await page.getByRole('button', { name: '创建知识库' }).click();
  // 列表出现新库
  await expect(page.locator('.kb-item').filter({ hasText: name })).toBeVisible();
});

test('问答页：输入区/知识库多选/发送按钮可用', async ({ page }) => {
  await page.goto('/#/chat');
  // 对话区与历史会话侧边栏
  await expect(page.locator('.chat-main')).toBeVisible();
  await expect(page.locator('.chat-sidebar')).toBeVisible();
  // 展开检索参数，知识库多选（至少 1 个勾选）
  await page.locator('.rag-params summary').click();
  const checkboxes = page.locator('.kb-check input');
  await expect(checkboxes.first()).toBeVisible();
  // 发送按钮与输入框
  await expect(page.getByRole('button', { name: '发送' })).toBeVisible();
  await expect(page.locator('textarea[placeholder*="输入问题"]')).toBeVisible();
});

test('SQL 诊断页：输入 SQL 后按钮与结果区可用', async ({ page }) => {
  await page.goto('/#/sql');
  await expect(page.locator('.sql-form h2')).toHaveText('SQL 执行计划诊断');
  const textarea = page.locator('textarea[placeholder*="SELECT"]');
  await textarea.fill('SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20');
  await expect(page.getByRole('button', { name: '开始诊断' })).toBeVisible();
  await page.getByRole('button', { name: '开始诊断' }).click();
  // 诊断中或已出结果（结果区标题出现）
  await expect(page.locator('.sql-result')).toBeVisible({ timeout: 90_000 });
});

test('流程页：运行工作流显示实时监视器并到达终态', async ({ page }) => {
  await page.goto('/#/workflows');
  const row = page.locator('tr').filter({ hasText: 'SSE演示' });
  await expect(row).toBeVisible();
  page.once('dialog', d => d.accept());
  await row.getByRole('button', { name: '运行' }).click();
  // 运行监视器出现（SSE 实时进度）
  await expect(page.locator('.run-monitor')).toBeVisible({ timeout: 10_000 });
  // 实时步骤出现（usage_query 等）
  await expect(page.locator('.rm-steps .step-row').first()).toBeVisible({ timeout: 20_000 });
  // 无审批节点，应到达终态 SUCCESS
  await expect(page.locator('.rm-result')).toContainText('SUCCESS', { timeout: 30_000 });
});

test('流程页：含审批工作流完成人工审批闭环', async ({ page }) => {
  await page.goto('/#/workflows');
  const row = page.locator('tr').filter({ hasText: '审批演示' });
  await expect(row).toBeVisible();
  page.once('dialog', d => d.accept());
  await row.getByRole('button', { name: '运行' }).click();
  await expect(page.locator('.run-monitor')).toBeVisible({ timeout: 10_000 });
  // 执行到审批节点 → 审批面板出现（human-in-the-loop）
  await expect(page.locator('.approval-panel')).toBeVisible({ timeout: 30_000 });
  // 批准 → 恢复执行 → 终态 SUCCESS
  await page.locator('.approval-card').getByRole('button', { name: '批准' }).click();
  await expect(page.locator('.rm-result')).toContainText('SUCCESS', { timeout: 45_000 });
});
