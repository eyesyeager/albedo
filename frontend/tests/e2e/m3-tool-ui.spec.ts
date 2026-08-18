import { expect, test, type Page, type Route } from '@playwright/test'

const APP_URL = 'http://localhost:5173'

function result(code: number, data: unknown = null, message = code === 0 ? 'success' : 'error') {
  return { code, message, data, timestamp: Date.now() }
}

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function mockLoggedInShell(page: Page): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('authorization', 'm3-e2e-token'))
  await page.route('**/api/v1/me', (route) =>
    fulfillJson(
      route,
      result(0, {
        uid: 'm3-e2e-user',
        nickname: 'M3 测试用户',
        avatarUrl: '',
        tenantRole: 'END_USER',
        platformAdmin: false,
        memberStatus: 'active',
      }),
    ),
  )
  // M3.1：登录态会拉取每日额度（api-spec §7.15.1）。此处给确定桩，避免用例依赖真实额度数据。
  await page.route('**/api/v1/me/quota', (route) =>
    fulfillJson(route, result(0, quotaSnapshot())),
  )
  await page.route('**/api/v1/conversations?**', (route) =>
    fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 20 })),
  )
  await page.route('**/api/v1/analytics/events', (route) =>
    fulfillJson(route, result(0, { accepted: 1, discarded: 0, duplicated: 0 })),
  )
}

/** 额度快照桩（恰 9 键，api-spec §7.15.2）。 */
function quotaSnapshot(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  const resetsAt = new Date(Date.now() + 3_600_000).toISOString()
  return {
    enabled: true,
    limit: 50,
    used: 3,
    remaining: 47,
    status: 'available',
    periodStart: new Date(Date.now() - 3_600_000).toISOString(),
    resetsAt,
    timezone: 'Asia/Shanghai',
    asOf: new Date().toISOString(),
    ...overrides,
  }
}

function sseFrame(event: string, data: Record<string, unknown>): string {
  return `event:${event}\ndata:${JSON.stringify(data)}\n\n`
}

function toolFrame(
  toolCallId: string,
  status: string,
  overrides: Record<string, unknown> = {},
): string {
  return sseFrame('tool', {
    toolCallId,
    toolType: 'local',
    toolKey: `m3:${status}`,
    riskLevel: status === 'awaiting_confirmation' ? 'high' : 'low',
    status,
    round: 1,
    summary: `${status} 摘要`,
    argsSummary: 'expression=1+1',
    resultSummary: status === 'succeeded' ? 'result=2' : '',
    truncated: false,
    errorCode: status === 'denied' ? 30050 : null,
    retryAfterSeconds: null,
    ...overrides,
  })
}

async function sendMessage(page: Page, content = '执行 M3 工具状态终验'): Promise<void> {
  const composer = page.getByRole('textbox', { name: '消息输入框' })
  await expect(composer).toBeEnabled()
  await composer.fill(content)
  await composer.press('Enter')
}

