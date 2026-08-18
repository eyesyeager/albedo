import { expect, test, type Page, type Route } from '@playwright/test'

/**
 * 控制台 / 网络洁净度回归（BUG-20260816-001、BUG-20260816-002）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 首屏不得探测不存在的 `/favicon.ico`（index.html 必须声明中性初始 icon）
 *   2. 已被界面正确消费的业务错误（10004 / 10005）**不产生 error 级 console**
 *   3. 🔴 反向守护：真异常（网络失败 / 断流）**必须仍产生 error 级 console** ——
 *      防止后来者用"一刀切静音"的方式让本 spec 变绿，把真问题吞掉
 */
const APP_URL = 'http://localhost:5173'
const CHAT_STREAM_LOG = '[chat] 流式生成失败'

function result(code: number, data: unknown = null, message = code === 0 ? 'success' : 'error') {
  return { code, message, data, timestamp: Date.now() }
}

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

/** 收集 error 级 console（验收口径与 AC-NFR-002 一致）。 */
function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') {
      errors.push(message.text())
    }
  })
  return errors
}

async function mockLoggedInShell(page: Page): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('authorization', 'console-hygiene-token'))
  await page.route('**/api/v1/me', (route) =>
    fulfillJson(
      route,
      result(0, {
        uid: 'console-hygiene-user',
        nickname: '洁净度测试用户',
        avatarUrl: '',
        tenantRole: 'END_USER',
        platformAdmin: false,
        memberStatus: 'active',
      }),
    ),
  )
  await page.route('**/api/v1/conversations?**', (route) =>
    fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 20 })),
  )
  // M3.1：登录态会拉取每日额度；给确定桩，避免洁净度用例受真实额度接口影响。
  await page.route('**/api/v1/me/quota', (route) =>
    fulfillJson(route, {
      code: 0,
      message: 'success',
      timestamp: Date.now(),
      data: {
        enabled: true,
        limit: 50,
        used: 1,
        remaining: 49,
        status: 'available',
        periodStart: new Date(Date.now() - 3_600_000).toISOString(),
        resetsAt: new Date(Date.now() + 3_600_000).toISOString(),
        timezone: 'Asia/Shanghai',
        asOf: new Date().toISOString(),
      },
    }),
  )
  await page.route('**/api/v1/events', (route) =>
    fulfillJson(route, result(0, { accepted: 1, discarded: 0, duplicated: 0 })),
  )
}

async function sendMessage(page: Page, content: string): Promise<void> {
  const composer = page.getByRole('textbox', { name: '消息输入框' })
  await expect(composer).toBeEnabled()
  await composer.fill(content)
  await composer.press('Enter')
}

