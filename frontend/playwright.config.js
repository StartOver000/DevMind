import { defineConfig } from '@playwright/test';

/**
 * E2E 配置：后端由 Docker 托管（http://localhost:8080，免登录模式）。
 * 运行：cd frontend && npx playwright test
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:8080',
    headless: true,
    viewport: { width: 1280, height: 800 }
  }
});
