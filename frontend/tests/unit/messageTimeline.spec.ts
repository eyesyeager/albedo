import { describe, expect, it } from 'vitest'

import type { ChatMessageView } from '@/types/chat'
import type { ToolCallSummary } from '@/types/tool'
import {
  activeProcessKey,
  buildMessageTimeline,
  type MessageTimelineBlock,
} from '@/utils/messageTimeline'

/**
 * 助手消息时间线的回归测试。
 *
 * 🔴 锁定两条核心不变量：
 *   1. **渲染顺序 = 真实发生顺序**。模型的实际时序是「思考① → 工具① → 思考② → 正文」；
 *      退回"全部思考 → 全部工具 → 全部正文"会让用户误以为模型先想完了才调工具。
 *   2. **工具调用是思考的一部分，不是思考的分隔符**。连续的思考/工具必须合并为
 *      <b>一个</b>过程块（工具栏嵌在面板内），绝不能把一段连续推理切成两个「思考过程」面板。
 */
describe('buildMessageTimeline · 过程块与正文', () => {
  function tool(overrides: Partial<ToolCallSummary> = {}): ToolCallSummary {
    return {
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
      ...overrides,
    }
  }

  function message(overrides: Partial<ChatMessageView> = {}): ChatMessageView {
    return {
      messageId: '5002',
      role: 'assistant',
      content: '',
      status: 'completed',
      attemptNo: 1,
      isCurrent: true,
      createdAt: '2026-08-16T09:00:00.000Z',
      clientId: 'c-1',
      streaming: false,
      errorMessage: null,
      errorCode: null,
      toolCalls: [],
      reasoning: '',
      segments: [],
      ...overrides,
    }
  }

  const kinds = (blocks: MessageTimelineBlock[]): string[] => blocks.map((b) => b.kind)

  /** 取过程块内部节点类型序列 */
  function innerKinds(block: MessageTimelineBlock | undefined): string[] {
    return block !== undefined && block.kind === 'process'
      ? block.items.map((i) => i.kind)
      : []
  }

  it('🔴 截图场景：思考与工具合并为单个过程块，工具栏在面板内部', () => {
    const blocks = buildMessageTimeline(
      message({
        content: '总价为1230.96元。',
        reasoning: '先算前9个…需要用计算器核对。好的，工具返回的结果正确。',
        segments: [
          { round: 1, reasoning: '先算前9个…需要用计算器核对。', text: '' },
          { round: 2, reasoning: '好的，工具返回的结果正确。', text: '总价为1230.96元。' },
        ],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    // 🔴 只有 2 个顶层块：一个过程块 + 一个正文块（而不是 4 个平铺节点）
    expect(kinds(blocks)).toEqual(['process', 'text'])
    // 🔴 过程块内部：思考 → 工具 → 思考（同一个面板里交替）
    expect(innerKinds(blocks[0])).toEqual(['reasoning', 'tools', 'reasoning'])
    expect(blocks[1]).toMatchObject({ kind: 'text', text: '总价为1230.96元。' })
  })

  it('🔴 一段连续推理绝不被切成两个过程块（回归本次缺陷）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '思考①', text: '' },
          { round: 2, reasoning: '思考②', text: '答案' },
        ],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    expect(blocks.filter((b) => b.kind === 'process')).toHaveLength(1)
  })

  it('🔴 工具节点绝不被堆到过程块末尾（顺序即因果）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '思考①', text: '' },
          { round: 2, reasoning: '思考②', text: '答案' },
        ],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    const inner = innerKinds(blocks[0])
    expect(inner.indexOf('tools')).toBeLessThan(inner.lastIndexOf('reasoning'))
  })

  it('多轮工具：每轮工具紧跟该轮思考，全部收在同一过程块内', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '想①', text: '' },
          { round: 2, reasoning: '想②', text: '' },
          { round: 3, reasoning: '想③', text: '答案' },
        ],
        toolCalls: [
          tool({ toolCallId: '1', round: 1, toolKey: 'datetime_now' }),
          tool({ toolCallId: '2', round: 2, toolKey: 'calculator' }),
        ],
      }),
    )

    expect(kinds(blocks)).toEqual(['process', 'text'])
    expect(innerKinds(blocks[0])).toEqual([
      'reasoning',
      'tools',
      'reasoning',
      'tools',
      'reasoning',
    ])
  })

  it('🔴 中途正文会结束当前过程块（模型对用户说话了，该阶段就此结束）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '想①', text: '我先查一下。' },
          { round: 2, reasoning: '想②', text: '答案' },
        ],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    // 想① → 「我先查一下。」→ [工具① + 想②] → 答案
    expect(kinds(blocks)).toEqual(['process', 'text', 'process', 'text'])
    expect(innerKinds(blocks[0])).toEqual(['reasoning'])
    expect(innerKinds(blocks[2])).toEqual(['tools', 'reasoning'])
  })

  it('同一轮的多个工具聚为一个节点（保持组内顺序）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '想', text: '' },
          { round: 2, reasoning: '', text: '答案' },
        ],
        toolCalls: [
          tool({ toolCallId: '1', round: 1, toolKey: 'datetime_now' }),
          tool({ toolCallId: '2', round: 1, toolKey: 'calculator' }),
        ],
      }),
    )

    const process = blocks[0]
    expect(process?.kind).toBe('process')
    if (process?.kind === 'process') {
      const tools = process.items.filter((i) => i.kind === 'tools')
      expect(tools).toHaveLength(1)
      if (tools[0]?.kind === 'tools') {
        expect(tools[0].calls.map((c) => c.toolKey)).toEqual(['datetime_now', 'calculator'])
      }
    }
  })

  it('🔴 轮次缺口容错：某轮只有工具、没有思考/正文时工具不得丢失', () => {
    const blocks = buildMessageTimeline(
      message({
        // 模型直接决定调工具，第 1 轮没有任何思考与正文
        segments: [{ round: 2, reasoning: '拿到结果了', text: '答案' }],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    expect(kinds(blocks)).toEqual(['process', 'text'])
    expect(innerKinds(blocks[0])).toEqual(['tools', 'reasoning'])
  })

  it('🔴 只有工具、完全没有思考 → hasReasoning 为 false（不得假造"思考过程"外壳）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [{ round: 2, reasoning: '', text: '答案' }],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    expect(blocks[0]).toMatchObject({ kind: 'process', hasReasoning: false })
  })

  it('有思考时 hasReasoning 为 true', () => {
    const blocks = buildMessageTimeline(
      message({ segments: [{ round: 1, reasoning: '想', text: '' }] }),
    )

    expect(blocks[0]).toMatchObject({ kind: 'process', hasReasoning: true })
  })

  it('空段落被跳过（不渲染空的思考面板或空正文块）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '', text: '' },
          { round: 2, reasoning: '想', text: '' },
        ],
      }),
    )

    expect(kinds(blocks)).toEqual(['process'])
    expect(innerKinds(blocks[0])).toEqual(['reasoning'])
  })

  it('🔴 仅含空白的正文段落不得渲染成空块（实测模型会在调工具前只吐一个换行）', () => {
    const blocks = buildMessageTimeline(
      message({
        segments: [
          { round: 1, reasoning: '想①', text: '\n' },
          { round: 2, reasoning: '想②', text: '答案' },
        ],
        toolCalls: [tool({ round: 1 })],
      }),
    )

    // 空白正文若未被过滤，会把过程块切成两段
    expect(kinds(blocks)).toEqual(['process', 'text'])
    expect(innerKinds(blocks[0])).toEqual(['reasoning', 'tools', 'reasoning'])
  })

  it('🔴 无 segments 的历史消息降级为旧版布局，内容一个字都不能少', () => {
    const blocks = buildMessageTimeline(
      message({
        content: '答案',
        reasoning: '思考',
        segments: [],
        toolCalls: [tool()],
      }),
    )

    expect(kinds(blocks)).toEqual(['process', 'text'])
    expect(innerKinds(blocks[0])).toEqual(['reasoning', 'tools'])
    expect(blocks[1]).toMatchObject({ text: '答案' })
  })

  it('普通对话（无思考无工具）只产出正文块', () => {
    const blocks = buildMessageTimeline(
      message({ content: '你好', segments: [{ round: 1, reasoning: '', text: '你好' }] }),
    )

    expect(kinds(blocks)).toEqual(['text'])
  })
})

describe('activeProcessKey · 进行中判定', () => {
  const processBlock = (key: string): MessageTimelineBlock => ({
    kind: 'process',
    key,
    items: [{ kind: 'reasoning', key: 'r', text: '想', round: 1 }],
    hasReasoning: true,
  })
  const textBlock: MessageTimelineBlock = { kind: 'text', key: 't', text: '答案', round: 1 }

  it('流式中且最后一块是过程块 → 该块进行中', () => {
    expect(activeProcessKey([processBlock('p1')], true)).toBe('p1')
  })

  it('🔴 正文开始到达后无进行中面板（思考已结束，自动折叠让位给答案）', () => {
    expect(activeProcessKey([processBlock('p1'), textBlock], true)).toBeNull()
  })

  it('🔴 非流式（历史消息）恒无进行中面板', () => {
    expect(activeProcessKey([processBlock('p1')], false)).toBeNull()
  })

  it('🔴 多个过程块时只有最后一个进行中（前面的阶段早已结束）', () => {
    expect(activeProcessKey([processBlock('p1'), textBlock, processBlock('p2')], true)).toBe('p2')
  })

  it('空时间线返回 null', () => {
    expect(activeProcessKey([], true)).toBeNull()
  })
})
