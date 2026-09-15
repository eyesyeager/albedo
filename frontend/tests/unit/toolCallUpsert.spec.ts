import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import type { ToolCallSummary } from '@/types/tool'
import { createStreamHandler, type StreamHandlerContext } from '@/utils/chatStreamHandler'
import type { ChatStreamEvent } from '@/utils/streamRequest'
import {
  groupToolCallsByRound,
  shouldShowRoundLabel,
  upsertToolCall,
} from '@/utils/toolCall'

import { setupPinia } from './helpers'

/**
 * 工具调用**原位更新**契约（api-spec.md §5.4 / design-system.md §14.1）。
 *
 * 🔴 为什么必须有本测试：如果每帧都往列表里追加（或整表重建），用户会看到
 * 状态条数量不断增长 / DOM 节点重建导致入场动画重放、展开状态丢失、Tab 顺序抖动。
 * 因此这里同时断言**数据层长度稳定**与**DOM 节点身份稳定**。
 */
function call(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'weather:query',
    status: 'pending',
    round: 1,
    summary: '',
    argsSummary: 'city=上海',
    resultSummary: '',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
    ...overrides,
  }
}

/** 后端实际行为：状态迁移逐帧不跳帧。 */
const TRANSITION: readonly string[] = ['pending', 'running', 'succeeded']

describe('upsertToolCall · 按 toolCallId 原位更新', () => {
  it('同一 toolCallId 的多帧只更新既有条目，长度恒为 1', () => {
    let list: ToolCallSummary[] = []
    TRANSITION.forEach((status) => {
      list = upsertToolCall(list, call({ status }))
    })

    expect(list).toHaveLength(1)
    expect(list[0].status).toBe('succeeded')
  })

  it('不同 toolCallId 各自成条，且保持首次出现顺序', () => {
    let list = upsertToolCall([], call({ toolCallId: '9001' }))
    list = upsertToolCall(list, call({ toolCallId: '9002', toolKey: 'db:query' }))
    // 先到的 9001 再次更新，不得被移到末尾
    list = upsertToolCall(list, call({ toolCallId: '9001', status: 'running' }))

    expect(list.map((item) => item.toolCallId)).toEqual(['9001', '9002'])
    expect(list[0].status).toBe('running')
  })

  it('空帧不擦除既有摘要 / toolKey / round（M1 只发 summary 的旧帧也安全）', () => {
    const initial = upsertToolCall([], call({ argsSummary: 'city=上海', round: 2 }))
    const next = upsertToolCall(
      initial,
      call({ status: 'succeeded', toolKey: '', round: 0, argsSummary: '' }),
    )

    expect(next[0].status).toBe('succeeded')
    expect(next[0].toolKey).toBe('weather:query')
    expect(next[0].round).toBe(2)
    expect(next[0].argsSummary).toBe('city=上海')
  })

  it('errorCode 缺失时沿用旧值，终态语义不会丢失', () => {
    const initial = upsertToolCall([], call({ status: 'failed', errorCode: 30057 }))
    const next = upsertToolCall(initial, call({ status: 'failed', errorCode: null }))

    expect(next[0].errorCode).toBe(30057)
  })

  it('返回新数组而非原地 mutate（保证 Vue 侦听可靠触发）', () => {
    const initial = upsertToolCall([], call())
    const next = upsertToolCall(initial, call({ status: 'running' }))

    expect(next).not.toBe(initial)
    expect(initial[0].status).toBe('pending')
  })
})

describe('多轮工具调用 · 时间线顺序', () => {
  it('按 round 分组，组间与组内均保持 SSE 到达顺序', () => {
    const calls = [
      call({ toolCallId: '9001', round: 1 }),
      call({ toolCallId: '9002', round: 1, toolKey: 'db:query' }),
      call({ toolCallId: '9003', round: 2, toolKey: 'mail:send' }),
    ]

    const groups = groupToolCallsByRound(calls)

    expect(groups.map((group) => group.round)).toEqual([1, 2])
    expect(groups[0].calls.map((item) => item.toolCallId)).toEqual(['9001', '9002'])
    expect(groups[1].calls.map((item) => item.toolCallId)).toEqual(['9003'])
  })

  it('第二轮工具帧到达后不打乱第一轮已有条目的顺序', () => {
    let list = upsertToolCall([], call({ toolCallId: '9001', round: 1 }))
    list = upsertToolCall(list, call({ toolCallId: '9003', round: 2, toolKey: 'mail:send' }))
    // 第一轮的终态帧晚于第二轮首帧到达（后端多轮时序示例）
    list = upsertToolCall(list, call({ toolCallId: '9001', round: 1, status: 'succeeded' }))

    expect(list.map((item) => item.toolCallId)).toEqual(['9001', '9003'])
    expect(groupToolCallsByRound(list).map((group) => group.round)).toEqual([1, 2])
  })

  it('只有一个已知轮次时不显示轮次标签，两轮及以上才显示', () => {
    expect(shouldShowRoundLabel(groupToolCallsByRound([call({ round: 1 })]))).toBe(false)
    expect(
      shouldShowRoundLabel(
        groupToolCallsByRound([call({ toolCallId: '9001', round: 1 }), call({ toolCallId: '9003', round: 2 })]),
      ),
    ).toBe(true)
  })

  it('轮次未知（round=0）不参与标签判定', () => {
    const groups = groupToolCallsByRound([
      call({ toolCallId: '9001', round: 0 }),
      call({ toolCallId: '9002', round: 1 }),
    ])

    expect(shouldShowRoundLabel(groups)).toBe(false)
  })
})

