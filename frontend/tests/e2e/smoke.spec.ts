import { expect, test } from '@playwright/test'

/**
 * 骨架冒烟用例（@测试 在 M1 用真实用例替换/扩展）。
 *
 * 本地多租户验证方式见 README：
 *   后端 sys_config 中配置 tenant.dev_host_mapping = {"localhost:5173":"gift","127.0.0.1:5173":"redbook"}
 *   并把 tenant.dev_host_mapping_enabled 置为 true（🔴 仅非生产环境）。
 */
test.describe('骨架冒烟', () => {
  test('首页可访问且无 /login 路由跳转', async ({ page }) => {
    await page.goto('/')

    // 🔴 本项目不存在 /login 路由，鉴权失效必须整页跳转耶瞳 SSO
    expect(page.url()).not.toContain('/login')
    await expect(page.locator('#app')).toBeVisible()
  })
})
