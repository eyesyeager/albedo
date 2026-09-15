import { expect, test, type Page, type Route } from '@playwright/test'

const APP_URL = 'http://localhost:5173'

function result(code: number, data: unknown = null, message = code === 0 ? 'success' : 'error') {
  return { code, message, data, timestamp: Date.now() }
}

async function fulfillJson(route: Route, body: unknown): Promise<void> {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

async function mockLoggedInShell(page: Page, conversations: unknown[] = []): Promise<void> {
  await page.addInitScript(() => localStorage.setItem('authorization', 'reasoning-e2e-token'))
  await page.route('**/api/v1/me', (route) =>
    fulfillJson(
      route,
      result(0, {
        uid: 'reasoning-e2e-user',
        nickname: 'Reasoning 测试用户',
        avatarUrl: '',
        tenantRole: 'END_USER',
        platformAdmin: false,
        memberStatus: 'active',
      }),
    ),
  )
  await page.route('**/api/v1/conversations?**', (route) =>
    fulfillJson(route, result(0, { list: conversations, total: conversations.length, page: 1, pageSize: 20 })),
  )
  // M3.1：登录态会拉取每日额度（api-spec §7.15.1），给确定桩以隔离真实额度数据。
  await page.route('**/api/v1/me/quota', (route) =>
    fulfillJson(
      route,
      result(0, {
        enabled: true,
        limit: 50,
        used: 2,
        remaining: 48,
        status: 'available',
        periodStart: new Date(Date.now() - 3_600_000).toISOString(),
        resetsAt: new Date(Date.now() + 3_600_000).toISOString(),
        timezone: 'Asia/Shanghai',
        asOf: new Date().toISOString(),
      }),
    ),
  )
  await page.route('**/api/v1/events', (route) =>
    fulfillJson(route, result(0, { accepted: 1, discarded: 0, duplicated: 0 })),
  )
}

function installReasoningStream(page: Page): Promise<void> {
  return page.addInitScript(() => {
    const nativeFetch = window.fetch.bind(window)
    window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
      if (
        !url.includes('/api/v1/conversations/conv-reasoning-stream/messages') ||
        init?.method !== 'POST'
      ) {
        return nativeFetch(input, init)
      }

      const encoder = new TextEncoder()
      const frame = (event: string, data: Record<string, unknown>) =>
        `event:${event}\ndata:${JSON.stringify(data)}\n\n`
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode(
              frame('meta', {
                conversationId: 'conv-reasoning-stream',
                messageId: 'msg-reasoning-stream',
                agentVersion: 2,
                userMessageId: 'user-reasoning-stream',
              }) +
                frame('delta', {
                  text: '',
                  reasoning: '**不应加粗** <b>不应成为 HTML</b>，先查询当前日期。',
                }),
            ),
          )
          window.setTimeout(() => {
            controller.enqueue(
              encoder.encode(
                frame('tool', {
                  toolCallId: 'tool-datetime',
                  toolType: 'local',
                  toolKey: 'datetime_now',
                  status: 'running',
                  round: 1,
                  summary: '查询当前日期',
                  argsSummary: '{}',
                  resultSummary: '',
                  truncated: false,
                  errorCode: null,
                  retryAfterSeconds: null,
                }),
              ),
            )
          }, 700)
          window.setTimeout(() => {
            controller.enqueue(
              encoder.encode(
                frame('tool', {
                  toolCallId: 'tool-datetime',
                  toolType: 'local',
                  toolKey: 'datetime_now',
                  status: 'succeeded',
                  round: 1,
                  summary: '查询当前日期',
                  argsSummary: '{}',
                  resultSummary: '2026-08-16',
                  truncated: false,
                  errorCode: null,
                  retryAfterSeconds: null,
                }) + frame('delta', { text: '', reasoning: '工具返回后继续组织答案。' }),
              ),
            )
          }, 1400)
          window.setTimeout(() => {
            controller.enqueue(
              encoder.encode(
                frame('delta', { text: '今天是 2026 年 8 月 16 日。', reasoning: '' }) +
                  frame('done', {
                    finishReason: 'stop',
                    messageId: 'msg-reasoning-stream',
                    status: 'completed',
                    title: '日期查询',
                  }),
              ),
            )
            controller.close()
          }, 2100)
          init?.signal?.addEventListener('abort', () => controller.close(), { once: true })
        },
      })
      return new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } })
    }
  })
}

async function sendMessage(page: Page, content: string): Promise<void> {
  const composer = page.getByRole('textbox', { name: '消息输入框' })
  await expect(composer).toBeEnabled()
  await composer.fill(content)
  await composer.press('Enter')
}