describe('chatStreamHandler · tool 帧应用', () => {
  function context(initial: ToolCallSummary[]): {
    ctx: StreamHandlerContext
    state: { calls: ToolCallSummary[]; patches: number; terminal: string[] }
  } {
    const state = { calls: initial, patches: 0, terminal: [] as string[] }
    const ctx: StreamHandlerContext = {
      assistantClientId: 'c_1',
      getToolCalls: () => state.calls,
      patch: (_clientId, changes) => {
        state.patches += 1
        if (changes.toolCalls !== undefined) {
          state.calls = changes.toolCalls
        }
      },
      pushText: () => undefined,
      pushReasoning: () => undefined,
      flushText: () => undefined,
      onConversationId: () => undefined,
      onUserMessageId: () => undefined,
      onFirstVisibleFrame: () => undefined,
      onToolCall: (applied, terminal) => {
        if (terminal) {
          state.terminal.push(applied.status)
        }
      },
      onRateLimited: () => undefined,
      onDone: () => undefined,
    }
    return { ctx, state }
  }

  function toolEvent(overrides: Partial<ToolCallSummary>): ChatStreamEvent {
    return { type: 'tool', ...call(overrides) }
  }

  it('逐帧状态迁移只维持一条记录，终态回调只在终态触发一次', () => {
    const { ctx, state } = context([])
    const handle = createStreamHandler(ctx)

    TRANSITION.forEach((status) => handle(toolEvent({ status })))

    expect(state.calls).toHaveLength(1)
    expect(state.calls[0].status).toBe('succeeded')
    expect(state.terminal).toEqual(['succeeded'])
  })

  it('缺 toolCallId 的工具帧被忽略并 console.error（🔴 不按索引猜测目标）', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { ctx, state } = context([])
    const handle = createStreamHandler(ctx)

    handle(toolEvent({ toolCallId: '' }))

    expect(state.calls).toHaveLength(0)
    expect(spy).toHaveBeenCalledOnce()
    spy.mockRestore()
  })
})

describe('ToolCallTimeline · DOM 原位更新', () => {
  beforeEach(() => {
    setupPinia({ display: { toolStatusLabels: {} } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('🔴 状态更新不重建 DOM 节点：节点身份与数量均稳定', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call({ status: 'pending' })] },
    })

    const before = wrapper.findAll('.tool-bar')
    expect(before).toHaveLength(1)
    const beforeEl = before[0].element

    await wrapper.setProps({ calls: [call({ status: 'running' })] })
    await wrapper.setProps({ calls: [call({ status: 'succeeded', resultSummary: '晴' })] })

    const after = wrapper.findAll('.tool-bar')
    expect(after).toHaveLength(1)
    // 🔴 同一 toolCallId 复用同一 DOM 节点（key 稳定 → 无入场动画重放、无展开态丢失）
    expect(after[0].element).toBe(beforeEl)
    expect(wrapper.text()).toContain('已完成')
  })

  it('新增第二个工具调用时，已有节点不被重建', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call({ toolCallId: '9001' })] },
    })
    const firstEl = wrapper.findAll('.tool-bar')[0].element

    await wrapper.setProps({
      calls: [call({ toolCallId: '9001' }), call({ toolCallId: '9002', toolKey: 'db:query' })],
    })

    const bars = wrapper.findAll('.tool-bar')
    expect(bars).toHaveLength(2)
    expect(bars[0].element).toBe(firstEl)
  })

  it('用户展开摘要后状态更新，展开意图不丢失（design-system §14.2.3）', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call({ status: 'running', argsSummary: 'city=上海' })] },
    })

    await wrapper.find('.tool-bar-toggle').trigger('click')
    expect(wrapper.find('.tool-bar-panel').exists()).toBe(true)

    await wrapper.setProps({ calls: [call({ status: 'succeeded', resultSummary: '晴，24℃' })] })

    expect(wrapper.find('.tool-bar-panel').exists()).toBe(true)
    expect(wrapper.text()).toContain('晴，24℃')
  })

  it('多轮时渲染轮次标签，单轮时不渲染', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call({ toolCallId: '9001', round: 1 })] },
    })
    expect(wrapper.find('.timeline-round-label').exists()).toBe(false)

    await wrapper.setProps({
      calls: [
        call({ toolCallId: '9001', round: 1 }),
        call({ toolCallId: '9003', round: 2, toolKey: 'mail:send' }),
      ],
    })

    const labels = wrapper.findAll('.timeline-round-label')
    expect(labels).toHaveLength(2)
    expect(labels[0].text()).toContain('第 1 轮')
    expect(labels[1].text()).toContain('第 2 轮')
  })

  it('无工具调用时整个时间线不渲染（不产生空容器）', () => {
    const wrapper = mount(ToolCallTimeline, { props: { messageId: '5002', calls: [] } })

    expect(wrapper.find('.timeline').exists()).toBe(false)
  })
})
