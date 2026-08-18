import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import { resetAnnouncements } from '@/composables/useLiveAnnouncer'
import { useToolConfirmStore } from '@/stores/toolConfirm'
import type { ToolCallSummary } from '@/types/tool'
import { streamChat, type ToolStreamEvent } from '@/utils/streamRequest'
import { normalizeToolCall, resolveConfirmWaitSeconds, upsertToolCall } from '@/utils/toolCall'

import { collectStreamEvents, setupPinia, sseResponse, streamOptions, stubLocation } from './helpers'

/**
 * 确认倒计时消费 `confirmExpiresInSeconds`（api-spec.md §5.2 V1.2.2 / architecture ADR-017 ③ⓑ）。
 *
 * 🔴 缺陷背景（BUG-MCP-004，test-report V4.1 P1）：后端会在 `awaiting_confirmation` 帧下发
 * **按剩余生成预算收紧后的真实剩余秒数**（如 30），而前端仍从全局
 * `sys_config: tool.confirmWaitSeconds`（120）开始递减 —— 倒计时在骗人：
 * 显示 120s，30s 后卡片就被服务端判 `timed_out`。
 *
 * 🔴 本 spec 守护的取值优先级（唯一实现 `resolveConfirmWaitSeconds`）：
 *   ① 帧内 `confirmExpiresInSeconds` 为正数 → 用它（服务端真话）
 *   ② 缺失 / null / 非正数（旧后端、历史回显、异常值）→ 回退全局 `tool.confirmWaitSeconds`
 *   ③ 两者都不可用 → 不渲染倒计时，🔴 绝不用 120 或任何替代秒数兜底
 */
const GLOBAL_WAIT_SECONDS = 120
/** 后端按剩余生成预算收紧后的实际剩余秒数（实测值） */
const FRAME_EXPIRES_SECONDS = 30
const MS_PER_SECOND = 1000

const DONE_FRAME =
  'event: done\ndata: {"finishReason":"stop","messageId":"5002","status":"completed","title":null}\n\n'

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
    confirmExpiresInSeconds: FRAME_EXPIRES_SECONDS,
    ...overrides,
  }
}

function useMonotonicFakeTimers(): void {
  vi.useFakeTimers({
    toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date', 'performance'],
  })
}

describe('confirmExpiresInSeconds · 取值优先级（resolveConfirmWaitSeconds）', () => {
  it('🔴 帧内实际剩余秒数覆盖全局 confirmWaitSeconds（缺陷主因）', () => {
    const seconds = resolveConfirmWaitSeconds(awaitingCall(), GLOBAL_WAIT_SECONDS)

    expect(seconds).toBe(FRAME_EXPIRES_SECONDS)
    expect(seconds).not.toBe(GLOBAL_WAIT_SECONDS)
  })

  it('null / 字段缺失（旧后端、历史回显）回退全局 confirmWaitSeconds', () => {
    expect(
      resolveConfirmWaitSeconds(awaitingCall({ confirmExpiresInSeconds: null }), GLOBAL_WAIT_SECONDS),
    ).toBe(GLOBAL_WAIT_SECONDS)
    // 🔴 字段整体缺失（未经 normalize 的历史载荷）等价于 null
    expect(resolveConfirmWaitSeconds({}, GLOBAL_WAIT_SECONDS)).toBe(GLOBAL_WAIT_SECONDS)
  })

  it('非正数 / 非有限数（异常值）回退全局，🔴 不产生 0 或负数倒计时', () => {
    const invalid = [0, -1, Number.NaN, Number.POSITIVE_INFINITY]

    invalid.forEach((value) => {
      expect(
        resolveConfirmWaitSeconds({ confirmExpiresInSeconds: value }, GLOBAL_WAIT_SECONDS),
      ).toBe(GLOBAL_WAIT_SECONDS)
    })
  })

  it('全局配置未下发时仍可用帧内值：服务端真话不是"前端兜底默认值"', () => {
    expect(resolveConfirmWaitSeconds(awaitingCall(), 0)).toBe(FRAME_EXPIRES_SECONDS)
    // 🔴 两者都不可用 → ≤0 表示"不渲染倒计时"，绝不返回 120
    expect(resolveConfirmWaitSeconds({ confirmExpiresInSeconds: null }, 0)).toBe(0)
  })

  it('小数秒向下取整（倒计时按整秒展示，不得四舍五入放大上限）', () => {
    expect(resolveConfirmWaitSeconds({ confirmExpiresInSeconds: 30.9 }, GLOBAL_WAIT_SECONDS)).toBe(
      30,
    )
  })
})

