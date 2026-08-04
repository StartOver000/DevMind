import { test, expect } from '@playwright/test';

/**
 * 核心页面 E2E（免登录模式，后端 http://localhost:8080）。
 * 覆盖：导航/页面加载、知识库创建与列表、问答页交互、SQL 诊断页交互。
 * 注意：问答/诊断会触发真实模型链路（当前限流时走本地降级），耗时不确定，
 *       因此只验证页面元素与交互入口，不依赖模型返回内容。
 */

test('顶部导航包含 6 个功能入口', async ({ page }) => {
  await page.goto('/#/kb');
  const tabs = page.locator('header nav a');
  await expect(tabs).toHaveCount(6);
  await expect(tabs.nth(0)).toHaveText('知识库');
  await expect(tabs.nth(1)).toHaveText('问答');
  await expect(tabs.nth(2)).toHaveText('SQL 诊断');
  await expect(tabs.nth(3)).toHaveText('用量');
  await expect(tabs.nth(4)).toHaveText('团队');
  await expect(tabs.nth(5)).toHaveText('评估');
});

test('知识库页：创建知识库并出现在列表', async ({ page }) => {
  await page.goto('/#/kb');
  const name = `E2E库-${Date.now()}`;
  await page.getByPlaceholder('知识库名称').fill(name);
  await page.getByRole('button', { name: '创建知识库' }).click();
  // 列表出现新库
  await expect(page.locator('.kb-item').filter({ hasText: name })).toBeVisible();
});

test('问答页：输入区/知识库多选/提问按钮可用', async ({ page }) => {
  await page.goto('/#/chat');
  await expect(page.locator('.chat-form h2')).toHaveText('RAG 问答');
  // 知识库多选（至少 1 个勾选）
  const checkboxes = page.locator('.kb-check input');
  await expect(checkboxes.first()).toBeVisible();
  // 提问按钮与输入框
  await expect(page.getByRole('button', { name: '提问' })).toBeVisible();
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
