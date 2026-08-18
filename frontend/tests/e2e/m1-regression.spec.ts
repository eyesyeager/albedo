import { expect, test, type Page } from '@playwright/test'

const GIFT_URL = 'http://localhost:5173'
const REDBOOK_URL = 'http://127.0.0.1:5173'
const SSO_PATTERN = 'https://user.eyescode.top/**'

function result(code: number, data: unknown = null, message = code === 0 ? 'success' : 'error') {
  return { code, message, data, timestamp: Date.now() }
}

async function fulfillJson(route: Parameters<Parameters<Page['route']>[1]>[0], body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function setToken(page: Page, token = 'e2e-token') {
  await page.goto(GIFT_URL)
  await page.evaluate((value) => localStorage.setItem('authorization', value), token)
}

/**
 * M3.1：登录成功后前端会拉取每日额度（api-spec §7.15.1）。
 * 鉴权类用例只关心 token 行为，故给一个确定桩，避免真实额度接口影响判定。
 */
async function mockQuota(page: Page) {
  await page.route('**/api/v1/me/quota', (route) =>
    fulfillJson(
      route,
      result(0, {
        enabled: true,
        limit: 50,
        used: 0,
        remaining: 50,
        status: 'available',
        periodStart: new Date(Date.now() - 3_600_000).toISOString(),
        resetsAt: new Date(Date.now() + 3_600_000).toISOString(),
        timezone: 'Asia/Shanghai',
        asOf: new Date().toISOString(),
      }),
    ),
  )
}

test.describe('M1 多租户与站点体验回归', () => {
  test('REQ-TEN-001/003 REQ-CFG-001：gift 与 redbook 品牌和主题色隔离', async ({ page }) => {
    await page.goto(GIFT_URL)
    await expect(page.getByRole('heading', { name: '送礼不再纠结，告诉我对象和预算，我来给灵感。' })).toBeVisible()
    await expect(page).toHaveTitle('礼遇星球 · 送礼灵感助手')
    await expect(page.getByRole('textbox', { name: '消息输入框' })).toHaveAttribute(
      'placeholder',
      '例如：给 30 岁爱露营的朋友挑生日礼物，预算 500',
    )
    await expect(page.getByText('礼遇星球 · 内容由 AI 生成，请自行判断后再下单。')).toBeVisible()
    const giftBrand = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--color-brand').trim(),
    )

    await page.goto(REDBOOK_URL)
    await expect(page.getByRole('heading', { name: '一句话产出爆款笔记：标题、正文、话题标签一次给全。' })).toBeVisible()
    await expect(page).toHaveTitle('小红书文案助手 · Redbook')
    await expect(page.getByRole('textbox', { name: '消息输入框' })).toHaveAttribute(
      'placeholder',
      '例如：帮我写一篇城市周末咖啡探店笔记，风格轻松',
    )
    await expect(page.getByText('Redbook 助手 · AI 生成内容仅供参考，发布前请人工复核。')).toBeVisible()
    const redbookBrand = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--color-brand').trim(),
    )
    expect(redbookBrand).toBe('rgb(46, 107, 230)')
    expect(redbookBrand).not.toBe(giftBrand)
  })

  test('AC-NFR-003：375/768/1024/1440 无横向滚动且关键操作不越界', async ({ page }) => {
    await page.goto(REDBOOK_URL)
    await expect(page.getByRole('heading', { name: '一句话产出爆款笔记：标题、正文、话题标签一次给全。' })).toBeVisible()
    await expect(page.getByText('笔记写手', { exact: true }).first()).toBeVisible()

    for (const viewport of [
      { width: 375, height: 812 },
      { width: 768, height: 1024 },
      { width: 1024, height: 768 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport)
      // 等待断点切换动画收敛，避免将 topbar 正在过渡的瞬时坐标误判为横向溢出。
      await page.waitForTimeout(350)
      const layout = await page.evaluate(() => {
        const controls = [...document.querySelectorAll<HTMLElement>('button,textarea,input')].filter((element) => {
          const rect = element.getBoundingClientRect()
          return rect.width > 0 && rect.height > 0
        })
        return {
          scrollWidth: document.documentElement.scrollWidth,
          clientWidth: document.documentElement.clientWidth,
          outside: controls.filter((element) => {
            const rect = element.getBoundingClientRect()
            return rect.left < 0 || rect.right > innerWidth
          }).length,
          wideElements: [...document.querySelectorAll<HTMLElement>('body *')]
            .map((element) => {
              const rect = element.getBoundingClientRect()
              return {
                tag: element.tagName,
                className: element.className.toString().slice(0, 80),
                left: Math.round(rect.left),
                right: Math.round(rect.right),
                width: Math.round(rect.width),
              }
            })
            .filter((element) => element.left < -1 || element.right > innerWidth + 1)
            .slice(0, 10),
        }
      })
      expect(
        layout.scrollWidth,
        `${viewport.width} 视口不应横向溢出：${JSON.stringify(layout.wideElements)}`,
      ).toBeLessThanOrEqual(layout.clientWidth)
      expect(layout.outside, `${viewport.width} 视口关键控件不应越界`).toBe(0)
    }
  })

  test('AC-NFR-003：系统 Dark 偏好生效且信息同构', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' })
    await page.goto(REDBOOK_URL)
    await expect(page.getByRole('heading', { name: '一句话产出爆款笔记：标题、正文、话题标签一次给全。' })).toBeVisible()

    const theme = await page.evaluate(() => ({
      darkClass: document.documentElement.classList.contains('dark'),
      colorScheme: document.documentElement.style.colorScheme,
      background: getComputedStyle(document.body).backgroundColor,
      text: getComputedStyle(document.body).color,
    }))
    expect(theme).toEqual({
      darkClass: true,
      colorScheme: 'dark',
      background: 'rgb(14, 16, 20)',
      text: 'rgb(245, 247, 250)',
    })
  })

  for (const state of [
    { code: 30010, route: '/error/site-not-found', heading: '站点不存在' },
    { code: 30011, route: '/error/site-suspended', heading: '站点暂停服务' },
    { code: 30012, route: '/error/site-unavailable', heading: '站点配置异常' },
  ]) {
    test(`REQ-TEN-004 AC-NFR-004：站点状态 ${state.code} 无业务数据泄露`, async ({ page }) => {
      await page.route('**/api/v1/site/config', (route) => fulfillJson(route, result(state.code)))
      await page.goto(GIFT_URL)
      await expect(page).toHaveURL(new RegExp(`${state.route}$`))
      await expect(page.getByRole('heading', { name: state.heading })).toBeVisible()
      await expect(page).toHaveTitle('Albedo')
      await expect(page.getByRole('combobox', { name: '选择助手' })).toHaveCount(0)
      await expect(page.getByRole('button', { name: /登录|注册/ })).toHaveCount(0)
    })
  }

  test('REQ-AGT-004：无可用 Agent 展示原因并禁用输入、新建和发送', async ({ page }) => {
    await page.route('**/api/v1/agents', (route) =>
      fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 20 })),
    )
    await page.goto(GIFT_URL)

    await expect(page.getByText('礼遇助手正在维护，稍后再来试试。')).toHaveCount(2)
    await expect(page.getByRole('textbox', { name: '消息输入框' })).toBeDisabled()
    await expect(page.getByRole('button', { name: '发送' })).toBeDisabled()
    const sidebarToggle = page.getByRole('button', { name: '打开侧边栏' })
    if (await sidebarToggle.isVisible()) {
      await sidebarToggle.click()
    }
    await expect(page.getByRole('button', { name: '新的送礼咨询' })).toBeDisabled()
  })

  test('AC-NFR-002：Console 无 error，Network 不请求不存在业务接口', async ({ page }) => {
    const errors: string[] = []
    const apiPaths = new Set<string>()
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text())
    })
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.origin === GIFT_URL && url.pathname.startsWith('/api/')) apiPaths.add(url.pathname)
    })

    await page.goto(GIFT_URL)
    await expect(page.getByRole('heading', { name: '送礼不再纠结，告诉我对象和预算，我来给灵感。' })).toBeVisible()
    await page.waitForTimeout(300)

    expect([...apiPaths].sort()).toEqual(['/api/v1/agents', '/api/v1/site/config', '/api/v1/sys-config'])
    expect(errors, '首页不得产生 Console error').toEqual([])
  })
})

