import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AnalyticsEvent } from '@/api/events'
import {
  ANALYTICS_EVENT,
  buildAnalyticsEvent,
  flush,
  hasForbiddenKey,
  hasSensitiveValue,
  newClientEventId,
  resetAnalyticsQueue,
  track,
} from '@/utils/analytics'
import {
  trackConversationCreate,
  trackMessageComplete,
  trackMessageSend,
  trackToolCallResult,
} from '@/utils/chatAnalytics'
import type { ToolCallSummary } from '@/types/tool'

import { jsonResponse, lastRequestBody, requestUrlAt, setupPinia, stubLocation } from './helpers'

/**
 * 产品埋点（api-spec.md §7.10.1）。
 *
 * 🔴 本 spec 守护的五条纪律：
 *   1. 事件名白名单**由后端下发**，未命中直接丢弃并 `console.error`
 *   2. 批量上限取 `observability.analyticsBatchMax`，🔴 前端不写死批量大小
 *   3. `clientEventId` 唯一且符合 `^[A-Za-z0-9_-]{8,64}$`
 *   4. 🔴 绝不上报消息正文 / token / 凭据 / 完整手机号 / 完整邮箱 / 工具输入输出正文
 *   5. 上报失败只 `console.error`，🔴 不抛出、不重试、不影响主链路
 */
const ALLOWED = [
  ANALYTICS_EVENT.messageSend,
  ANALYTICS_EVENT.messageComplete,
  ANALYTICS_EVENT.messageFirstToken,
  ANALYTICS_EVENT.conversationCreate,
  ANALYTICS_EVENT.toolCallResult,
]

const EVENTS_URL = '/api/v1/events'
const CLIENT_EVENT_ID_PATTERN = /^[A-Za-z0-9_-]{8,64}$/

interface EventsBody {
  events: AnalyticsEvent[]
}

function observability(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { analyticsEnabled: true, analyticsSampleRate: 1, analyticsBatchMax: 1, analyticsAllowedEvents: ALLOWED, ...overrides }
}

function stubFetchOk() {
  const mock = vi.fn(() =>
    Promise.resolve(
      jsonResponse({ code: 0, message: 'success', data: { accepted: 1, duplicated: 0, discarded: 0 } }),
    ),
  )
  vi.stubGlobal('fetch', mock)
  return mock
}

function toolCall(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'weather:query',
    status: 'succeeded',
    round: 1,
    summary: 'city=上海',
    argsSummary: 'city=上海，手机号 13800001111',
    resultSummary: '上海今天多云，联系人 zhang@example.com',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
    ...overrides,
  }
}