test.describe('REQ-CHAT-003 思考过程、工具交替与历史回显', () => {
  test('生成中默认展开；手动折叠不被覆盖；工具嵌在单一面板且思考为纯文本', async ({ page }) => {
    const conversation = {
      conversationId: 'conv-reasoning-stream',
      title: '流式思考验收',
      titleSource: 'manual',
      agentId: '1',
      agentVersion: 2,
      status: 'active',
      messageCount: 0,
      lastMessageAt: null,
      updatedAt: '2026-08-16T10:00:00Z',
      version: 1,
    }
    await mockLoggedInShell(page, [conversation])
    await page.route('**/api/v1/conversations/conv-reasoning-stream', (route) =>
      fulfillJson(route, result(0, conversation)),
    )
    await page.route('**/api/v1/conversations/conv-reasoning-stream/messages?**', (route) =>
      fulfillJson(route, result(0, { list: [], total: 0, page: 1, pageSize: 100 })),
    )
    await installReasoningStream(page)
    await page.goto(`${APP_URL}/c/conv-reasoning-stream`)
    await sendMessage(page, '现在是几号')

    const toggle = page.getByRole('button', { name: '正在思考…' })
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const reasoningText = page.locator('.reasoning-text').first()
    await expect(reasoningText).toContainText('**不应加粗** <b>不应成为 HTML</b>')
    await expect(reasoningText.locator('strong, b')).toHaveCount(0)

    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(page.getByText('今天是 2026 年 8 月 16 日。')).toBeVisible()
    const completedToggle = page.getByRole('button', { name: '思考过程 · 调用 1 次工具' })
    await expect(completedToggle).toHaveAttribute('aria-expanded', 'false')

    await completedToggle.click()
    const panel = page.locator('.reasoning-panel')
    await expect(panel.getByText('datetime_now', { exact: true })).toBeVisible()
    await expect(panel.getByText('已完成', { exact: true })).toBeVisible()
    expect(
      await panel.locator(':scope > *').evaluateAll((nodes) =>
        nodes.map((node) =>
          node.classList.contains('reasoning-text') ? 'reasoning' : 'tools',
        ),
      ),
    ).toEqual(['reasoning', 'tools', 'reasoning'])
  })

  test('刷新历史会话后 reasoning、toolCalls、segments 及交替顺序完整回填', async ({ page }) => {
    const conversation = {
      conversationId: 'conv-history',
      title: '历史回显验收',
      titleSource: 'manual',
      agentId: '1',
      agentVersion: 2,
      status: 'active',
      messageCount: 2,
      lastMessageAt: '2026-08-16T10:00:00Z',
      updatedAt: '2026-08-16T10:00:00Z',
      version: 2,
    }
    await mockLoggedInShell(page, [conversation])
    await page.route('**/api/v1/conversations/conv-history', (route) =>
      fulfillJson(route, result(0, conversation)),
    )
    await page.route('**/api/v1/conversations/conv-history/messages?**', (route) =>
      fulfillJson(
        route,
        result(0, {
          list: [
            {
              messageId: 'user-history',
              role: 'user',
              content: '帮我算 128*37',
              status: 'sent',
              attemptNo: 1,
              isCurrent: true,
              createdAt: '2026-08-16T10:00:00Z',
            },
            {
              messageId: 'assistant-history',
              role: 'assistant',
              content: '结果是 4736。',
              reasoning: '先识别乘法。工具返回后核对结果。',
              status: 'completed',
              attemptNo: 1,
              isCurrent: true,
              toolCalls: [
                {
                  toolCallId: 'calculator-history',
                  toolType: 'local',
                  toolKey: 'calculator',
                  status: 'succeeded',
                  round: 1,
                  summary: '计算乘法',
                  argsSummary: '128*37',
                  resultSummary: '4736',
                  truncated: false,
                  errorCode: null,
                  retryAfterSeconds: null,
                },
              ],
              segments: [
                { round: 1, reasoning: '先识别乘法。', text: '' },
                { round: 2, reasoning: '工具返回后核对结果。', text: '结果是 4736。' },
              ],
              createdAt: '2026-08-16T10:00:01Z',
            },
          ],
          total: 2,
          page: 1,
          pageSize: 100,
        }),
      ),
    )

    for (let attempt = 0; attempt < 2; attempt += 1) {
      await page.goto(`${APP_URL}/c/conv-history`)
      await expect(page.getByText('结果是 4736。')).toBeVisible()
      await page.getByRole('button', { name: '思考过程 · 调用 1 次工具' }).click()
      const panel = page.locator('.reasoning-panel')
      await expect(panel.getByText('calculator', { exact: true })).toBeVisible()
      expect(
        await panel.locator(':scope > *').evaluateAll((nodes) =>
          nodes.map((node) =>
            node.classList.contains('reasoning-text') ? 'reasoning' : 'tools',
          ),
        ),
      ).toEqual(['reasoning', 'tools', 'reasoning'])
    }
  })

  test('REQ-LMT-001：建流前 10005 展示服务端剩余秒数', async ({ page }) => {
    await mockLoggedInShell(page)
    await page.route('**/api/v1/conversations/new/messages', (route) =>
      fulfillJson(route, result(10005, { retryAfterSeconds: 37 }, '请求频率超限')),
    )
    await page.goto(APP_URL)
    await sendMessage(page, '触发限流')

    await expect(page.getByText('发送太频繁了', { exact: true })).toBeVisible()
    await expect(page.getByText('请等待 37 秒后再发送', { exact: true }).first()).toBeVisible()
    await expect(page.getByRole('button', { name: '发送' })).toBeDisabled()
  })
})