test.describe('M1 耶瞳 SSO 回归', () => {
  test('REQ-AUTH-001：未登录访问受保护页首跳 OAuth2 且参数正确', async ({ page }) => {
    let redirect = ''
    await page.route(SSO_PATTERN, async (route) => {
      redirect = route.request().url()
      await route.abort()
    })
    await page.goto(GIFT_URL)
    await page.evaluate(() => localStorage.removeItem('authorization'))
    await page.goto(`${GIFT_URL}/c/123`).catch(() => undefined)

    await expect.poll(() => redirect).toBe(
      'https://user.eyescode.top/OAuth2?clientId=361925&redirectUrl=http%3A%2F%2Flocalhost%3A5173%2Fc%2F123',
    )
  })

  test('REQ-AUTH-002：回跳 token 入库后立即清 URL，保留安全参数和 hash', async ({ page }) => {
    await mockQuota(page)
    await page.route('**/api/v1/me', (route) =>
      fulfillJson(
        route,
        result(0, {
          uid: '10086',
          nickname: '回跳用户',
          avatarUrl: '',
          tenantRole: 'END_USER',
          platformAdmin: false,
          memberStatus: 'active',
        }),
      ),
    )
    await page.goto(`${GIFT_URL}/?authorization=callback-token&utm_source=sso#anchor`)

    await expect(page).toHaveURL(`${GIFT_URL}/?utm_source=sso#anchor`)
    expect(await page.evaluate(() => localStorage.getItem('authorization'))).toBe('callback-token')
  })

  test('REQ-AUTH-003/004：请求带 token，响应头新 token 立即回写', async ({ page }) => {
    let requestToken = ''
    await mockQuota(page)
    await page.route('**/api/v1/me', async (route) => {
      requestToken = route.request().headers().authorization ?? ''
      await route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json', authorization: 'new-token-e2e' },
        body: JSON.stringify(
          result(0, {
            uid: '10086',
            nickname: '测试用户',
            avatarUrl: '',
            tenantRole: 'END_USER',
            platformAdmin: false,
            memberStatus: 'active',
          }),
        ),
      })
    })
    await page.route('**/api/v1/conversations?**', (route) =>
      fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 20 })),
    )
    await setToken(page, 'old-token-e2e')
    await page.reload()

    await expect.poll(() => requestToken).toBe('old-token-e2e')
    await expect.poll(() => page.evaluate(() => localStorage.getItem('authorization'))).toBe('new-token-e2e')
  })

  test('REQ-AUTH-004：20000~20005 清 token 并整页跳 SSO', async ({ page }) => {
    for (const code of [20000, 20001, 20002, 20003, 20004, 20005]) {
      await page.unrouteAll({ behavior: 'ignoreErrors' })
      let redirect = ''
      await page.route('**/api/v1/me', (route) => fulfillJson(route, result(code)))
      await page.route(SSO_PATTERN, async (route) => {
        redirect = route.request().url()
        await route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body>SSO</body></html>' })
      })
      await setToken(page)
      await page.reload()
      await expect.poll(() => redirect).toContain('/OAuth2?clientId=361925')

      const state = await page.context().storageState()
      const localhost = state.origins.find((origin) => origin.origin === GIFT_URL)
      expect(localhost?.localStorage.some((item) => item.name === 'authorization')).toBeFalsy()
    }
  })

  test('REQ-AUTH-004：30001 不得误清 token 或跳 SSO', async ({ page }) => {
    let redirect = ''
    await page.route('**/api/v1/me', (route) => fulfillJson(route, result(30001, null, '业务错误')))
    await page.route(SSO_PATTERN, async (route) => {
      redirect = route.request().url()
      await route.abort()
    })
    await setToken(page)
    await page.reload()
    await page.waitForTimeout(300)

    expect(page.url()).toBe(`${GIFT_URL}/`)
    expect(redirect).toBe('')
    expect(await page.evaluate(() => localStorage.getItem('authorization'))).toBe('e2e-token')
  })
})
