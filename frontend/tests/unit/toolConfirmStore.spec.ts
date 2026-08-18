import { effectScope, nextTick, watch } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { liveAnnouncement, resetAnnouncements } from '@/composables/useLiveAnnouncer'
import zhCN from '@/locales/zh-CN'
import { useToolConfirmStore } from '@/stores/toolConfirm'
import type { ToolCallSummary } from '@/types/tool'

import {
  jsonResponse,
  lastRequestBody,
  lastRequestHeaders,
  requestUrlAt,
  setupPinia,
  stubLocation,
} from './helpers'

/**
 * 高风险工具确认的接口契约与冲突矩阵（api-spec.md §7.8.2）。
 *
 * 🔴 本 spec 守护的四条纪律：
 *   1. confirm 接口**不带 `Idempotency-Key`**（幂等由服务端行锁 + 状态机保证）
 *   2. 重复同 decision（`code=0` + `replayed`）→ 静默回放，不提示、不报错
 *   3. 相反 decision（`30055`）→ 🔴 **不自动重试**，按服务端最新 `tool` 帧收敛
 *   4. 非本人 / 无待确认（`10004`）→ 记录后台错误并解锁，等 SSE 给出确定状态
 */
const CONFIRM_URL = '/api/v1/messages/5002/tool-calls/9001/confirm'

function awaitingCall(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'weather:query',
    status: 'awaiting_confirmation',
    riskLevel: 'high',
    round: 1,
    summary: 'city=上海',
    argsSummary: 'city=上海',
    resultSummary: '',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
    ...overrides,
  }
}

function confirmOk(decision: 'allow' | 'deny', replayed = false): Response {
  return jsonResponse({
    code: 0,
    message: 'success',
    data: {
      toolCallId: '9001',
      messageId: '5002',
      decision,
      status: decision === 'allow' ? 'running' : 'denied',
      decidedAt: '2026-08-13T02:20:11.000Z',
      replayed,
    },
  })
}

function stubFetch(...responses: readonly Response[]) {
  let index = 0
  const mock = vi.fn(() => {
    const response = responses[Math.min(index, responses.length - 1)]
    index += 1
    return Promise.resolve(response)
  })
  vi.stubGlobal('fetch', mock)
  return mock
}

