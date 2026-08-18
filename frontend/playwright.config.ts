import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E 配置。
 *
 * 说明：
 * - 用例目录 `./tests/e2e`（项目根级不存在 tests/ 目录，框架 §十）
 * - 本地多租户验证：通过 baseURL 的 Host + 后端 `sys_config: tenant.dev_host_mapping` 组合完成，
 *   如把 `localhost:5173` 映射为 gift、`127.0.0.1:5173` 映射为 redbook（见 README）
 * - 断点验收四档：375 / 768 / 1024 / 1440
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --host 0.0.0.0',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'tablet',
      use: { ...devices['Desktop Chrome'], viewport: { width: 768, height: 1024 } },
    },
    {
      name: 'mobile',
      use: { ...devices['iPhone 13'] },
    },
  ],
})
