import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * M3.1 Composer 每日额度状态轨（PRD REQ-QUOTA-003/004、api-spec §7.15、design-system §15）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 🔴 匿名用户**不请求** `/api/v1/me/quota`，也不挂载状态轨（无占位、无骨架）
 *   2. 登录后展示「剩余 / 已用·总量 / 租户时区重置时刻」
 *   3. 🔴 状态切换（正常 → 用尽 / QPM）**不改变** Composer 输入面与发送按钮的 bounding box
 *   4. 🔴 `30070` → 禁用发送与 Enter，但输入与草稿保留；文案只给"当日重置"，无秒级倒计时
 *   5. 🔴 `10005` → 只给秒级等待与恢复，不出现任何阈值语义
 *   6. 四档宽度无横向滚动
 */
const APP_URL = 'http://localhost:5173'
const RAIL = '#composer-quota-rail'

function result(code: number, data: unknown = null, message = code === 0 ? 'success' : 'error') {
  return { code, message, data, timestamp: Date.now() }
}

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

/** 额度快照桩（🔴 恰 9 键，api-spec §7.15.2）。 */
function snapshot(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    enabled: true,
    limit: 50,
    used: 12,
    remaining: 38,
    status: 'available',
    periodStart: new Date(Date.now() - 3_600_000).toISOString(),
    resetsAt: new Date(Date.now() + 3_600_000).toISOString(),
    timezone: 'Asia/Shanghai',
    asOf: new Date().toISOString(),
    ...overrides,
  }
}

async function mockLoggedIn(
  page: Page,
  quota: () => Record<string, unknown>,
): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('authorization', 'quota-e2e-token'))
  await page.route('**/api/v1/me', (route) =>
    fulfillJson(
      route,
      result(0, {
        uid: 'quota-e2e-user',
        nickname: '额度测试用户',
        avatarUrl: '',
        tenantRole: 'END_USER',
        platformAdmin: false,
        memberStatus: 'active',
      }),
    ),
  )
  await page.route('**/api/v1/me/quota', (route) => fulfillJson(route, result(0, quota())))
  await page.route('**/api/v1/conversations?**', (route) =>
    fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 20 })),
  )
  await page.route('**/api/v1/analytics/events', (route) =>
    fulfillJson(route, result(0, { accepted: 1, discarded: 0, duplicated: 0 })),
  )
}

/** 模拟"另一个标签页消耗了额度"：跨标签页校准信号（stores/quota.ts）。 */
async function signalCrossTabSync(page: Page): Promise<void> {
  await page.evaluate(() =>
    window.dispatchEvent(
      new StorageEvent('storage', { key: 'albedo:quota:sync', newValue: String(Date.now()) }),
    ),
  )
}

async function composerBox(page: Page): Promise<{ shell: string; send: string; rail: number }> {
  return page.evaluate(() => {
    const round = (element: Element | null): string => {
      if (element === null) {
        return 'missing'
      }
      const rect = element.getBoundingClientRect()
      return [rect.x, rect.y, rect.width, rect.height].map((value) => Math.round(value)).join(',')
    }
    return {
      shell: round(document.querySelector('.composer-shell')),
      send: round(document.querySelector('.composer-actions button')),
      rail: Math.round(
        document.querySelector('.quota-rail-slot')?.getBoundingClientRect().height ?? 0,
      ),
    }
  })
}