describe('toolConfirm Store · 提交决定', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('authorization', 'my-token')
    stubLocation()
    resetAnnouncements()
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('allow 调用 confirm 接口，🔴 请求头不含 Idempotency-Key', async () => {
    const fetchMock = stubFetch(confirmOk('allow'))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(requestUrlAt(fetchMock)).toContain(CONFIRM_URL)
    const headers = lastRequestHeaders(fetchMock)
    const headerNames = Object.keys(headers).map((name) => name.toLowerCase())
    expect(headerNames).not.toContain('idempotency-key')
    expect(headers.authorization).toBe('my-token')
    expect(lastRequestBody<{ decision: string }>(fetchMock).decision).toBe('allow')
    expect(store.decided['9001']).toBe('allow')
  })

  it('deny 提交 decision=deny，并锁定后续操作', async () => {
    const fetchMock = stubFetch(confirmOk('deny'))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'deny')

    expect(lastRequestBody<{ decision: string }>(fetchMock).decision).toBe('deny')
    expect(store.decided['9001']).toBe('deny')
    expect(store.isLocked('9001')).toBe(true)
  })

  it('重复提交同一 decision（code=0 + replayed）静默回放，不报错、不打扰用户', async () => {
    stubFetch(confirmOk('allow', true))
    const store = useToolConfirmStore()

    await expect(store.submit('5002', '9001', 'allow')).resolves.toBeUndefined()

    expect(store.decided['9001']).toBe('allow')
    expect(store.conflicted['9001']).toBeUndefined()
    expect(errorSpy).not.toHaveBeenCalled()
    expect(liveAnnouncement.value).toBe('')
  })

  it('🔴 30055（相反决定）不自动重试：fetch 只发一次并按服务端状态收敛', async () => {
    const fetchMock = stubFetch(jsonResponse({ code: 30055, message: '该操作已有处理结果' }))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(store.conflicted['9001']).toBe(true)
    expect(store.decided['9001']).toBeUndefined()
    // 冲突后不允许再次提交（避免用户反复触发无效请求）
    await store.submit('5002', '9001', 'deny')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('30055 只做一次礼貌播报，且不弹窗（无 Toast / Dialog 通道）', async () => {
    stubFetch(jsonResponse({ code: 30055, message: '该操作已有处理结果' }))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(liveAnnouncement.value).toBe(zhCN.chat.toolConfirm.stateSynced)
  })

  it('10004（非本人 / 无待确认）记录后台错误并解锁，等待 SSE 给出确定状态', async () => {
    const fetchMock = stubFetch(jsonResponse({ code: 10004, message: '无待确认的工具调用' }))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.decided['9001']).toBeUndefined()
    expect(store.conflicted['9001']).toBeUndefined()
    expect(store.submitting['9001']).toBeUndefined()
    // 🔴 不是鉴权失效：token 必须保留，且不得跳登录
    expect(localStorage.getItem('authorization')).toBe('my-token')
    expect(errorSpy).toHaveBeenCalled()
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('50003（审计写入失败）解锁按钮，不伪造成功态', async () => {
    stubFetch(jsonResponse({ code: 50003, message: '系统繁忙' }))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.decided['9001']).toBeUndefined()
    expect(store.isLocked('9001')).toBe(false)
  })

  it('提交中重复点击不重复发请求（防抖动 + 防重复决定）', async () => {
    const fetchMock = stubFetch(confirmOk('allow'))
    const store = useToolConfirmStore()

    const first = store.submit('5002', '9001', 'allow')
    await store.submit('5002', '9001', 'deny')
    await first

    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('已提交决定后不再允许改判（等待 SSE 原位收敛）', async () => {
    const fetchMock = stubFetch(confirmOk('allow'))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')
    await store.submit('5002', '9001', 'deny')

    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('🔴 停止生成期间禁止提交决定（停止 ≠ 拒绝）', async () => {
    const fetchMock = stubFetch(confirmOk('deny'))
    const store = useToolConfirmStore()

    store.markStopping()
    await store.submit('5002', '9001', 'deny')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.isLocked('9001')).toBe(true)

    store.clearStopping()
    expect(store.isLocked('9001')).toBe(false)
  })

  it('messageId 或 toolCallId 缺失时不发请求（避免打到错误路径）', async () => {
    const fetchMock = stubFetch(confirmOk('allow'))
    const store = useToolConfirmStore()

    await store.submit('', '9001', 'allow')
    await store.submit('5002', '', 'allow')

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('toolConfirm Store · 倒计时截止时刻与播报', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
    resetAnnouncements()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('首次进入 awaiting_confirmation 登记截止时刻，重复帧不重置剩余时间', () => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    const first = store.deadlineOf('9001')
    expect(first).not.toBeNull()

    store.observe(awaitingCall())

    expect(store.deadlineOf('9001')).toBe(first)
  })

  it('🔴 confirmWaitSeconds 未下发时不登记截止时刻（不渲染倒计时、绝不用 120 兜底）', () => {
    setupPinia({})
    const store = useToolConfirmStore()

    store.observe(awaitingCall())

    expect(store.confirmWaitSeconds).toBe(0)
    expect(store.deadlineOf('9001')).toBeNull()
  })

  it('终态帧释放截止时刻与提交态（避免跨状态残留）', () => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    store.observe(awaitingCall({ status: 'timed_out', errorCode: 30050 }))

    expect(store.deadlineOf('9001')).toBeNull()
  })

  it('🔴 aria-live 只播报一次：同一 toolCallId 的重复 awaiting 帧不重复播报', async () => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
    const store = useToolConfirmStore()
    const spy = vi.fn()
    const scope = effectScope()
    scope.run(() => watch(liveAnnouncement, spy))

    store.observe(awaitingCall())
    store.observe(awaitingCall())
    store.observe(awaitingCall())
    await nextTick()

    expect(spy).toHaveBeenCalledOnce()
    expect(liveAnnouncement.value).toBe(zhCN.chat.toolConfirm.announcement)
    scope.stop()
  })

  it('reset 清空全部确认状态（切换会话 / 新一轮生成）', async () => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })
    stubFetch(confirmOk('allow'))
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    await store.submit('5002', '9001', 'allow')
    store.markStopping()
    store.reset()

    expect(store.deadlineOf('9001')).toBeNull()
    expect(store.decided['9001']).toBeUndefined()
    expect(store.stopping).toBe(false)
    expect(store.isLocked('9001')).toBe(false)
  })
})
