import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import RateLimitNote from '@/components/chat/RateLimitNote.vue'
import zhCN from '@/locales/zh-CN'
import { useChatStore } from '@/stores/chat'
import { useRateLimitStore } from '@/stores/rateLimit'
import { ERROR_CODE } from '@/types/api'
import type { ChatMessageView } from '@/types/chat'
import { createStreamHandler } from '@/utils/chatStreamHandler'
import { ApiError, NetworkError } from '@/utils/request'
import { readRetryAfterSeconds } from '@/utils/toolErrors'
import { RATE_LIMIT_RECOVERED_NOTE_MS } from '@/utils/uiConstants'

import { setupPinia, stubLocation } from './helpers'

/**
 * `10005` 限流（api-spec.md §7.12 / design-system.md §14.4）。
 *
 * 🔴 本 spec 守护的纪律：
 *   1. 剩余秒数**只**来自服务端 `data.retryAfterSeconds`；🔴 前端不得硬编码等待秒数
 *   2. 倒计时结束**只恢复可发送**，🔴 绝不自动发送、绝不自动重试
 *   3. 限流是"需要等待"，不是"系统故障"：内联说明，🔴 不弹 Dialog / Toast 打断
 *   4. 等待期间输入框可用、草稿保留、停止按钮不受影响
 *   5. `role="status" aria-live="polite"` 只播报首次等待与恢复
 *
 * ⚠️ api-spec §7.12 明确：**SSE 流内 `10005` 一期不可达（契约预留）**，
 * 因此本 spec 只断言"若该帧到达也能正确解析并交给限流状态"，
 * 🔴 绝不断言"必须触发"。
 */
/** 只伪造计时相关 API：保留 fetch / queueMicrotask 真实行为，避免 await 链卡死。 */
const FAKE_TIMER_OPTIONS: Parameters<typeof vi.useFakeTimers>[0] = {
  toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date', 'performance'],
}

function rateLimitError(data: unknown): ApiError {
  return new ApiError(ERROR_CODE.RATE_LIMITED, '请求过于频繁', data)
}

describe('readRetryAfterSeconds · 只信服务端下发值', () => {
  it('从 data 对象读取 retryAfterSeconds', () => {
    expect(readRetryAfterSeconds({ retryAfterSeconds: 45 })).toBe(45)
  })

  it('直接传数字同样可用（SSE error 帧形态）', () => {
    expect(readRetryAfterSeconds(30)).toBe(30)
  })

  it('小数向下取整（服务端契约为 ≥1 的整数，防御性归一）', () => {
    expect(readRetryAfterSeconds({ retryAfterSeconds: 12.9 })).toBe(12)
  })

  it('🔴 缺省 / null / 0 / 负数 / 字符串一律返回 null（绝不猜测等待时长）', () => {
    expect(readRetryAfterSeconds(undefined)).toBeNull()
    expect(readRetryAfterSeconds(null)).toBeNull()
    expect(readRetryAfterSeconds({})).toBeNull()
    expect(readRetryAfterSeconds({ retryAfterSeconds: 0 })).toBeNull()
    expect(readRetryAfterSeconds({ retryAfterSeconds: -5 })).toBeNull()
    expect(readRetryAfterSeconds({ retryAfterSeconds: '30' })).toBeNull()
    expect(readRetryAfterSeconds(0.5)).toBeNull()
  })
})