test.describe('M3.1 Composer 每日额度状态轨', () => {
  test('🔴 AC-QUOTA-013：匿名不请求额度接口、不挂载状态轨', async ({ page }) => {
    const quotaRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/api/v1/me/quota')) {
        quotaRequests.push(request.url())
      }
    })

    await page.goto(APP_URL)
    await expect(page.getByRole('textbox', { name: '消息输入框' })).toBeEnabled()
    await page.waitForTimeout(500)

    expect(quotaRequests).toEqual([])
    await expect(page.locator(RAIL)).toHaveCount(0)
  })

  test('AC-QUOTA-010：展示剩余、已用/总量与租户时区重置时刻', async ({ page }) => {
    await mockLoggedIn(page, () => snapshot())
    await page.goto(APP_URL)

    const rail = page.locator(RAIL)
    await expect(rail).toBeVisible()
    const active = rail.locator('.quota-panel.is-active')
    await expect(active).toHaveClass(/quota-panel--count/)
    await expect(active).toContainText('今日剩余 38 次')
    await expect(active).toContainText('已用 12/50')
    await expect(active).toContainText('重置')
    // 🔴 四套模板恒在 DOM 中，恒只有一个激活（稳定状态轨）
    await expect(rail.locator('.quota-panel')).toHaveCount(4)
    await expect(rail.locator('.quota-panel.is-active')).toHaveCount(1)

    for (const viewport of [
      { width: 375, height: 812 },
      { width: 768, height: 1024 },
      { width: 1024, height: 768 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport)
      await page.waitForTimeout(300)
      const layout = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1)
    }
  })

  test('🔴 AC-QUOTA-010/011：跨标签页校准进入用尽态，Composer 布局零跳动', async ({ page }) => {
    let current = snapshot()
    await mockLoggedIn(page, () => current)
    await page.goto(APP_URL)
    const composer = page.getByRole('textbox', { name: '消息输入框' })
    await expect(composer).toBeEnabled()
    await expect(page.locator(`${RAIL} .quota-panel--count.is-active`)).toBeVisible()

    // 草稿先写入：用尽后必须原样保留
    await composer.fill('用尽后仍可整理问题')
    const before = await composerBox(page)

    // 另一个标签页把额度用光 → 本页收到校准信号后重取权威快照
    current = snapshot({ used: 50, remaining: 0, status: 'exhausted' })
    await signalCrossTabSync(page)

    const exhausted = page.locator(`${RAIL} .quota-panel--exhausted.is-active`)
    await expect(exhausted).toBeVisible()
    await expect(exhausted).toContainText('今日额度已用完')
    await expect(exhausted).toContainText('请联系租户管理员')
    // 🔴 用尽是"当日不可恢复"：绝不出现秒级倒计时语义
    await expect(exhausted).not.toContainText('秒后可继续')

    // 🔴 输入与草稿保留，只有发送被禁用；Enter 也不发
    await expect(composer).toBeEnabled()
    await expect(composer).toHaveValue('用尽后仍可整理问题')
    await expect(page.locator('.composer-actions button[type="submit"]')).toBeDisabled()
    await composer.press('Enter')
    await expect(composer).toHaveValue('用尽后仍可整理问题')
    await expect(page.locator('textarea')).toHaveAttribute('aria-describedby', /composer-quota-rail/)

    // 🔴 状态切换只做 opacity 交叉：输入面、发送按钮与状态轨高度完全不变
    expect(await composerBox(page)).toEqual(before)
  })

  test('🔴 AC-QUOTA-011：30070 拒绝响应自带快照，立即进入用尽态并禁用发送', async ({ page }) => {
    await mockLoggedIn(page, () => snapshot())
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      fulfillJson(
        route,
        result(
          30070,
          snapshot({ used: 50, remaining: 0, status: 'exhausted' }),
          '今日对话额度已用完',
        ),
      ),
    )
    await page.goto(APP_URL)
    const composer = page.getByRole('textbox', { name: '消息输入框' })
    await expect(composer).toBeEnabled()
    await expect(page.locator(`${RAIL} .quota-panel--count.is-active`)).toBeVisible()

    await composer.fill('触发日额度用尽')
    await composer.press('Enter')

    await expect(page.locator(`${RAIL} .quota-panel--exhausted.is-active`)).toBeVisible()
    await expect(page.locator('.composer-actions button[type="submit"]')).toBeDisabled()
    // 🔴 输入仍可编辑（只禁发送）
    await composer.fill('用尽之后照样能打字')
    await expect(composer).toHaveValue('用尽之后照样能打字')
  })

  test('🔴 AC-QUOTA-012：10005 只给秒级等待，不出现阈值与"明日重置"语义', async ({ page }) => {
    await mockLoggedIn(page, () => snapshot())
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      fulfillJson(route, result(10005, { retryAfterSeconds: 42 }, '发送太频繁')),
    )
    await page.goto(APP_URL)
    const composer = page.getByRole('textbox', { name: '消息输入框' })
    await expect(composer).toBeEnabled()

    await composer.fill('触发分钟级限流')
    await composer.press('Enter')

    const rate = page.locator(`${RAIL} .quota-panel--rate.is-active`)
    await expect(rate).toBeVisible()
    await expect(rate).toContainText('秒后可继续')
    await expect(rate).not.toContainText('每分钟')
    await expect(rate).not.toContainText('今日额度已用完')
    // QPM 期间仍展示当日额度副信息，但发送只是**临时**禁用
    await expect(rate).toContainText('已用 12/50')
    await expect(page.locator('.composer-actions button[type="submit"]')).toBeDisabled()
  })
})