test.describe('M3 工具状态、确认与可访问性终验', () => {
  test('REQ-TOL-002 AC-TOL-002：8 态均有图标和文字，四档无横向滚动', async ({ page }) => {
    await mockLoggedInShell(page)

    const statuses = [
      'pending',
      'awaiting_confirmation',
      'running',
      'succeeded',
      'failed',
      'timed_out',
      'cancelled',
      'denied',
    ]
    const body =
      sseFrame('meta', {
        conversationId: 'conv-m3-status',
        messageId: 'msg-m3-status',
        agentVersion: 1,
        userMessageId: 'user-m3-status',
      }) + statuses.map((status, index) => toolFrame(`tool-${index + 1}`, status)).join('')

    await page.route('**/api/v1/conversations/new/messages', (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body }),
    )
    await page.goto(APP_URL)
    await expect(page.getByRole('textbox', { name: '消息输入框' })).toBeEnabled()
    await sendMessage(page)

    for (const label of ['排队中', '执行中', '已完成', '执行失败', '已超时', '已取消', '已拒绝']) {
      await expect(page.getByText(label, { exact: true })).toBeVisible()
    }
    const confirmCard = page.getByRole('group', { name: /需要你确认后才会执行/ })
    await expect(confirmCard).toBeVisible()
    await expect(confirmCard.getByText('高风险', { exact: true })).toBeVisible()
    await expect(page.locator('svg.tool-bar-icon')).toHaveCount(7)
    await expect(confirmCard.locator('svg.confirm-title-icon')).toHaveCount(1)

    for (const viewport of [
      { width: 375, height: 812 },
      { width: 768, height: 1024 },
      { width: 1024, height: 768 },
      { width: 1440, height: 900 },
    ]) {
      await page.setViewportSize(viewport)
      // 等待断点切换动画收敛后再量测，避免读取到侧栏/主栏正在过渡的瞬时坐标。
      await page.waitForTimeout(350)
      const layout = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        confirmHeight: document.querySelector<HTMLElement>('.confirm')?.getBoundingClientRect().height ?? 0,
        buttons: [...document.querySelectorAll<HTMLElement>('.confirm-actions button')].map((button) => {
          const rect = button.getBoundingClientRect()
          return { left: rect.left, right: rect.right, height: rect.height }
        }),
      }))
      expect(layout.scrollWidth, `${viewport.width}px 不得出现页面级横向滚动`).toBeLessThanOrEqual(
        layout.clientWidth,
      )
      expect(layout.confirmHeight).toBeGreaterThan(0)
      expect(layout.buttons).toHaveLength(2)
      for (const button of layout.buttons) {
        expect(button.left).toBeGreaterThanOrEqual(0)
        expect(button.right).toBeLessThanOrEqual(viewport.width)
        expect(button.height).toBeGreaterThanOrEqual(44)
      }
      await page.screenshot({
        path: `tests/e2e/__screenshots__/m3-tool-states-${viewport.width}.png`,
        fullPage: true,
      })
    }

    // WCAG 1.4.4：文本缩放到 200% 后，确认卡与关键操作仍可达且不产生页面级横向滚动。
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '200%'
    })
    await page.waitForTimeout(350)
    const zoomed = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      buttons: [...document.querySelectorAll<HTMLElement>('.confirm-actions button')].map((button) => {
        const rect = button.getBoundingClientRect()
        return { left: rect.left, right: rect.right, height: rect.height }
      }),
    }))
    expect(zoomed.scrollWidth).toBeLessThanOrEqual(zoomed.clientWidth)
    expect(zoomed.buttons).toHaveLength(2)
    for (const button of zoomed.buttons) {
      expect(button.left).toBeGreaterThanOrEqual(0)
      expect(button.right).toBeLessThanOrEqual(1440)
      expect(button.height).toBeGreaterThanOrEqual(44)
    }
    await page.screenshot({ path: 'tests/e2e/__screenshots__/m3-tool-states-200pct.png', fullPage: true })
  })

  test('REQ-TOL-002：确认卡不抢焦点、只播报一次、Tab 拒绝→允许、Esc 不提交', async ({ page, browserName }) => {
    await mockLoggedInShell(page)
    let confirmRequests = 0

    await page.addInitScript(() => {
      const nativeFetch = window.fetch.bind(window)
      window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
        if (url.includes('/api/v1/conversations/new/messages')) {
          const encoder = new TextEncoder()
          const stream = new ReadableStream<Uint8Array>({
            start(controller) {
              const frame = (event: string, data: Record<string, unknown>) =>
                `event:${event}\ndata:${JSON.stringify(data)}\n\n`
              controller.enqueue(
                encoder.encode(
                  frame('meta', {
                    conversationId: 'conv-m3-confirm',
                    messageId: 'msg-m3-confirm',
                    agentVersion: 1,
                    userMessageId: 'user-m3-confirm',
                  }) +
                    frame('tool', {
                      toolCallId: 'tool-confirm-e2e',
                      toolType: 'local',
                      toolKey: 'payments:transfer-with-a-very-long-key-for-responsive-verification',
                      riskLevel: 'high',
                      status: 'awaiting_confirmation',
                      round: 1,
                      summary: '等待用户确认',
                      argsSummary: 'target=已脱敏账户；amount=100.00；remark=长摘要用于换行验证',
                      resultSummary: '',
                      truncated: false,
                      errorCode: null,
                      retryAfterSeconds: null,
                    }),
                ),
              )
              init?.signal?.addEventListener(
                'abort',
                () => {
                  try {
                    controller.close()
                  } catch {
                    // 页面销毁或流已关闭时无需额外动作。
                  }
                },
                { once: true },
              )
            },
          })
          return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } })
        }
        return nativeFetch(input, init)
      }
    })
    await page.route('**/api/v1/messages/msg-m3-confirm/tool-calls/tool-confirm-e2e/confirm', async (route) => {
      confirmRequests += 1
      await new Promise((resolve) => setTimeout(resolve, 200))
      await fulfillJson(
        route,
        result(0, { decision: 'deny', replayed: false, auditEventId: '0123456789abcdef0123456789abcdef' }),
      )
    })

    await page.goto(APP_URL)
    const composer = page.getByRole('textbox', { name: '消息输入框' })
    await composer.focus()
    await sendMessage(page, '请执行高风险工具')

    const confirmCard = page.getByRole('group', { name: /需要你确认后才会执行/ })
    await expect(confirmCard).toBeVisible()
    expect(
      await confirmCard.evaluate((card) => card.contains(document.activeElement)),
      '确认卡出现时不得把焦点移入卡片',
    ).toBe(false)
    /*
     * 🔴 live region 口径（design-system §14.3.3 + §15.7.1）：
     *   ① 工具/生成语义共用消息流里**唯一**的播报节点 —— 确认卡自身不得再挂 live region；
     *   ② M3.1 的额度状态轨按 §15.7.1 使用**独立且与可见倒计时解耦**的播报节点，
     *      它在本场景应当**保持静默**（额度无变化）—— 因此两个节点并存不等于"重复播报"。
     */
    const listAnnouncer = page.locator('.message-list [aria-live="polite"]')
    await expect(listAnnouncer).toHaveCount(1)
    await expect(page.locator('.confirm [aria-live]')).toHaveCount(0)
    await expect(page.locator('#composer-quota-rail [aria-live="polite"]')).toHaveText('')
    const announcement = await listAnnouncer.textContent()
    await page.waitForTimeout(1_200)
    expect(await listAnnouncer.textContent()).toBe(announcement)

    const deny = confirmCard.getByRole('button', { name: '拒绝' })
    const allow = confirmCard.getByRole('button', { name: '允许执行' })
    if (browserName !== 'webkit') {
      // iPhone WebKit 为触控项目，不提供桌面 Tab 导航；键盘顺序由 desktop/tablet Chromium 验收。
      await deny.focus()
      await page.keyboard.press('Tab')
      await expect(allow).toBeFocused()

      await page.keyboard.press('Escape')
      await expect(page.getByRole('button', { name: '停止生成' })).toBeFocused()
      expect(confirmRequests).toBe(0)
    }

    await deny.dblclick({ delay: 20 })
    await expect(deny).toBeDisabled()
    await expect(allow).toBeDisabled()
    await expect.poll(() => confirmRequests).toBe(1)
  })

  test('BUG-MCP-003：SSE 已断开时 confirm 回放 cancelled，卡片立即收敛为「已取消」', async ({ page }) => {
    await mockLoggedInShell(page)
    // 🔴 复现关键：SSE 在 awaiting_confirmation 后直接结束（无 done 帧），
    //    此后该 toolCallId 不会再有任何 tool 帧 —— 只能靠 confirm 响应收敛。
    const body =
      sseFrame('meta', {
        conversationId: 'conv-m3-replay',
        messageId: 'msg-m3-replay',
        agentVersion: 1,
        userMessageId: 'user-m3-replay',
      }) + toolFrame('tool-replay-cancelled', 'awaiting_confirmation')
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body }),
    )
    await page.route(
      '**/api/v1/messages/msg-m3-replay/tool-calls/tool-replay-cancelled/confirm',
      (route) =>
        fulfillJson(
          route,
          result(0, {
            toolCallId: 'tool-replay-cancelled',
            messageId: 'msg-m3-replay',
            decision: 'allow',
            status: 'cancelled',
            decidedAt: '2026-08-13T02:20:11.000Z',
            replayed: true,
            auditEventId: null,
          }),
        ),
    )

    await page.goto(APP_URL)
    await sendMessage(page, '第三个高风险确认')
    const confirmCard = page.getByRole('group', { name: /需要你确认后才会执行/ })
    await expect(confirmCard).toBeVisible()

    await confirmCard.getByRole('button', { name: '允许执行' }).click()

    // 🔴 无需刷新：终态直接来自 confirm 响应体
    await expect(page.getByText('已取消', { exact: true })).toBeVisible()
    await expect(page.locator('.confirm')).toHaveCount(0)
    await expect(page.getByText('正在提交你的决定…')).toHaveCount(0)
    // 取消 ≠ 拒绝 ≠ 超时
    await expect(page.getByText('已拒绝', { exact: true })).toHaveCount(0)
    await expect(page.getByText('已超时', { exact: true })).toHaveCount(0)
  })

  test('AC-NFR-001 AC-CHAT-002：普通流与确认等待 stop 均在 1s 内停止/收敛', async ({ page }) => {
    await mockLoggedInShell(page)
    await page.addInitScript(() => {
      const nativeFetch = window.fetch.bind(window)
      window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
        if (!url.includes('/api/v1/conversations/new/messages')) {
          return nativeFetch(input, init)
        }
        const encoder = new TextEncoder()
        const requestBody = typeof init?.body === 'string' ? init.body : ''
        const confirmationPath = requestBody.includes('确认等待')
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            const frame = (event: string, data: Record<string, unknown>) =>
              `event:${event}\ndata:${JSON.stringify(data)}\n\n`
            const messageId = confirmationPath ? 'msg-stop-confirm' : 'msg-stop-delta'
            controller.enqueue(
              encoder.encode(
                frame('meta', {
                  conversationId: confirmationPath ? 'conv-stop-confirm' : 'conv-stop-delta',
                  messageId,
                  agentVersion: 1,
                  userMessageId: `${messageId}-user`,
                }) +
                  (confirmationPath
                    ? frame('tool', {
                        toolCallId: 'tool-stop-confirm',
                        toolType: 'local',
                        toolKey: 'payments:stop-race',
                        riskLevel: 'high',
                        status: 'awaiting_confirmation',
                        round: 1,
                        summary: '等待确认',
                        argsSummary: 'amount=100',
                        resultSummary: '',
                        truncated: false,
                        errorCode: null,
                        retryAfterSeconds: null,
                      })
                    : frame('delta', { text: '首个可见片段' })),
              ),
            )
            let sequence = 0
            const timer = confirmationPath
              ? undefined
              : window.setInterval(() => {
                  try {
                    controller.enqueue(encoder.encode(frame('delta', { text: `-${sequence++}` })))
                  } catch {
                    window.clearInterval(timer)
                  }
                }, 25)
            init?.signal?.addEventListener(
              'abort',
              () => {
                if (timer !== undefined) window.clearInterval(timer)
                try {
                  controller.close()
                } catch {
                  // 流已关闭时无需额外动作。
                }
              },
              { once: true },
            )
          },
        })
        return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } })
      }
    })
    await page.route('**/api/v1/messages/*/stop', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 50))
      await fulfillJson(route, result(0, { status: 'stopped' }))
    })
    // stop 后 Store 会异步同步会话；避免测试 token 命中真实后端后触发 SSO，且不影响本地停止断言。
    await page.route('**/api/v1/conversations/conv-stop-*', (route) =>
      fulfillJson(route, result(30001, null, '性能专项无需同步会话详情')),
    )

    await page.goto(APP_URL)
    await sendMessage(page, '普通流停止性能')
    await expect(page.getByText(/首个可见片段/)).toBeVisible()
    const normalBegan = Date.now()
    await page.getByRole('button', { name: '停止生成' }).click()
    await expect(page.getByText('已停止生成')).toBeVisible()
    const normalStopMs = Date.now() - normalBegan
    const stoppedText = await page.getByRole('article', { name: '助手' }).textContent()
    await page.waitForTimeout(1_100)
    expect(await page.getByRole('article', { name: '助手' }).textContent()).toBe(stoppedText)

    await page.goto(APP_URL)
    await sendMessage(page, '确认等待停止性能')
    await expect(page.getByRole('group', { name: /需要你确认后才会执行/ })).toBeVisible()
    const confirmBegan = Date.now()
    await page.getByRole('button', { name: '停止生成' }).click()
    await expect(page.getByText('已取消', { exact: true })).toBeVisible()
    const confirmStopMs = Date.now() - confirmBegan

    console.log(`PERF stop normal=${normalStopMs}ms awaiting-confirmation=${confirmStopMs}ms`)
    expect(normalStopMs).toBeLessThanOrEqual(1_000)
    expect(confirmStopMs).toBeLessThanOrEqual(1_000)
  })

  test('AC-NFR-003：reduced-motion 下无位移/旋转/循环动画', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockLoggedInShell(page)
    const body =
      sseFrame('meta', {
        conversationId: 'conv-m3-motion',
        messageId: 'msg-m3-motion',
        agentVersion: 1,
        userMessageId: 'user-m3-motion',
      }) +
      toolFrame('tool-running', 'running') +
      toolFrame('tool-confirm-motion', 'awaiting_confirmation')
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      route.fulfill({ status: 200, contentType: 'text/event-stream', body }),
    )

    await page.goto(APP_URL)
    await sendMessage(page, '验证 reduced motion')
    await expect(page.locator('.confirm')).toBeVisible()
    await expect(page.locator('.tool-bar-icon--spin')).toBeVisible()

    const motion = await page.evaluate(() => {
      const confirmStyle = getComputedStyle(document.querySelector('.confirm') as Element)
      const spinnerStyle = getComputedStyle(document.querySelector('.tool-bar-icon--spin') as Element)
      const countdownStyle = getComputedStyle(document.querySelector('.confirm-progress') as Element)
      return {
        confirmAnimationName: confirmStyle.animationName,
        confirmDurationMs: Number.parseFloat(confirmStyle.animationDuration) * 1000,
        spinnerAnimationName: spinnerStyle.animationName,
        countdownTransition: countdownStyle.transitionProperty,
      }
    })
    expect(motion.confirmAnimationName).toContain('confirm-fade')
    expect(motion.confirmDurationMs).toBeLessThanOrEqual(100)
    expect(motion.spinnerAnimationName).toBe('none')
    expect(motion.countdownTransition).toBe('none')
  })
})