describe('rateLimit Store · 10005 倒计时与自动恢复', () => {
  beforeEach(() => {
    vi.useFakeTimers(FAKE_TIMER_OPTIONS)
    setupPinia()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('10005 + retryAfterSeconds 启动等待，剩余秒数取服务端值', () => {
    const store = useRateLimitStore()

    expect(store.startFromError(rateLimitError({ retryAfterSeconds: 30 }))).toBe(true)
    expect(store.waiting).toBe(true)
    expect(store.remainingSeconds).toBe(30)
  })

  it('倒计时按截止时间反推逐秒递减，归零后自动恢复可发送', () => {
    const store = useRateLimitStore()
    store.startFromError(rateLimitError({ retryAfterSeconds: 3 }))

    vi.advanceTimersByTime(1000)
    expect(store.remainingSeconds).toBe(2)

    vi.advanceTimersByTime(2000)
    expect(store.remainingSeconds).toBe(0)
    // 🔴 恢复只是"可以发送"，不是"已经发送"
    expect(store.waiting).toBe(false)
  })

  it('🔴 倒计时结束不产生任何请求（绝不自动重试）', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new NetworkError('不应发生的请求')))
    vi.stubGlobal('fetch', fetchMock)
    const store = useRateLimitStore()
    store.startFromError(rateLimitError({ retryAfterSeconds: 2 }))

    await vi.advanceTimersByTimeAsync(5000)

    expect(store.waiting).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('🔴 10005 未带 retryAfterSeconds 时不启动倒计时（不硬编码兜底秒数）', () => {
    const store = useRateLimitStore()

    expect(store.startFromError(rateLimitError(null))).toBe(true)
    expect(store.waiting).toBe(false)
    expect(store.remainingSeconds).toBe(0)
  })

  it('非 10005 的业务错误不触发限流（30041 属正常业务错误）', () => {
    const store = useRateLimitStore()

    expect(store.startFromError(new ApiError(ERROR_CODE.MESSAGE_TOO_LONG, '内容超长'))).toBe(false)
    expect(store.startFromError(new NetworkError('断网'))).toBe(false)
    expect(store.waiting).toBe(false)
  })

  it('reset 立即结束等待（切会话 / 重新登录）', () => {
    const store = useRateLimitStore()
    store.start(60)
    expect(store.waiting).toBe(true)

    store.reset()

    expect(store.waiting).toBe(false)
    expect(store.remainingSeconds).toBe(0)
  })

  it('🔴 限流等待期间 chat.send 不发起任何请求（保留草稿、由用户自行重发）', async () => {
    const fetchMock = vi.fn(() => Promise.reject(new NetworkError('不应发生的请求')))
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem('authorization', 'my-token')
    const rateLimit = useRateLimitStore()
    rateLimit.start(30)
    const chat = useChatStore()

    await chat.send('你好', null)

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('SSE error 帧 · 10005 契约预留路径（api-spec §7.12）', () => {
  /**
   * ⚠️ §7.12：一期**不会**在流内下发 `10005`（限流一律在建流之前触发）。
   * 因此本组只断言"若预留帧真的到达，解析层不崩且把秒数交给限流状态"，
   * 🔴 不断言"必须触发"，避免把契约预留写成必现行为。
   */
  it('error 帧 code=10005 时把 retryAfterSeconds 交给限流状态（预留路径可用）', () => {
    const onRateLimited = vi.fn()
    const handler = createStreamHandler({
      assistantClientId: 'c-1',
      getToolCalls: () => [],
      patch: () => undefined,
      pushText: () => undefined,
      pushReasoning: () => undefined,
      flushText: () => undefined,
      onConversationId: () => undefined,
      onUserMessageId: () => undefined,
      onFirstVisibleFrame: () => undefined,
      onToolCall: () => undefined,
      onRateLimited,
      onDone: () => undefined,
    })

    handler({
      type: 'error',
      code: ERROR_CODE.RATE_LIMITED,
      message: '请求过于频繁',
      retryAfterSeconds: 15,
    })

    expect(onRateLimited).toHaveBeenCalledWith(15)
  })

  it('🔴 30000+ 业务错误帧不进入限流通道（不误判为需要等待）', () => {
    const onRateLimited = vi.fn()
    const patched: Partial<ChatMessageView>[] = []
    const handler = createStreamHandler({
      assistantClientId: 'c-1',
      getToolCalls: () => [],
      patch: (_, changes) => patched.push(changes),
      pushText: () => undefined,
      pushReasoning: () => undefined,
      flushText: () => undefined,
      onConversationId: () => undefined,
      onUserMessageId: () => undefined,
      onFirstVisibleFrame: () => undefined,
      onToolCall: () => undefined,
      onRateLimited,
      onDone: () => undefined,
    })

    handler({
      type: 'error',
      code: ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED,
      message: '已达到调用轮次上限',
      retryAfterSeconds: null,
    })

    expect(onRateLimited).not.toHaveBeenCalled()
    expect(patched.at(-1)?.errorCode).toBe(ERROR_CODE.TOOL_LOOP_LIMIT_EXCEEDED)
  })
})

describe('RateLimitNote · 内联等待说明', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('等待中展示服务端剩余秒数，文案全部来自 locales', () => {
    const wrapper = mount(RateLimitNote, { props: { remainingSeconds: 25 } })

    expect(wrapper.find('.rate-note-text').text()).toBe(
      zhCN.errors.rateLimited.description.replace('{remaining}', '25'),
    )
  })

  it('🔴 独立 live region 使用 role=status + polite，且不是 Dialog / Toast', () => {
    const wrapper = mount(RateLimitNote, { props: { remainingSeconds: 10 } })

    const liveRegion = wrapper.find('[role="status"]')
    expect(liveRegion.attributes('aria-live')).toBe('polite')
    expect(liveRegion.attributes('aria-atomic')).toBe('true')
    expect(wrapper.find('.rate-note').attributes('aria-live')).toBeUndefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
  })

  it('🔴 可视秒数递减时 live region 保持不变，不逐秒读屏', async () => {
    const wrapper = mount(RateLimitNote, { props: { remainingSeconds: 10 } })
    const announced = wrapper.find('[role="status"]').text()

    await wrapper.setProps({ remainingSeconds: 9 })

    expect(wrapper.find('.rate-note-text').text()).toContain('9')
    expect(wrapper.find('[role="status"]').text()).toBe(announced)
  })

  it('未处于限流时完全不渲染（不占位、不打扰）', () => {
    const wrapper = mount(RateLimitNote, { props: { remainingSeconds: 0 } })

    expect(wrapper.find('.rate-note').exists()).toBe(false)
  })

  it('归零后交叉淡化为"可以继续发送"，短暂保留后移除', async () => {
    vi.useFakeTimers(FAKE_TIMER_OPTIONS)
    const wrapper = mount(RateLimitNote, { props: { remainingSeconds: 1 } })

    await wrapper.setProps({ remainingSeconds: 0 })
    expect(wrapper.find('.rate-note-text').text()).toBe(zhCN.errors.rateLimited.recovered)
    expect(wrapper.find('[role="status"]').text()).toBe(zhCN.errors.rateLimited.recovered)

    vi.advanceTimersByTime(RATE_LIMIT_RECOVERED_NOTE_MS)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.rate-note').exists()).toBe(false)
  })
})

describe('ChatComposer · 限流期间的可用性', () => {
  const baseProps = {
    placeholder: '输入你的问题',
    tenantId: 't-1',
    maxChars: 2000,
    minChars: 1,
    generating: false,
    disabled: false,
  }

  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('等待期间禁用发送，但输入框可用且草稿保留', async () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, rateLimitRemaining: 20 },
      attachTo: document.body,
    })
    const textarea = wrapper.find('textarea')
    await textarea.setValue('等待期间照样能打字')

    expect(textarea.attributes('disabled')).toBeUndefined()
    expect((textarea.element as HTMLTextAreaElement).value).toBe('等待期间照样能打字')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.rate-note').exists()).toBe(true)
  })

  it('🔴 等待期间提交不抛 submit 事件（回车也不发）', async () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, rateLimitRemaining: 20 },
      attachTo: document.body,
    })
    await wrapper.find('textarea').setValue('回车不该发出去')

    await wrapper.find('form').trigger('submit')

    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('剩余秒数归零后发送按钮自动恢复可用（🔴 但不自动发送）', async () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, rateLimitRemaining: 3 },
      attachTo: document.body,
    })
    await wrapper.find('textarea').setValue('等下再发')

    await wrapper.setProps({ rateLimitRemaining: 0 })

    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('生成中即使限流，停止按钮仍可点击（停止不受等待影响）', async () => {
    const wrapper = mount(ChatComposer, {
      props: { ...baseProps, generating: true, rateLimitRemaining: 20 },
      attachTo: document.body,
    })

    const stopButton = wrapper.find('button')
    expect(stopButton.attributes('disabled')).toBeUndefined()
    await stopButton.trigger('click')
    expect(wrapper.emitted('stop')).toHaveLength(1)
  })
})
