import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import { liveAnnouncement, resetAnnouncements } from '@/composables/useLiveAnnouncer'
import zhCN from '@/locales/zh-CN'
import { useChatStore } from '@/stores/chat'
import { useToolConfirmStore } from '@/stores/toolConfirm'
import type { ChatMessageView } from '@/types/chat'
import type { ToolCallSummary } from '@/types/tool'
import { applyToolCallStatus } from '@/utils/chatMessageList'
import { createAssistantPlaceholder } from '@/utils/chatMessage'

import { jsonResponse, setupPinia, stubLocation } from './helpers'

/**
 * confirm 响应回放终态 → 工具卡片立即收敛（BUG-MCP-003，api-spec §7.8.2）。
 *
 * 🔴 本 spec 守护的判定边界（错一条就是回归）：
 *   1. `replayed=true` + 终态（如 `cancelled`）→ **立即**收敛，🔴 不再等已断开的 SSE
 *   2. `replayed=true` 仍是**静默**回放：不弹提示、不写 console.error、不额外播报
 *   3. `replayed=false`（首次决定）→ 🔴 不收敛，终态仍以 SSE `tool` 帧为准（不乐观展示）
 *   4. `replayed=true` 但状态非终态（如 `running`）→ 不收敛，流仍在推进
 *   5. 🔴 已到达的终态不被回放值改写（不篡改历史、不破坏聚合口径）
 */
const CONFIRM_WAIT_SECONDS = 60

function awaitingCall(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'websearch:SearchPro',
    status: 'awaiting_confirmation',
    riskLevel: 'high',
    round: 1,
    summary: 'query=已脱敏',
    argsSummary: 'query=已脱敏',
    resultSummary: '',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
    ...overrides,
  }
}

/** confirm 成功响应（字段集合与 api-spec §7.8.2 成功响应一致）。 */
function confirmResponse(status: string, replayed: boolean): Response {
  return jsonResponse({
    code: 0,
    message: 'success',
    data: {
      toolCallId: '9001',
      messageId: '5002',
      decision: 'allow',
      status,
      decidedAt: '2026-08-13T02:20:11.000Z',
      replayed,
      auditEventId: replayed ? null : '7f1c9a40e6a14b3d8f27c095b1de73a2',
    },
  })
}

function stubFetch(response: Response) {
  const mock = vi.fn(() => Promise.resolve(response))
  vi.stubGlobal('fetch', mock)
  return mock
}

/** 装配一条带待确认工具调用的 assistant 消息，并接上时间线（走真实 Store 链路）。 */
function seedMessage(calls: readonly ToolCallSummary[]): ChatMessageView {
  const chatStore = useChatStore()
  const message = { ...createAssistantPlaceholder(), messageId: '5002', toolCalls: [...calls] }
  chatStore.messages = [message]
  return chatStore.messages[0]
}

function mountTimeline() {
  const chatStore = useChatStore()
  const host = defineComponent({
    setup() {
      return () =>
        h(ToolCallTimeline, {
          messageId: '5002',
          calls: chatStore.messages[0]?.toolCalls ?? [],
        })
    },
  })
  return mount(host)
}

describe('confirm 回放终态 · Store 层收敛判定', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('authorization', 'my-token')
    stubLocation()
    resetAnnouncements()
    setupPinia({ tool: { confirmWaitSeconds: CONFIRM_WAIT_SECONDS } })
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('🔴 replayed=true + status=cancelled：记录服务端终态并释放倒计时（不等已断开的 SSE）', async () => {
    stubFetch(confirmResponse('cancelled', true))
    const store = useToolConfirmStore()
    store.observe(awaitingCall())
    expect(store.deadlineOf('9001')).not.toBeNull()

    await store.submit('5002', '9001', 'allow')

    expect(store.syncedStatus['9001']).toBe('cancelled')
    expect(store.deadlineOf('9001')).toBeNull()
    expect(store.submitting['9001']).toBeUndefined()
  })

  it('🔴 回放收敛保持静默：不写 console.error、不额外播报（30055 才播报）', async () => {
    stubFetch(confirmResponse('cancelled', true))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(errorSpy).not.toHaveBeenCalled()
    expect(liveAnnouncement.value).toBe('')
    expect(store.conflicted['9001']).toBeUndefined()
  })

  it('🔴 replayed=false（首次决定）不收敛：终态仍以 SSE tool 帧为准', async () => {
    stubFetch(confirmResponse('denied', false))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.syncedStatus['9001']).toBeUndefined()
    expect(store.decided['9001']).toBe('allow')
  })

  it('🔴 replayed=true 但状态非终态（running）不收敛：流仍在推进', async () => {
    stubFetch(confirmResponse('running', true))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.syncedStatus['9001']).toBeUndefined()
  })

  it('响应缺失 status 字段时按"无终态可同步"处理（旧后端 / 代理兼容，不崩）', async () => {
    stubFetch(
      jsonResponse({
        code: 0,
        message: 'success',
        data: { toolCallId: '9001', messageId: '5002', decision: 'allow', replayed: true },
      }),
    )
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.syncedStatus['9001']).toBeUndefined()
    expect(store.decided['9001']).toBe('allow')
  })

  it('30055 冲突不写回放终态（以服务端既有决定为准，路径不变）', async () => {
    stubFetch(jsonResponse({ code: 30055, message: '该操作已有处理结果' }))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')

    expect(store.syncedStatus['9001']).toBeUndefined()
    expect(store.conflicted['9001']).toBe(true)
    expect(liveAnnouncement.value).toBe(zhCN.chat.toolConfirm.stateSynced)
  })

  it('reset 清空回放终态（切换会话 / 新一轮生成不串状态）', async () => {
    stubFetch(confirmResponse('cancelled', true))
    const store = useToolConfirmStore()

    await store.submit('5002', '9001', 'allow')
    store.reset()

    expect(store.syncedStatus['9001']).toBeUndefined()
  })
})

