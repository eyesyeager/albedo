import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'

import zhCN from '@/locales/zh-CN'
import { useChatStore } from '@/stores/chat'
import { useRateLimitStore } from '@/stores/rateLimit'
import { ERROR_CODE } from '@/types/api'
import { loadConversationSnapshot } from '@/utils/chatHistory'
import { isUiHandledError, isUiHandledErrorCode } from '@/utils/errorLevel'
import { ApiError, NetworkError } from '@/utils/request'

import { jsonResponse, setupPinia, stubLocation } from './helpers'

/**
 * 错误日志分级（BUG-20260816-001）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 已被界面正确消费的业务错误（跨租户 10004、限流 10005 等）**不产生 error 级 console**，
 *      否则"关键页面 console 零 error"这条验收项失去信号价值
 *   2. 真异常（网络失败、SSE 断流、响应非法、50000+、10001 参数违约）**必须仍报 error**，
 *      🔴 绝不允许"一刀切静音"把真问题吞掉
 *   3. 降级只改日志级别，🔴 不改变任何既有 UI 行为（失败态文案、限流倒计时保持原样）
 */
const CHAT_LOG_PREFIX = '[chat]'

/** 只取本模块产生的日志，避免埋点等无关日志干扰断言。 */
function chatLogs(spy: MockInstance): string[] {
  return spy.mock.calls
    .map((args) => String(args[0]))
    .filter((message) => message.startsWith(CHAT_LOG_PREFIX))
}

describe('isUiHandledErrorCode · 已消费业务码判定', () => {
  it('通用段已消费码（10003 / 10004 / 10005）判为已消费', () => {
    expect(isUiHandledErrorCode(ERROR_CODE.PERMISSION_DENIED)).toBe(true)
    expect(isUiHandledErrorCode(ERROR_CODE.RESOURCE_NOT_FOUND)).toBe(true)
    expect(isUiHandledErrorCode(ERROR_CODE.RATE_LIMITED)).toBe(true)
  })

  it('业务段 30000~39999 整体判为已消费（站点状态 / 版本冲突 / 工具类均有确定呈现）', () => {
    expect(isUiHandledErrorCode(ERROR_CODE.TENANT_SUSPENDED)).toBe(true)
    expect(isUiHandledErrorCode(ERROR_CODE.VERSION_CONFLICT)).toBe(true)
    expect(isUiHandledErrorCode(ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED)).toBe(true)
  })

  it('🔴 参数违约 10001 与系统异常 50000+ 不得判为已消费（必须留 error 暴露）', () => {
    expect(isUiHandledErrorCode(ERROR_CODE.VALIDATION_FAILED)).toBe(false)
    expect(isUiHandledErrorCode(ERROR_CODE.UPSTREAM_UNAVAILABLE)).toBe(false)
    expect(isUiHandledErrorCode(ERROR_CODE.INTERNAL_ERROR)).toBe(false)
  })

  it('🔴 非 ApiError（网络 / 解析 / 运行时异常）恒判为未消费', () => {
    expect(isUiHandledError(new NetworkError('断网'))).toBe(false)
    expect(isUiHandledError(new TypeError('unexpected shape'))).toBe(false)
    expect(isUiHandledError(null)).toBe(false)
    expect(isUiHandledError(new ApiError(ERROR_CODE.RESOURCE_NOT_FOUND, '资源不存在'))).toBe(true)
  })
})

describe('会话加载失败 · 日志分级不改变失败态呈现', () => {
  let errorSpy: MockInstance
  let debugSpy: MockInstance

  beforeEach(() => {
    setupPinia()
    stubLocation()
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('🔴 跨租户 10004 已渲染失败态：不报 error，仅 debug，且原样透出服务端文案', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse({ code: ERROR_CODE.RESOURCE_NOT_FOUND, message: '资源不存在' }))),
    )

    const snapshot = await loadConversationSnapshot('1670', 100)

    expect(snapshot.error).toBe('资源不存在')
    expect(snapshot.messages).toEqual([])
    expect(chatLogs(errorSpy)).toEqual([])
    expect(chatLogs(debugSpy)).toHaveLength(1)
  })

  it('🔴 网络失败仍报 error（不静音真问题），失败态文案走 locales', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('ERR_CONNECTION_FAILED'))))

    const snapshot = await loadConversationSnapshot('1670', 100)

    expect(snapshot.error).toBe(zhCN.chat.historyLoadFailed)
    expect(chatLogs(errorSpy)).toHaveLength(1)
  })

  it('🔴 50003 服务端异常仍报 error（业务段之外一律视为真故障）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse({ code: ERROR_CODE.INTERNAL_ERROR, message: '服务异常' }))),
    )

    await loadConversationSnapshot('1670', 100)

    expect(chatLogs(errorSpy)).toHaveLength(1)
  })
})

describe('流式生成失败 · 日志分级不改变限流与失败块行为', () => {
  let errorSpy: MockInstance
  let debugSpy: MockInstance

  beforeEach(() => {
    setupPinia()
    stubLocation()
    localStorage.setItem('authorization', 'my-token')
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => undefined)
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('🔴 建流前 10005：倒计时照常启动，但不产生 error 级 console', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          jsonResponse({
            code: ERROR_CODE.RATE_LIMITED,
            message: '请求频率超限',
            data: { retryAfterSeconds: 37 },
          }),
        ),
      ),
    )
    const chat = useChatStore()
    const rateLimit = useRateLimitStore()

    await chat.send('你好', null)

    // 既有行为不变：倒计时秒数仍只来自服务端 data.retryAfterSeconds
    expect(rateLimit.remainingSeconds).toBe(37)
    expect(chat.messages.at(-1)?.errorCode).toBe(ERROR_CODE.RATE_LIMITED)
    expect(chatLogs(errorSpy)).toEqual([])
    expect(chatLogs(debugSpy)).toHaveLength(1)
  })

  it('🔴 SSE 断流 / 网络失败仍报 error，并保留失败态与重试入口', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('ERR_CONNECTION_FAILED'))))
    const chat = useChatStore()

    await chat.send('你好', null)

    expect(chat.messages.at(-1)?.status).toBe('failed')
    expect(chatLogs(errorSpy)).toHaveLength(1)
  })
})
