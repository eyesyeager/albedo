import { describe, expect, it, vi } from 'vitest'

import type { ChatMessageView } from '@/types/chat'
import { createStreamHandler, type StreamHandlerContext } from '@/utils/chatStreamHandler'

/**
 * 思考过程通道（`delta.reasoning`，api-spec §5.2）的确定性回归测试。
 *
 * 🔴 锁定的核心不变量：**思考内容与正文严格分流**。
 * 一旦有人"顺手"把 reasoning 并入 pushText，将同时造成：
 *   ① 思维链被拼进 `content` → 落库污染历史、"复制回答"复制出过程
 *   ② 首轮标题取自正文 → 标题变成思考片段
 *   ③ 只读 `text` 的旧前端把过程当答案渲染（§5.4.1 第 4 条兼容性被破坏）
 * 因此下列断言不是实现细节，而是契约。
 */
describe('SSE delta · 思考过程与正文分流', () => {
  function context(): {
    ctx: StreamHandlerContext
    state: { text: string[]; reasoning: string[]; firstVisible: number }
  } {
    const state = { text: [] as string[], reasoning: [] as string[], firstVisible: 0 }
    const ctx: StreamHandlerContext = {
      assistantClientId: 'c_1',
      getToolCalls: () => [],
      patch: (_clientId: string, _changes: Partial<ChatMessageView>) => undefined,
      pushText: (text) => {
        state.text.push(text)
      },
      pushReasoning: (reasoning) => {
        state.reasoning.push(reasoning)
      },
      flushText: () => undefined,
      onConversationId: () => undefined,
      onUserMessageId: () => undefined,
      onFirstVisibleFrame: () => {
        state.firstVisible += 1
      },
      onToolCall: () => undefined,
      onRateLimited: () => undefined,
      onDone: () => undefined,
    }
    return { ctx, state }
  }

  it('🔴 思考帧只进 reasoning 通道，绝不污染正文', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    handle({ type: 'delta', text: '', reasoning: '好的，' })
    handle({ type: 'delta', text: '', reasoning: '用户问日期' })

    expect(state.reasoning).toEqual(['好的，', '用户问日期'])
    expect(state.text).toEqual([])
  })

  it('🔴 正文帧只进 text 通道，不产生空的 reasoning 写入', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    handle({ type: 'delta', text: '今天是 8 月 16 日', reasoning: '' })

    expect(state.text).toEqual(['今天是 8 月 16 日'])
    expect(state.reasoning).toEqual([])
  })

  it('推理模型的真实时序：先思考后正文，两条通道各自累积且互不干扰', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    handle({ type: 'delta', text: '', reasoning: '需要查当前时间' })
    handle({ type: 'delta', text: '今天是', reasoning: '' })
    handle({ type: 'delta', text: '周日', reasoning: '' })

    expect(state.reasoning.join('')).toBe('需要查当前时间')
    expect(state.text.join('')).toBe('今天是周日')
  })

  it('空帧（text 与 reasoning 皆空）被忽略，不触发首字埋点', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    handle({ type: 'delta', text: '', reasoning: '' })

    expect(state.text).toEqual([])
    expect(state.reasoning).toEqual([])
    expect(state.firstVisible).toBe(0)
  })

  it('思考帧即用户可见帧：首字埋点只触发一次（§5.4.2 锚点口径）', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    handle({ type: 'delta', text: '', reasoning: '思考中' })
    handle({ type: 'delta', text: '答案', reasoning: '' })

    expect(state.firstVisible).toBe(1)
  })

  it('🔴 旧后端不下发 reasoning 字段时行为完全不变（向后兼容）', () => {
    const { ctx, state } = context()
    const handle = createStreamHandler(ctx)

    // 解析层已把缺失字段归一为空串，此处模拟归一后的形态
    handle({ type: 'delta', text: '普通回答', reasoning: '' })

    expect(state.text).toEqual(['普通回答'])
    expect(state.reasoning).toEqual([])
  })

  it('done 帧仍会刷出缓冲（思考通道不影响既有收尾逻辑）', () => {
    const { ctx } = context()
    const flushText = vi.fn()
    const handle = createStreamHandler({ ...ctx, flushText })

    handle({ type: 'delta', text: '', reasoning: '思考' })
    handle({
      type: 'done',
      finishReason: 'stop',
      messageId: '5002',
      status: 'completed',
      title: null,
    })

    expect(flushText).toHaveBeenCalled()
  })
})