describe('analytics · 事件名白名单', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    localStorage.clear()
    stubLocation()
    resetAnalyticsQueue()
    setupPinia({ observability: observability() })
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    resetAnalyticsQueue()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('🔴 白名单外事件被丢弃并 console.error，不产生请求', async () => {
    const fetchMock = stubFetchOk()

    track('notInWhitelist', { charCount: 3 })
    await flush()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
  })

  it('🔴 白名单未下发时任何事件都不上报（前端不内置业务白名单）', async () => {
    setupPinia({ observability: observability({ analyticsAllowedEvents: undefined }) })
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 3 })
    await flush()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('白名单内事件按契约上报到 POST /api/v1/events', async () => {
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 12 })
    await flush()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(requestUrlAt(fetchMock)).toContain(EVENTS_URL)
    const body = lastRequestBody<EventsBody>(fetchMock)
    expect(body.events).toHaveLength(1)
    expect(body.events[0].eventName).toBe(ANALYTICS_EVENT.messageSend)
    expect(body.events[0].charCount).toBe(12)
  })

  it('analyticsEnabled=false / sampleRate=0 时不上报（开关只服从后端下发）', async () => {
    setupPinia({ observability: observability({ analyticsEnabled: false }) })
    const fetchMock = stubFetchOk()
    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    await flush()
    expect(fetchMock).not.toHaveBeenCalled()

    setupPinia({ observability: observability({ analyticsSampleRate: 0 }) })
    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    await flush()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('analytics · 批量上限与去重键', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
    resetAnalyticsQueue()
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    resetAnalyticsQueue()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('🔴 单批条数取后端下发的 analyticsBatchMax（下发 3 → 攒满 3 条才发一次）', async () => {
    setupPinia({ observability: observability({ analyticsBatchMax: 3 }) })
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    track(ANALYTICS_EVENT.messageSend, { charCount: 2 })
    expect(fetchMock).not.toHaveBeenCalled()

    track(ANALYTICS_EVENT.messageSend, { charCount: 3 })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    expect(lastRequestBody<EventsBody>(fetchMock).events).toHaveLength(3)
  })

  it('🔴 未下发上限时按「单条即发」处理，绝不猜测批量大小', async () => {
    setupPinia({ observability: observability({ analyticsBatchMax: undefined }) })
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    expect(lastRequestBody<EventsBody>(fetchMock).events).toHaveLength(1)
  })

  it('超过上限的事件留在队列中分批发送，不合并成超限请求', async () => {
    setupPinia({ observability: observability({ analyticsBatchMax: 2 }) })
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    track(ANALYTICS_EVENT.messageSend, { charCount: 2 })
    track(ANALYTICS_EVENT.messageSend, { charCount: 3 })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    expect(lastRequestBody<EventsBody>(fetchMock).events).toHaveLength(2)

    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(lastRequestBody<EventsBody>(fetchMock).events).toHaveLength(1)
  })

  it('🔴 clientEventId 唯一且符合服务端去重键格式', () => {
    const ids = new Set(Array.from({ length: 200 }, () => newClientEventId()))

    expect(ids.size).toBe(200)
    ids.forEach((id) => expect(id).toMatch(CLIENT_EVENT_ID_PATTERN))
  })

  it('同一事件重复采集也各自生成不同 clientEventId（服务端按 uk 去重）', async () => {
    setupPinia({ observability: observability({ analyticsBatchMax: 2 }) })
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 5 })
    track(ANALYTICS_EVENT.messageSend, { charCount: 5 })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    const [first, second] = lastRequestBody<EventsBody>(fetchMock).events
    expect(first.clientEventId).not.toBe(second.clientEventId)
    expect(first.clientEventId).toMatch(CLIENT_EVENT_ID_PATTERN)
  })

  it('上报失败只 console.error，🔴 不抛出、不重试、不影响主链路', async () => {
    setupPinia({ observability: observability() })
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const fetchMock = vi.fn(() => Promise.reject(new Error('network down')))
    vi.stubGlobal('fetch', fetchMock)

    track(ANALYTICS_EVENT.messageSend, { charCount: 1 })
    await expect(flush()).resolves.toBeUndefined()

    expect(errorSpy).toHaveBeenCalled()
    // 失败批次直接丢弃，不无限重投放大故障
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('analytics · 隐私拦截（🔴 绝不上报正文与个人信息）', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
    resetAnalyticsQueue()
    setupPinia({ observability: observability() })
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    resetAnalyticsQueue()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('禁止键名（正文 / token / 凭据 / 手机号 / 邮箱）命中即判定为不可上报', () => {
    const forbidden = [
      { messageContent: 'x' },
      { content: 'x' },
      { text: 'x' },
      { prompt: 'x' },
      { systemPrompt: 'x' },
      { skillInstruction: 'x' },
      { token: 'x' },
      { authorization: 'x' },
      { jwt: 'x' },
      { credential: 'x' },
      { apiKey: 'x' },
      { secret: 'x' },
      { password: 'x' },
      { phone: 'x' },
      { mobile: 'x' },
      { email: 'x' },
      { idCard: 'x' },
      { bankCard: 'x' },
    ]

    forbidden.forEach((payload) => expect(hasForbiddenKey(payload)).toBe(true))
    expect(hasForbiddenKey({ charCount: 1, status: 'completed' })).toBe(false)
  })

  it('值级命中手机号 / 邮箱 / 长数字即判定为敏感', () => {
    expect(hasSensitiveValue('13800001111')).toBe(true)
    expect(hasSensitiveValue('zhang@example.com')).toBe(true)
    expect(hasSensitiveValue('310101199001011234')).toBe(true)
    expect(hasSensitiveValue({ nested: 'user@mail.cn' })).toBe(true)
    expect(hasSensitiveValue(42)).toBe(false)
    expect(hasSensitiveValue('newChat')).toBe(false)
  })

  it('🔴 命中禁止键名的事件整条丢弃并 console.error', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 3, messageContent: '我的密码是 123456' } as never)
    await flush()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
  })

  it('🔴 命中敏感值的事件整条丢弃（即使键名合法）', async () => {
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { source: '13800001111' })
    await flush()

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('字段白名单外的字段被剔除，不进入请求体', () => {
    const event = buildAnalyticsEvent(
      ANALYTICS_EVENT.messageSend,
      { charCount: 6, nickname: 'Ada', extra: 1 },
      ALLOWED,
    )

    expect(event).not.toBeNull()
    expect(Object.keys(event ?? {})).not.toContain('nickname')
    expect(Object.keys(event ?? {})).not.toContain('extra')
  })

  it('雪花 ID（18~19 位）作为系统标识不被误杀', () => {
    const event = buildAnalyticsEvent(
      ANALYTICS_EVENT.conversationCreate,
      { conversationId: '1750000000000000001', agentId: '1750000000000000002', source: 'newChat' },
      ALLOWED,
    )

    expect(event?.conversationId).toBe('1750000000000000001')
    expect(event?.agentId).toBe('1750000000000000002')
  })

  it('🔴 pagePath 只取站内 path，绝不携带 query 中的 token', () => {
    stubLocation('http://localhost:5173/chat/123?authorization=leak-token#frag')

    const event = buildAnalyticsEvent(ANALYTICS_EVENT.messageSend, { charCount: 1 }, ALLOWED)

    expect(event?.pagePath).toBe('/chat/123')
    expect(JSON.stringify(event)).not.toContain('leak-token')
  })

  it('loginState 由 token 存在性推导，且请求体内不出现 token 值', async () => {
    localStorage.setItem('authorization', 'super-secret-jwt')
    const fetchMock = stubFetchOk()

    track(ANALYTICS_EVENT.messageSend, { charCount: 4 })
    await flush()

    const body = lastRequestBody<EventsBody>(fetchMock)
    expect(body.events[0].loginState).toBe('logged_in')
    expect(JSON.stringify(body)).not.toContain('super-secret-jwt')
  })

  it('🔴 trackMessageSend 只上报字符数，正文绝不出现在请求体', async () => {
    const fetchMock = stubFetchOk()
    const content = '这是一段绝对不允许被上报的用户正文'

    trackMessageSend('1750000000000000001', [...content].length)
    await flush()

    const raw = JSON.stringify(lastRequestBody<EventsBody>(fetchMock))
    expect(raw).not.toContain(content)
    expect(lastRequestBody<EventsBody>(fetchMock).events[0].charCount).toBe([...content].length)
  })

  it('🔴 trackToolCallResult 只上报 toolKey / 状态 / 错误码，不含摘要正文', async () => {
    const fetchMock = stubFetchOk()
    const call = toolCall({ status: 'failed', errorCode: 30057 })

    trackToolCallResult(call, '1750000000000000001')
    await flush()

    const body = lastRequestBody<EventsBody>(fetchMock)
    const raw = JSON.stringify(body)
    expect(body.events[0].toolKey).toBe('weather:query')
    expect(body.events[0].status).toBe('failed')
    expect(body.events[0].result).toBe('failed')
    expect(body.events[0].errorCode).toBe(30057)
    expect(raw).not.toContain(call.argsSummary)
    expect(raw).not.toContain(call.resultSummary)
    expect(raw).not.toContain('13800001111')
    expect(raw).not.toContain('zhang@example.com')
  })

  it('工具终态语义映射：succeeded→success，denied/cancelled→denied，其余→failed', () => {
    const cases: readonly [ToolCallSummary['status'], string][] = [
      ['succeeded', 'success'],
      ['denied', 'denied'],
      ['cancelled', 'denied'],
      ['timed_out', 'failed'],
      ['failed', 'failed'],
    ]
    const events: AnalyticsEvent[] = []
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    cases.forEach(([status, expected]) => {
      const built = buildAnalyticsEvent(
        ANALYTICS_EVENT.toolCallResult,
        { status, result: expected },
        ALLOWED,
      )
      expect(built?.result).toBe(expected)
      events.push(built as AnalyticsEvent)
    })

    expect(events).toHaveLength(cases.length)
    spy.mockRestore()
  })

  it('🔴 D-008 回归：tokenUsage 是契约白名单字段，不得被 token 子串匹配误杀', () => {
    const event = buildAnalyticsEvent(
      ANALYTICS_EVENT.messageComplete,
      { status: 'completed', tokenUsage: { promptTokens: 1, completionTokens: 2, totalTokens: 3 } },
      ALLOWED,
    )

    expect(event).not.toBeNull()
    expect(event?.tokenUsage).toEqual({ promptTokens: 1, completionTokens: 2, totalTokens: 3 })
  })

  it('🔴 豁免只对精确键名生效：token / tokenUsageContent 等仍整条丢弃', () => {
    expect(hasForbiddenKey({ tokenUsage: {} })).toBe(false)
    expect(hasForbiddenKey({ token: 'x' })).toBe(true)
    expect(hasForbiddenKey({ tokenUsageContent: 'x' })).toBe(true)
    expect(hasForbiddenKey({ accessToken: 'x' })).toBe(true)
  })

  it('tokenUsage 内的非数字被归零，绝不把字符串带出去', () => {
    const event = buildAnalyticsEvent(
      ANALYTICS_EVENT.messageComplete,
      { tokenUsage: { promptTokens: 'super-secret-jwt', completionTokens: 2, totalTokens: 3 } },
      ALLOWED,
    )

    expect(event?.tokenUsage).toEqual({ promptTokens: 0, completionTokens: 2, totalTokens: 3 })
    expect(JSON.stringify(event)).not.toContain('super-secret-jwt')
  })

  it('tokenUsage 只保留三个数值子字段，其他内容被剔除', async () => {
    const fetchMock = stubFetchOk()

    trackMessageComplete('1750000000000000001', 'completed', 1200, {
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
    })
    await flush()

    const body = lastRequestBody<EventsBody>(fetchMock)
    expect(body.events[0].tokenUsage).toEqual({
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
    })
    expect(body.events[0].durationMs).toBe(1200)
  })

  it('conversationCreate 上报 source，缺省 agentId 时不产生空字段', async () => {
    const fetchMock = stubFetchOk()

    trackConversationCreate('1750000000000000001', null)
    await flush()

    const event = lastRequestBody<EventsBody>(fetchMock).events[0]
    expect(event.source).toBe('newChat')
    expect(Object.keys(event)).not.toContain('agentId')
  })
})