test.describe('首屏静态资源洁净度', () => {
  /**
   * ⚠️ 判定口径说明：**无头 Chromium 不会自动探测 `/favicon.ico`**，
   * 所以"页面跑完没看到 404"证明不了修复（缺陷正是在真实浏览器里才暴露的）。
   * 因此这里直接校验**服务端下发的原始 HTML**：只要文档里声明了 icon，
   * 任何浏览器都不会再回退探测默认路径 —— 这才是与缺陷同源的断言。
   */
  test('服务端下发的 HTML 已声明中性初始 icon，且占位可被浏览器解码', async ({ page }) => {
    const html = await page.request.get(APP_URL).then((response) => response.text())

    expect(html, '首屏文档必须声明 icon，否则浏览器回退探测 /favicon.ico').toMatch(
      /<link[\s\S]*?rel="icon"/,
    )
    // 🔴 占位必须中性且零额外请求；🔴 id 必须与 useSiteBranding 的 FAVICON_ID 一致
    const href = html.match(/href="(data:image\/[^"]+)"/)?.[1] ?? ''
    expect(href).not.toBe('')
    expect(html).toContain('id="albedo-favicon"')
    expect(html).not.toMatch(/rel="icon"[\s\S]*?(gift|redbook)/i)

    // 🔴 占位必须真的能解码：解码失败的 icon 会让浏览器继续回退探测 /favicon.ico
    await page.goto(APP_URL)
    const size = await page.evaluate(async (dataUri) => {
      const image = new Image()
      image.src = dataUri
      await image.decode()
      return `${image.naturalWidth}x${image.naturalHeight}`
    }, href)
    expect(size).toBe('16x16')

    // BUG-20260816-003：静态服务器可能把缺失资源 SPA fallback 为 200 text/html。
    // 只断言状态码会漏检，必须同时校验 siteConfig 下发 URL 的真实 Content-Type。
    for (const origin of ['http://localhost:5173', 'http://127.0.0.1:5173']) {
      const configResponse = await page.request.get(`${origin}/api/v1/site/config`)
      expect(configResponse.status(), `${origin} 站点配置 HTTP 状态`).toBe(200)
      const payload = (await configResponse.json()) as {
        code: number
        data: { tenantId: string; logoUrl: string; faviconUrl: string }
      }
      expect(payload.code, `${origin} 站点配置业务码`).toBe(0)

      for (const [field, assetUrl] of [
        ['logoUrl', payload.data.logoUrl],
        ['faviconUrl', payload.data.faviconUrl],
      ] as const) {
        expect(assetUrl, `${payload.data.tenantId}.${field} 不得为空`).not.toBe('')
        const assetResponse = await page.request.get(new URL(assetUrl, origin).toString())
        expect(assetResponse.status(), `${payload.data.tenantId}.${field} HTTP 状态`).toBe(200)
        expect(
          assetResponse.headers()['content-type'] ?? '',
          `${payload.data.tenantId}.${field} 不得被 SPA fallback 为 text/html`,
        ).toMatch(/^image\//)
        expect(
          (await assetResponse.body()).byteLength,
          `${payload.data.tenantId}.${field} 不得为空文件`,
        ).toBeGreaterThan(0)
      }
    }
  })

  test('首屏无 404、console error 为 0，且 icon 节点唯一（运行时替换后仍唯一）', async ({ page }) => {
    const errors = collectConsoleErrors(page)
    const notFound: string[] = []
    page.on('response', (response) => {
      const url = new URL(response.url())
      if (response.status() === 404 && url.origin === APP_URL) {
        notFound.push(`${response.status()} ${url.pathname}`)
      }
    })

    await page.goto(APP_URL)
    await page.waitForLoadState('networkidle')

    // 🔴 占位 + 运行时替换必须复用同一节点，否则浏览器可能仍显示占位图
    await expect(page.locator('link[rel="icon"]')).toHaveCount(1)
    await expect(page.locator('link#albedo-favicon[rel="icon"]')).toHaveCount(1)
    expect(notFound, '首屏不得出现 404').toEqual([])
    expect(errors, '首屏不得产生 Console error').toEqual([])
  })
})

test.describe('业务错误的 console 分级', () => {
  test('10004 已渲染失败态：不产生 error 级 console', async ({ page }) => {
    const errors = collectConsoleErrors(page)
    await mockLoggedInShell(page)
    await page.route('**/api/v1/conversations/conv-missing', (route) =>
      fulfillJson(route, result(10004, null, '资源不存在')),
    )
    await page.route('**/api/v1/conversations/conv-missing/messages**', (route) =>
      fulfillJson(route, result(10004, null, '资源不存在')),
    )

    await page.goto(`${APP_URL}/c/conv-missing`)

    // 功能行为不变：失败态 + 重试入口
    await expect(page.locator('.state-block--danger .state-block-title')).toHaveText('资源不存在')
    await expect(page.locator('.state-block--danger').getByRole('button')).toBeVisible()
    expect(errors, '已被界面消费的 10004 不得记 error').toEqual([])
  })

  test('10005 已展示倒计时：不产生 error 级 console', async ({ page }) => {
    const errors = collectConsoleErrors(page)
    await mockLoggedInShell(page)
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      fulfillJson(route, result(10005, { retryAfterSeconds: 37 }, '请求频率超限')),
    )

    await page.goto(APP_URL)
    await sendMessage(page, '触发限流')

    // 功能行为不变：秒数只来自服务端 retryAfterSeconds
    await expect(page.getByText('请等待 37 秒后再发送', { exact: true }).first()).toBeVisible()
    expect(errors, '已被限流倒计时消费的 10005 不得记 error').toEqual([])
  })

  test('🔴 反向守护：网络断流仍记 error（不得一刀切静音真问题）', async ({ page }) => {
    const errors = collectConsoleErrors(page)
    await mockLoggedInShell(page)
    await page.route('**/api/v1/conversations/new/messages', (route) => route.abort('failed'))

    await page.goto(APP_URL)
    await sendMessage(page, '触发网络失败')

    await expect(page.getByText('网络异常，请检查网络后重试')).toBeVisible()
    await expect
      .poll(() => errors.filter((text) => text.includes(CHAT_STREAM_LOG)).length)
      .toBeGreaterThan(0)
  })
})
