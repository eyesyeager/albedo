import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import ToolConfirmCard from '@/components/chat/ToolConfirmCard.vue'
import { resetAnnouncements } from '@/composables/useLiveAnnouncer'
import zhCN from '@/locales/zh-CN'
import type { ToolCallSummary } from '@/types/tool'

import { requestUrlAt, setupPinia } from './helpers'

/**
 * 高风险工具确认卡片的交互与可访问性（design-system.md §14.3）。
 *
 * 🔴 本 spec 守护的不可退让行为：
 *   1. 内联在时间线内的 `role="group"`，**出现时绝不移动焦点**、不 autofocus
 *   2. DOM 与视觉均「拒绝在前、允许在后」，无默认按钮
 *   3. 倒计时归零只禁用操作 + 显示"正在同步"，🔴 绝不自行判定 `timed_out`
 *   4. 停止生成期间冻结倒计时并禁用按钮，最终按 SSE `cancelled` 原位收敛
 *   5. Esc 不提交任何决定；倒计时不进入 `aria-live`；全树仅一个 live region
 */
const WAIT_SECONDS = 60

function call(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
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

interface CardProps {
  call: ToolCallSummary
  riskLabel: string
  confirmWaitSeconds: number
  deadlineAt: number | null
  submitting: 'allow' | 'deny' | null
  decided: 'allow' | 'deny' | null
  stopping: boolean
  conflicted: boolean
}

function props(overrides: Partial<CardProps> = {}): CardProps {
  return {
    call: call(),
    riskLabel: '高风险操作',
    confirmWaitSeconds: WAIT_SECONDS,
    deadlineAt: null,
    submitting: null,
    decided: null,
    stopping: false,
    conflicted: false,
    ...overrides,
  }
}

function mountCard(overrides: Partial<CardProps> = {}) {
  return mount(ToolConfirmCard, { props: props(overrides), attachTo: document.body })
}

describe('ToolConfirmCard · 结构与决策', () => {
  beforeEach(() => {
    resetAnnouncements()
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('渲染为内联 role="group"（🔴 不是 Dialog / alertdialog / Toast）', () => {
    const wrapper = mountCard()

    // 🔴 单根断言：根元素必须直接携带 role/aria-*（Fragment 根会让父级属性丢失）
    const root = wrapper.find('section.confirm')
    expect(root.exists()).toBe(true)
    expect(wrapper.element.tagName).toBe('SECTION')
    expect(wrapper.attributes('role')).toBe('group')
    expect(wrapper.attributes('aria-labelledby')).toBe('tool-confirm-title-9001')
    expect(wrapper.attributes('aria-describedby')).toBe('tool-confirm-desc-9001')
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('🔴 Tab 顺序为「拒绝 → 允许」，且都是真实 button（防 D-007 类静默不渲染）', () => {
    const wrapper = mountCard()

    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(2)
    expect(buttons[0].text()).toBe(zhCN.chat.toolConfirm.deny)
    expect(buttons[1].text()).toBe(zhCN.chat.toolConfirm.allow)
    buttons.forEach((button) => expect(button.element.tagName).toBe('BUTTON'))
    expect(wrapper.html()).not.toContain('<appbutton')
  })

  it('🔴 出现时不 autofocus、不移动焦点', () => {
    const before = document.activeElement
    const wrapper = mountCard()

    expect(wrapper.html()).not.toContain('autofocus')
    expect(document.activeElement).toBe(before)
    // 没有任何按钮被设为默认（不存在 tabindex 抢占或 autofocus 属性）
    wrapper.findAll('button').forEach((button) => {
      expect(button.attributes('autofocus')).toBeUndefined()
      expect(button.attributes('tabindex')).toBeUndefined()
    })
  })

  it('点击拒绝 / 允许各自抛出 decide 事件（父级负责调接口）', async () => {
    const wrapper = mountCard()

    await wrapper.findAll('button')[0].trigger('click')
    await wrapper.findAll('button')[1].trigger('click')

    expect(wrapper.emitted('decide')).toEqual([['deny'], ['allow']])
  })

  it('🔴 Esc 不代表拒绝、不提交任何决定', async () => {
    const wrapper = mountCard()

    await wrapper.trigger('keydown.esc')

    expect(wrapper.emitted('decide')).toBeUndefined()
  })

  it('只展示服务端脱敏摘要，🔴 无"查看原始参数"入口', () => {
    const wrapper = mountCard()

    expect(wrapper.find('.confirm-args-text').text()).toBe('city=上海')
    expect(wrapper.text()).not.toContain('原始参数')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('提交中：被点击的按钮 loading，两个按钮同时锁定（🔴 不乐观展示成功）', () => {
    const wrapper = mountCard({ submitting: 'allow' })

    const [deny, allow] = wrapper.findAll('button')
    expect(deny.attributes('disabled')).toBeDefined()
    expect(allow.attributes('aria-busy')).toBe('true')
    expect(wrapper.find('.confirm-note').text()).toBe(zhCN.chat.toolConfirm.submitting)
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.succeeded)
  })

  it('已提交决定后按钮保持锁定，等待 SSE 收敛', () => {
    const wrapper = mountCard({ decided: 'deny' })

    wrapper.findAll('button').forEach((button) => {
      expect(button.attributes('disabled')).toBeDefined()
    })
  })

  it('30055 冲突态：按钮锁定并展示"已有处理结果"说明，不弹窗', () => {
    const wrapper = mountCard({ conflicted: true })

    wrapper.findAll('button').forEach((button) => {
      expect(button.attributes('disabled')).toBeDefined()
    })
    expect(wrapper.find('.confirm-note').text()).toBe(zhCN.errors.toolConfirmConflict.description)
  })

  it('风险标签未下发时不渲染（🔴 不硬编码"高风险"）', () => {
    const wrapper = mountCard({ riskLabel: '' })

    expect(wrapper.find('.confirm-risk').exists()).toBe(false)
  })
})

describe('ToolConfirmCard · 倒计时', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('🔴 confirmWaitSeconds ≤ 0（未下发）时完全不渲染倒计时', () => {
    const wrapper = mountCard({ confirmWaitSeconds: 0 })

    expect(wrapper.find('.confirm-countdown').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('剩余')
  })

  it('倒计时用 transform: scaleX 表达进度，🔴 不动画 width、不闪烁', async () => {
    const wrapper = mountCard()
    await wrapper.vm.$nextTick()

    const progress = wrapper.find('.confirm-progress')
    expect(progress.attributes('style')).toContain('scaleX')
    expect(progress.attributes('style')).not.toContain('width')
    // 轨道对读屏隐藏，剩余秒数以文本表达
    expect(wrapper.find('.confirm-track').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('.confirm-remaining').text()).toContain('剩余')
  })

  it('🔴 倒计时文本不进入 aria-live（不逐秒读屏轰炸）', async () => {
    const wrapper = mountCard()
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-remaining').exists()).toBe(true)
    expect(wrapper.find('.confirm-remaining').attributes('aria-live')).toBeUndefined()
    expect(wrapper.findAll('[aria-live]')).toHaveLength(0)
    expect(wrapper.findAll('[role="status"]')).toHaveLength(0)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
  })

  it('按截止时刻反推剩余秒数，每秒更新一次文本', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date', 'performance'] })
    const wrapper = mountCard({ confirmWaitSeconds: 5, deadlineAt: performance.now() + 5000 })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-remaining').text()).toContain('5')

    vi.advanceTimersByTime(2000)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-remaining').text()).toContain('3')
  })

  it('🔴 归零后禁用操作但不自行判定超时（状态仍为等待确认，只提示正在同步）', async () => {
    const wrapper = mountCard({ deadlineAt: performance.now() - 1000 })
    await wrapper.vm.$nextTick()

    wrapper.findAll('button').forEach((button) => {
      expect(button.attributes('disabled')).toBeDefined()
    })
    expect(wrapper.find('.confirm-note').text()).toBe(zhCN.chat.toolConfirm.expiredSyncing)
    // 🔴 绝不伪造 timed_out：不抛决定、不显示超时文案
    expect(wrapper.emitted('decide')).toBeUndefined()
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.timed_out)
  })

  it('停止生成期间冻结倒计时并禁用按钮，展示"正在停止"（停止 ≠ 拒绝）', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date', 'performance'] })
    const wrapper = mountCard({ confirmWaitSeconds: 10, deadlineAt: performance.now() + 10_000 })

    await wrapper.setProps({ stopping: true })
    const frozen = wrapper.find('.confirm-remaining').text()
    vi.advanceTimersByTime(3000)
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.confirm-remaining').text()).toBe(frozen)
    wrapper.findAll('button').forEach((button) => {
      expect(button.attributes('disabled')).toBeDefined()
    })
    expect(wrapper.find('.confirm-note').text()).toBe(zhCN.chat.toolConfirm.stopping)
    expect(wrapper.emitted('decide')).toBeUndefined()
  })
})

describe('ToolCallTimeline · 确认卡原位收敛', () => {
  beforeEach(() => {
    resetAnnouncements()
    setupPinia({
      tool: { confirmWaitSeconds: WAIT_SECONDS },
      display: { toolRiskLabels: { high: '高风险操作' } },
    })
  })

  it('awaiting_confirmation 渲染确认卡而非普通状态条', () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call()] },
    })

    expect(wrapper.find('.confirm').exists()).toBe(true)
    expect(wrapper.find('.tool-bar').exists()).toBe(false)
  })

  it('🔴 收到 SSE cancelled 后原位转取消态（停止生成的最终收敛）', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call()] },
    })

    await wrapper.setProps({ calls: [call({ status: 'cancelled' })] })

    expect(wrapper.find('.confirm').exists()).toBe(false)
    expect(wrapper.find('.tool-bar-status').text()).toBe(zhCN.chat.toolStatus.cancelled)
    // 取消不等于拒绝，也不等于超时
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.denied)
    expect(wrapper.text()).not.toContain(zhCN.chat.toolStatus.timed_out)
  })

  it('🔴 timed_out + 30050 收敛为「确认等待超时」语义（未执行）', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call()] },
    })

    await wrapper.setProps({ calls: [call({ status: 'timed_out', errorCode: 30050 })] })

    expect(wrapper.find('.tool-bar-status').text()).toBe(zhCN.chat.toolStatus.timed_out)
    expect(wrapper.find('.tool-bar-error').text()).toBe(zhCN.errors.toolDenied.confirmTimeout)
  })

  it('denied 帧收敛为已拒绝状态条', async () => {
    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call()] },
    })

    await wrapper.setProps({ calls: [call({ status: 'denied', errorCode: 30050 })] })

    expect(wrapper.find('.tool-bar-status').text()).toBe(zhCN.chat.toolStatus.denied)
    expect(wrapper.find('.tool-bar-error').text()).toBe(zhCN.errors.toolDenied.description)
  })

  it('点击允许后由 Store 提交，卡片停留在等待收敛（不乐观切换状态条）', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve({
        ok: true,
        status: 200,
        headers: new Headers({ 'content-type': 'application/json' }),
        json: () =>
          Promise.resolve({
            code: 0,
            message: 'success',
            data: {
              toolCallId: '9001',
              messageId: '5002',
              decision: 'allow',
              status: 'running',
              decidedAt: '2026-08-13T02:20:11.000Z',
              replayed: false,
            },
            timestamp: Date.now(),
          }),
      } as unknown as Response),
    )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call()] },
    })
    await wrapper.findAll('button')[1].trigger('click')

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(requestUrlAt(fetchMock)).toContain('/api/v1/messages/5002/tool-calls/9001/confirm')
    // 状态条只在 SSE 帧到达后出现
    expect(wrapper.find('.confirm').exists()).toBe(true)
    vi.unstubAllGlobals()
  })
})