describe('confirmExpiresInSeconds · 解析与归一', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('🔴 SSE awaiting_confirmation 帧的 confirmExpiresInSeconds 必须透传到事件对象', async () => {
    const frame = `event: tool\ndata: ${JSON.stringify({
      toolCallId: '9001',
      toolType: 'mcp',
      toolKey: 'weather:query',
      riskLevel: 'high',
      status: 'awaiting_confirmation',
      round: 1,
      summary: 'city=上海',
      argsSummary: 'city=上海',
      resultSummary: '',
      truncated: false,
      errorCode: null,
      retryAfterSeconds: null,
      confirmExpiresInSeconds: FRAME_EXPIRES_SECONDS,
    })}\n\n`
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(sseResponse([frame, DONE_FRAME]))))
    const { events, onEvent } = collectStreamEvents()

    await streamChat(streamOptions(onEvent))

    const tool = events.find((event): event is ToolStreamEvent => event.type === 'tool')
    expect(tool?.confirmExpiresInSeconds).toBe(FRAME_EXPIRES_SECONDS)
  })

  it('归一：缺失 → null（旧后端不下发该字段时行为不变）', () => {
    expect(normalizeToolCall({}).confirmExpiresInSeconds).toBeNull()
    expect(normalizeToolCall({ confirmExpiresInSeconds: null }).confirmExpiresInSeconds).toBeNull()
    expect(
      normalizeToolCall({ confirmExpiresInSeconds: FRAME_EXPIRES_SECONDS })
        .confirmExpiresInSeconds,
    ).toBe(FRAME_EXPIRES_SECONDS)
  })

  it('原位合并：后续帧恒 null 不擦除 awaiting 帧给出的剩余秒数', () => {
    const merged = upsertToolCall(
      [awaitingCall()],
      awaitingCall({ status: 'running', confirmExpiresInSeconds: null }),
    )

    expect(merged).toHaveLength(1)
    expect(merged[0].status).toBe('running')
    expect(merged[0].confirmExpiresInSeconds).toBe(FRAME_EXPIRES_SECONDS)
  })
})