describe('confirm 回放终态 · 消息列表写回', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('authorization', 'my-token')
    stubLocation()
    resetAnnouncements()
    setupPinia({
      tool: { confirmWaitSeconds: CONFIRM_WAIT_SECONDS },
      display: { toolRiskLabels: { high: '高风险操作' } },
    })
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('🔴 点击允许后 confirm 回放 cancelled：确认卡消失并原位显示「已取消」', async () => {
    stubFetch(confirmResponse('cancelled', true))
    const message = seedMessage([awaitingCall()])
    const wrapper = mountTimeline()
    expect(wrapper.find('.confirm').exists()).toBe(true)

    // 走真实交互路径：卡片 → decide 事件 → Store.submit → 回放终态写回消息
    await wrapper.findAll('button')[1].trigger('click')
    await nextTick()
    await nextTick()

    expect(message.toolCalls[0].status).toBe('cancelled')
    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(wrapper.find('.tool-bar-status').text()).toBe(zhCN.chat.toolStatus.cancelled)
    // 🔴 不得再显示提交中的说明文案
    expect(wrapper.text()).not.toContain(zhCN.chat.toolConfirm.submitting)
    // 取消 ≠ 拒绝 ≠ 超时
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.denied)
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.timed_out)
  })

  it('🔴 replayed=false 时卡片保持等待收敛（不乐观展示成功，行为不变）', async () => {
    stubFetch(confirmResponse('denied', false))
    const message = seedMessage([awaitingCall()])
    const wrapper = mountTimeline()

    await wrapper.findAll('button')[1].trigger('click')
    await nextTick()
    await nextTick()

    expect(message.toolCalls[0].status).toBe('awaiting_confirmation')
    expect(wrapper.find('.confirm').exists()).toBe(true)
    expect(wrapper.find('.confirm-note').text()).toBe(zhCN.chat.toolConfirm.submitting)
  })

  it('只改写 status：摘要与 errorCode 等既有字段原样保留', async () => {
    stubFetch(confirmResponse('cancelled', true))
    const message = seedMessage([awaitingCall({ argsSummary: 'query=已脱敏', round: 3 })])
    mountTimeline()

    await useToolConfirmStore().submit('5002', '9001', 'allow')
    await nextTick()

    expect(message.toolCalls[0]).toMatchObject({
      status: 'cancelled',
      argsSummary: 'query=已脱敏',
      round: 3,
      errorCode: null,
    })
  })
})

describe('applyToolCallStatus · 纯函数边界', () => {
  function message(calls: readonly ToolCallSummary[]): ChatMessageView {
    return { ...createAssistantPlaceholder(), clientId: 'c1', toolCalls: [...calls] }
  }

  it('按 toolCallId 原位改写非终态行，返回需要写回的列表', () => {
    const applied = applyToolCallStatus([message([awaitingCall()])], '9001', 'cancelled')

    expect(applied?.clientId).toBe('c1')
    expect(applied?.toolCalls[0].status).toBe('cancelled')
  })

  it('🔴 本地已是终态时不被回放值改写（不篡改历史、不破坏聚合口径）', () => {
    const applied = applyToolCallStatus(
      [message([awaitingCall({ status: 'succeeded', resultSummary: 'ok' })])],
      '9001',
      'cancelled',
    )

    expect(applied).toBeNull()
  })

  it('状态相同 / 找不到调用 / 参数为空时返回 null（不产生无意义写入）', () => {
    const messages = [message([awaitingCall()])]

    expect(applyToolCallStatus(messages, '9001', 'awaiting_confirmation')).toBeNull()
    expect(applyToolCallStatus(messages, '不存在', 'cancelled')).toBeNull()
    expect(applyToolCallStatus(messages, '', 'cancelled')).toBeNull()
    expect(applyToolCallStatus(messages, '9001', '')).toBeNull()
  })
})
