import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'

import ToolCallBar from '@/components/chat/ToolCallBar.vue'
import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import zhCN from '@/locales/zh-CN'
import { TOOL_CALL_STATUSES, type ToolCallStatus, type ToolCallSummary } from '@/types/tool'
import { toolStatusIcon, toolStatusTone } from '@/utils/toolVisual'

import { setupPinia } from './helpers'

/**
 * 8 种工具状态的可辨识渲染（design-system.md §14.2.2）。
 *
 * 🔴 核心断言：状态**不只靠颜色**表达 —— 每种状态都必须同时具备
 * ① 可读文字（来自 sys_config 或 locales），② 与状态一一对应的图标组件。
 * 颜色 class 只是辅助编码，色盲用户与高对比模式用户必须仍能分辨。
 */
function call(status: string, overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
  return {
    toolCallId: '9001',
    toolType: 'mcp',
    toolKey: 'weather:query',
    status,
    riskLevel: 'low',
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

/** 契约要求的 8 种状态，一个不能少。 */
const EXPECTED_STATUSES: readonly ToolCallStatus[] = [
  'pending',
  'awaiting_confirmation',
  'running',
  'succeeded',
  'failed',
  'timed_out',
  'cancelled',
  'denied',
]

describe('工具状态视觉映射 · 8 态齐全', () => {
  it('状态枚举恰好为契约定义的 8 种', () => {
    expect([...TOOL_CALL_STATUSES]).toEqual([...EXPECTED_STATUSES])
  })

  it('每种状态都有专属图标，8 种图标互不重复（🔴 不靠颜色区分）', () => {
    const icons = EXPECTED_STATUSES.map((status) => toolStatusIcon(status))

    expect(new Set(icons).size).toBe(EXPECTED_STATUSES.length)
  })

  it('每种状态都有 locales 兜底文案，且互不重复', () => {
    const labels = EXPECTED_STATUSES.map((status) => zhCN.chat.toolStatus[status])

    labels.forEach((label) => expect(label.length).toBeGreaterThan(0))
    expect(new Set(labels).size).toBe(EXPECTED_STATUSES.length)
  })

  it('色调映射符合 design-system §14.2.2 的语义分配', () => {
    expect(toolStatusTone('pending')).toBe('neutral')
    expect(toolStatusTone('awaiting_confirmation')).toBe('warning')
    expect(toolStatusTone('running')).toBe('running')
    expect(toolStatusTone('succeeded')).toBe('success')
    expect(toolStatusTone('failed')).toBe('danger')
    expect(toolStatusTone('timed_out')).toBe('warning')
    expect(toolStatusTone('cancelled')).toBe('neutral')
    expect(toolStatusTone('denied')).toBe('danger')
  })

  it('未知状态降级为中性色调 + 兜底图标（🔴 不崩、不显示原始枚举值）', () => {
    expect(toolStatusTone('compensating')).toBe('neutral')
    expect(toolStatusIcon('compensating')).toBe(toolStatusIcon('pending'))
  })
})

describe('ToolCallBar · 状态渲染不只靠颜色', () => {
  // awaiting_confirmation 走确认卡，不使用普通状态条（design-system §14.2.2）
  const BAR_STATUSES = EXPECTED_STATUSES.filter((status) => status !== 'awaiting_confirmation')

  BAR_STATUSES.forEach((status) => {
    it(`${status} 渲染出可读文案 + 图标 + 状态 class`, () => {
      const label = zhCN.chat.toolStatus[status]
      const wrapper = mount(ToolCallBar, {
        props: { call: call(status), statusLabel: label },
      })

      // ① 有文本语义
      const statusEl = wrapper.find('.tool-bar-status')
      expect(statusEl.exists()).toBe(true)
      expect(statusEl.text()).toBe(label)
      // ② 有图标（真实渲染出 svg，防组件未注册导致静默不渲染，参照 D-007）
      expect(wrapper.find('.tool-bar-icon').exists()).toBe(true)
      expect(wrapper.find('svg.tool-bar-icon').exists()).toBe(true)
      // ③ 颜色只是附加编码
      expect(statusEl.classes().some((name) => name.startsWith('tool-bar-status--'))).toBe(true)
      // ④ 容器有 role + 无障碍名称
      expect(wrapper.attributes('role')).toBe('group')
      expect(wrapper.attributes('aria-label')).toBe(zhCN.chat.toolCallTitle)
    })
  })

  it('running 状态的旋转仅作用于图标，读屏名称仍是状态文字', () => {
    const wrapper = mount(ToolCallBar, {
      props: { call: call('running'), statusLabel: zhCN.chat.toolStatus.running },
    })

    expect(wrapper.find('.tool-bar-icon--spin').exists()).toBe(true)
    expect(wrapper.find('.tool-bar-icon').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('.tool-bar-status').text()).toBe('执行中')
  })

  it('展开控件是原生 button，带 aria-expanded / aria-controls / 无障碍名称', async () => {
    const wrapper = mount(ToolCallBar, {
      props: { call: call('running'), statusLabel: '执行中' },
    })

    const toggle = wrapper.find('.tool-bar-toggle')
    expect(toggle.element.tagName).toBe('BUTTON')
    expect(toggle.attributes('type')).toBe('button')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(toggle.attributes('aria-label')).toBe(zhCN.a11y.toolCallSummaryToggle)
    expect(toggle.attributes('aria-controls')).toBe('tool-panel-9001')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('#tool-panel-9001').exists()).toBe(true)
  })

  it('无可展示摘要时不渲染展开按钮（不给用户假入口）', () => {
    const wrapper = mount(ToolCallBar, {
      props: { call: call('cancelled', { argsSummary: '', resultSummary: '' }), statusLabel: '已取消' },
    })

    expect(wrapper.find('.tool-bar-toggle').exists()).toBe(false)
  })

  it('truncated=true 只提示被截断，🔴 不展示阈值数字、不提供"查看全部"', async () => {
    const wrapper = mount(ToolCallBar, {
      props: {
        call: call('succeeded', { resultSummary: '很长的结果…', truncated: true }),
        statusLabel: '已完成',
      },
    })

    await wrapper.find('.tool-bar-toggle').trigger('click')

    expect(wrapper.find('.tool-bar-truncated').text()).toBe(zhCN.chat.toolCall.summaryTruncated)
    expect(wrapper.text()).not.toMatch(/\d{3,}/)
    expect(wrapper.text()).not.toContain('查看全部')
  })
})

describe('ToolCallTimeline · 状态文案来源优先级', () => {
  it('sys_config 下发 toolStatusLabels 时优先使用下发文案', () => {
    setupPinia({ display: { toolStatusLabels: { succeeded: '执行成功' } } })

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call('succeeded', { resultSummary: '晴' })] },
    })

    expect(wrapper.find('.tool-bar-status').text()).toBe('执行成功')
  })

  it('sys_config 支持 [{value,label}] 数组形态（契约未固定，两种都必须能用）', () => {
    setupPinia({ display: { toolStatusLabels: [{ value: 'failed', label: '调用失败' }] } })

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call('failed', { errorCode: 30057 })] },
    })

    expect(wrapper.find('.tool-bar-status').text()).toBe('调用失败')
  })

  it('sys_config 未下发时回退 locales，绝不显示原始枚举值', () => {
    setupPinia({})

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call('timed_out')] },
    })

    const text = wrapper.find('.tool-bar-status').text()
    expect(text).toBe(zhCN.chat.toolStatus.timed_out)
    expect(text).not.toBe('timed_out')
  })

  it('🔴 未知状态显示通用文案，不把 snake_case 枚举暴露给用户', () => {
    setupPinia({})

    const wrapper = mount(ToolCallTimeline, {
      props: { messageId: '5002', calls: [call('compensating')] },
    })

    expect(wrapper.find('.tool-bar-status').text()).toBe(zhCN.chat.toolStatus.unknown)
    expect(wrapper.text()).not.toContain('compensating')
  })

  it('风险文案未下发时不渲染风险标签（🔴 绝不硬编码"高风险"）', () => {
    setupPinia({ tool: { confirmWaitSeconds: 60 } })

    const wrapper = mount(ToolCallTimeline, {
      props: {
        messageId: '5002',
        calls: [call('awaiting_confirmation', { riskLevel: 'high' })],
      },
    })

    expect(wrapper.find('.confirm').exists()).toBe(true)
    expect(wrapper.find('.confirm-risk').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('高风险')
  })

  it('风险文案已下发时按下发内容渲染标签', () => {
    setupPinia({
      tool: { confirmWaitSeconds: 60 },
      display: { toolRiskLabels: { high: '高风险操作' } },
    })

    const wrapper = mount(ToolCallTimeline, {
      props: {
        messageId: '5002',
        calls: [call('awaiting_confirmation', { riskLevel: 'high' })],
      },
    })

    expect(wrapper.find('.confirm-risk').text()).toBe('高风险操作')
    expect(wrapper.find('.confirm-risk').classes()).toContain('confirm-risk--high')
  })
})