describe('confirmExpiresInSeconds · Store 截止时刻与总时长同源', () => {
  beforeEach(() => {
    resetAnnouncements()
    stubLocation()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('🔴 按帧内剩余秒数登记截止时刻（不再是全局 120s）', () => {
    useMonotonicFakeTimers()
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    const store = useToolConfirmStore()
    const startedAt = performance.now()

    store.observe(awaitingCall())

    expect(store.waitSecondsOf('9001')).toBe(FRAME_EXPIRES_SECONDS)
    expect(store.deadlineOf('9001')).toBe(startedAt + FRAME_EXPIRES_SECONDS * MS_PER_SECOND)
    // 🔴 全局值仍可读（其它调用的回退来源），但不再决定本次倒计时
    expect(store.confirmWaitSeconds).toBe(GLOBAL_WAIT_SECONDS)
  })

  it('字段缺失（历史回显 / 旧后端）回退全局 confirmWaitSeconds，行为与修复前一致', () => {
    useMonotonicFakeTimers()
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    const store = useToolConfirmStore()
    const startedAt = performance.now()

    store.observe(awaitingCall({ confirmExpiresInSeconds: null }))

    expect(store.waitSecondsOf('9001')).toBe(GLOBAL_WAIT_SECONDS)
    expect(store.deadlineOf('9001')).toBe(startedAt + GLOBAL_WAIT_SECONDS * MS_PER_SECOND)
  })

  it('全局未下发但帧内有值 → 仍按服务端真实剩余秒数倒计时', () => {
    setupPinia({})
    const store = useToolConfirmStore()

    store.observe(awaitingCall())

    expect(store.confirmWaitSeconds).toBe(0)
    expect(store.waitSecondsOf('9001')).toBe(FRAME_EXPIRES_SECONDS)
    expect(store.deadlineOf('9001')).not.toBeNull()
  })

  it('🔴 两者都不可用时不登记截止时刻、总时长为 0（不渲染倒计时，绝不用 120 兜底）', () => {
    setupPinia({})
    const store = useToolConfirmStore()

    store.observe(awaitingCall({ confirmExpiresInSeconds: null }))

    expect(store.deadlineOf('9001')).toBeNull()
    expect(store.waitSecondsOf('9001')).toBe(0)
  })

  it('重复 awaiting 帧不重置：即使新帧给出更小的剩余秒数也沿用原截止时刻', () => {
    useMonotonicFakeTimers()
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    const first = store.deadlineOf('9001')
    vi.advanceTimersByTime(3 * MS_PER_SECOND)
    store.observe(awaitingCall({ confirmExpiresInSeconds: 10 }))

    expect(store.deadlineOf('9001')).toBe(first)
    expect(store.waitSecondsOf('9001')).toBe(FRAME_EXPIRES_SECONDS)
  })

  it('终态帧释放总时长登记（不跨调用串状态）', () => {
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    store.observe(awaitingCall({ status: 'timed_out', errorCode: 30050 }))

    expect(store.deadlineOf('9001')).toBeNull()
    // 释放后回退全局值（与未登记时一致）
    expect(store.waitSecondsOf('9001')).toBe(GLOBAL_WAIT_SECONDS)
  })

  it('reset 清空总时长登记', () => {
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    const store = useToolConfirmStore()

    store.observe(awaitingCall())
    store.reset()

    expect(store.waitSecondsOf('9001')).toBe(GLOBAL_WAIT_SECONDS)
    expect(store.deadlineOf('9001')).toBeNull()
  })
})

describe('confirmExpiresInSeconds · 确认卡倒计时端到端', () => {
  beforeEach(() => {
    resetAnnouncements()
    stubLocation()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('🔴 倒计时从 30 起递减（缺陷复现锚点：绝不出现 120 / 118）', async () => {
    useMonotonicFakeTimers()
    setupPinia({
      tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS },
      display: { toolRiskLabels: { high: '高风险操作' } },
    })
    const store = useToolConfirmStore()
    store.observe(awaitingCall())

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [awaitingCall()] },
    })
    await wrapper.vm.$nextTick()

    const initial = wrapper.find('.confirm-remaining').text()
    expect(initial).toContain(String(FRAME_EXPIRES_SECONDS))
    expect(initial).not.toContain(String(GLOBAL_WAIT_SECONDS))

    vi.advanceTimersByTime(2 * MS_PER_SECOND)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-remaining').text()).toContain('28')
  })

  it('🔴 进度条从满格起（总时长与截止时刻同源，不从 25% 起跳）', async () => {
    useMonotonicFakeTimers()
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    useToolConfirmStore().observe(awaitingCall())

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [awaitingCall()] },
    })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-progress').attributes('style')).toContain('scaleX(1)')
  })

  it('🔴 归零后停在 0：不出现负数秒，也不伪造 timed_out', async () => {
    useMonotonicFakeTimers()
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    useToolConfirmStore().observe(awaitingCall())

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [awaitingCall()] },
    })
    // 远超帧内剩余秒数：必须停在 0，且不得越过 0 继续递减
    vi.advanceTimersByTime((FRAME_EXPIRES_SECONDS + 10) * MS_PER_SECOND)
    await wrapper.vm.$nextTick()

    const text = wrapper.find('.confirm-remaining').text()
    expect(text).toContain('0')
    expect(text).not.toContain('-')
    // 状态仍是等待确认（🔴 终态只以 SSE 为准）
    expect(wrapper.find('.confirm').exists()).toBe(true)
    expect(wrapper.find('.tool-bar').exists()).toBe(false)
  })

  it('🔴 历史回显不受影响：终态工具调用渲染只读状态条，不渲染任何倒计时', () => {
    setupPinia({ tool: { confirmWaitSeconds: GLOBAL_WAIT_SECONDS } })
    // 历史载荷经 normalizeToolCall 后 confirmExpiresInSeconds 恒 null
    const history = normalizeToolCall({
      toolCallId: '9001',
      toolType: 'mcp',
      toolKey: 'weather:query',
      status: 'succeeded',
      round: 1,
      resultSummary: '晴',
    })

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [history] },
    })

    expect(history.confirmExpiresInSeconds).toBeNull()
    expect(wrapper.find('.confirm-countdown').exists()).toBe(false)
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(wrapper.find('.tool-bar').exists()).toBe(true)
  })
})
