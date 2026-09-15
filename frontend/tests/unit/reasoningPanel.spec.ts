import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import ReasoningPanel from '@/components/chat/ReasoningPanel.vue'
import type { ToolCallSummary } from '@/types/tool'
import type { MessageProcessItem } from '@/utils/messageTimeline'

/**
 * 思考面板的回归测试。
 *
 * 🔴 锁定两条核心不变量：
 *   1. **自动折叠绝不覆盖用户意图** —— 用户点开思考读到一半，生成结束时若被强行合上是明确缺陷。
 *   2. **工具栏渲染在面板内部** —— 工具调用是思考中途的外部查询，不是思考的分隔符。
 */
describe('ReasoningPanel · 展开与折叠', () => {
  const reasoningItem = (text: string, round = 1): MessageProcessItem => ({
    kind: 'reasoning',
    key: `reasoning-${round}`,
    text,
    round,
  })

  function mountPanel(streaming: boolean, items?: MessageProcessItem[]) {
    return mount(ReasoningPanel, {
      props: {
        items: items ?? [reasoningItem('模型正在推理…')],
        streaming,
        messageKey: 'c_1',
        messageId: '5002',
      },
    })
  }

  const isExpanded = (wrapper: ReturnType<typeof mountPanel>): boolean =>
    wrapper.find('.reasoning-toggle').attributes('aria-expanded') === 'true'

  it('生成中默认展开', () => {
    expect(isExpanded(mountPanel(true))).toBe(true)
  })

  it('历史消息（非流式）默认折叠', () => {
    expect(isExpanded(mountPanel(false))).toBe(false)
  })

  it('思考结束后自动折叠', async () => {
    const wrapper = mountPanel(true)
    expect(isExpanded(wrapper)).toBe(true)

    await wrapper.setProps({ streaming: false })

    expect(isExpanded(wrapper)).toBe(false)
  })

  it('🔴 用户手动展开后，思考结束不得强行折叠', async () => {
    const wrapper = mountPanel(true)
    // 用户先折叠、再展开 —— 明确表达了"我要看"
    await wrapper.find('.reasoning-toggle').trigger('click')
    await wrapper.find('.reasoning-toggle').trigger('click')
    expect(isExpanded(wrapper)).toBe(true)

    await wrapper.setProps({ streaming: false })

    expect(isExpanded(wrapper)).toBe(true)
  })

  it('🔴 用户手动折叠后，不得因仍在生成而被重新展开', async () => {
    const wrapper = mountPanel(true)
    await wrapper.find('.reasoning-toggle').trigger('click')
    expect(isExpanded(wrapper)).toBe(false)

    // 模拟新一轮生成（多轮工具编排时 streaming 可能再次置真）
    await wrapper.setProps({ streaming: false })
    await wrapper.setProps({ streaming: true })

    expect(isExpanded(wrapper)).toBe(false)
  })

  it('展开时渲染思考正文，且 aria-controls 指向该面板', () => {
    const wrapper = mountPanel(true)
    const panelId = wrapper.find('.reasoning-toggle').attributes('aria-controls')

    expect(panelId).toBe('reasoning-panel-c_1')
    expect(wrapper.find(`#${panelId}`).text()).toContain('模型正在推理…')
  })

  it('折叠时不渲染面板内容（不占布局）', () => {
    const wrapper = mountPanel(false)
    expect(wrapper.find('.reasoning-panel').exists()).toBe(false)
  })
})

describe('ReasoningPanel · 工具栏内嵌', () => {
  // 🔴 内嵌的 ToolCallTimeline 依赖 pinia store（配置与确认态），须先激活
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const call: ToolCallSummary = {
    toolCallId: '9001',
    toolType: 'local',
    toolKey: 'calculator',
    status: 'succeeded',
    round: 1,
    summary: 'result:1230.96',
    argsSummary: 'expression=9*89.2',
    resultSummary: 'result:1230.96',
    truncated: false,
    errorCode: null,
    retryAfterSeconds: null,
  }

  const interleaved: MessageProcessItem[] = [
    { kind: 'reasoning', key: 'reasoning-1', text: '需要用计算器核对', round: 1 },
    { kind: 'tools', key: 'tools-1', round: 1, calls: [call] },
    { kind: 'reasoning', key: 'reasoning-2', text: '结果正确', round: 2 },
  ]

  function mountInterleaved() {
    return mount(ReasoningPanel, {
      props: { items: interleaved, streaming: true, messageKey: 'c_1', messageId: '5002' },
    })
  }

  it('🔴 工具栏渲染在思考面板内部，且位于两段思考之间', () => {
    const wrapper = mountInterleaved()
    const panel = wrapper.find('.reasoning-panel')

    expect(panel.exists()).toBe(true)
    // 面板内部的直接子节点顺序：思考文本 → 工具栏 → 思考文本
    const html = panel.html()
    const firstText = html.indexOf('需要用计算器核对')
    const toolIndex = html.indexOf('reasoning-tools')
    const secondText = html.indexOf('结果正确')

    expect(firstText).toBeGreaterThanOrEqual(0)
    expect(toolIndex).toBeGreaterThan(firstText)
    expect(secondText).toBeGreaterThan(toolIndex)
  })

  it('两段思考都在同一个面板内（只有一个折叠头）', () => {
    const wrapper = mountInterleaved()

    expect(wrapper.findAll('.reasoning-toggle')).toHaveLength(1)
    expect(wrapper.findAll('.reasoning-text')).toHaveLength(2)
  })

  it('🔴 折叠态展示工具调用次数（否则折叠等于信息消失）', () => {
    const wrapper = mountInterleaved()

    expect(wrapper.find('.reasoning-meta').text()).toContain('1')
  })

  it('无工具调用时不显示次数标记', () => {
    const wrapper = mount(ReasoningPanel, {
      props: {
        items: [{ kind: 'reasoning', key: 'r1', text: '想', round: 1 }],
        streaming: true,
        messageKey: 'c_1',
        messageId: '5002',
      },
    })

    expect(wrapper.find('.reasoning-meta').exists()).toBe(false)
  })
})
