import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { normalizeToolCall } from '@/utils/toolCall'
import { streamChat, type ToolStreamEvent } from '@/utils/streamRequest'

import { collectStreamEvents, sseResponse, streamOptions, stubLocation } from './helpers'

/**
 * SSE `tool` 事件解析契约（docs/api-spec.md §5.2 / §5.4.1）。
 *
 * 覆盖两件事：
 *   ① 12 个字段全部按契约解析，类型与取值不失真；
 *   ② 🔴 **向后兼容硬约束**：未知字段、未知 `status` / `riskLevel`、缺省可选字段、
 *      null 值都不得抛异常、不得中断流 —— M1「只读 summary」的行为必须继续可用。
 */
const DONE_FRAME =
  'event: done\ndata: {"finishReason":"stop","messageId":"5002","status":"completed","title":null}\n\n'

function toolFrame(payload: Record<string, unknown>): string {
  return `event: tool\ndata: ${JSON.stringify(payload)}\n\n`
}

function stubFetch(chunks: readonly string[]): void {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(sseResponse(chunks))))
}

/** 后端第三阶段实际下发的完整 12 字段帧。 */
const FULL_PAYLOAD = {
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
} as const

async function firstToolEvent(chunks: readonly string[]): Promise<ToolStreamEvent> {
  const { events, onEvent } = collectStreamEvents()
  await streamChat(streamOptions(onEvent))
  const tool = events.find((event): event is ToolStreamEvent => event.type === 'tool')
  expect(tool).toBeDefined()
  return tool as ToolStreamEvent
}

describe('SSE tool 事件 · 12 字段解析', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('完整帧的 12 个字段全部解析正确，且 id 保持 string（ADR-004）', async () => {
    stubFetch([toolFrame(FULL_PAYLOAD), DONE_FRAME])

    const tool = await firstToolEvent([])

    expect(tool.toolCallId).toBe('9001')
    expect(typeof tool.toolCallId).toBe('string')
    expect(tool.toolType).toBe('mcp')
    expect(tool.toolKey).toBe('weather:query')
    expect(tool.riskLevel).toBe('high')
    expect(tool.status).toBe('awaiting_confirmation')
    expect(tool.round).toBe(1)
    // 🔴 兼容字段 summary 必须继续解析（V1.0 已声明，永久保留）
    expect(tool.summary).toBe('city=上海')
    expect(tool.argsSummary).toBe('city=上海')
    expect(tool.resultSummary).toBe('')
    expect(tool.truncated).toBe(false)
    expect(tool.errorCode).toBeNull()
    expect(tool.retryAfterSeconds).toBeNull()
  })

  it('终态帧解析 errorCode 为数字业务码，truncated 为 true', async () => {
    stubFetch([
      toolFrame({
        ...FULL_PAYLOAD,
        status: 'failed',
        resultSummary: '外部工具返回失败…',
        truncated: true,
        errorCode: 30057,
      }),
      DONE_FRAME,
    ])

    const tool = await firstToolEvent([])

    expect(tool.status).toBe('failed')
    expect(tool.errorCode).toBe(30057)
    expect(typeof tool.errorCode).toBe('number')
    expect(tool.truncated).toBe(true)
    expect(tool.resultSummary).toBe('外部工具返回失败…')
  })

  it('限流拒绝的工具帧解析 retryAfterSeconds', async () => {
    stubFetch([
      toolFrame({ ...FULL_PAYLOAD, status: 'failed', errorCode: 30057, retryAfterSeconds: 12 }),
      DONE_FRAME,
    ])

    const tool = await firstToolEvent([])

    expect(tool.retryAfterSeconds).toBe(12)
  })
})

describe('SSE tool 事件 · 向后兼容（api-spec §5.4.1）', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('🔴 未知字段被忽略，不抛异常、不中断后续事件', async () => {
    stubFetch([
      toolFrame({
        ...FULL_PAYLOAD,
        // 后端未来追加的字段：前端必须无感忽略
        futureField: 'whatever',
        nestedFuture: { a: 1, b: ['x'] },
        toolCallId: '9001',
      }),
      DONE_FRAME,
    ])

    const { events, onEvent } = collectStreamEvents()
    await expect(streamChat(streamOptions(onEvent))).resolves.toBeUndefined()

    expect(events.map((event) => event.type)).toEqual(['tool', 'done'])
    const tool = events[0] as ToolStreamEvent
    expect(tool.toolCallId).toBe('9001')
    expect(Object.keys(tool)).not.toContain('futureField')
  })

  it('🔴 未知 status 原样保留且流继续，done 仍能到达', async () => {
    stubFetch([
      toolFrame({ ...FULL_PAYLOAD, status: 'compensating' }),
      toolFrame({ ...FULL_PAYLOAD, status: 'succeeded', resultSummary: '晴' }),
      DONE_FRAME,
    ])

    const { events, onEvent } = collectStreamEvents()
    await streamChat(streamOptions(onEvent))

    expect(events.map((event) => event.type)).toEqual(['tool', 'tool', 'done'])
    // 未知枚举不猜测、不丢帧、不降级为已知状态
    expect((events[0] as ToolStreamEvent).status).toBe('compensating')
    expect((events[1] as ToolStreamEvent).status).toBe('succeeded')
  })

  it('🔴 未知 riskLevel 降级为空串（不渲染风险标签，绝不猜测风险）', async () => {
    stubFetch([toolFrame({ ...FULL_PAYLOAD, riskLevel: 'critical' }), DONE_FRAME])

    const tool = await firstToolEvent([])

    expect(tool.riskLevel).toBe('')
  })

  it('🔴 M1 旧帧（只有 toolCallId + status + summary）仍能正常解析', async () => {
    stubFetch([
      toolFrame({ toolCallId: '9001', status: 'running', summary: '正在查询' }),
      DONE_FRAME,
    ])

    const tool = await firstToolEvent([])

    expect(tool.toolCallId).toBe('9001')
    expect(tool.status).toBe('running')
    expect(tool.summary).toBe('正在查询')
    // 缺省字段被归一为确定形态，读取方无需判空
    expect(tool.toolKey).toBe('')
    expect(tool.argsSummary).toBe('')
    expect(tool.resultSummary).toBe('')
    expect(tool.riskLevel).toBe('')
    expect(tool.round).toBe(0)
    expect(tool.truncated).toBe(false)
    expect(tool.errorCode).toBeNull()
    expect(tool.retryAfterSeconds).toBeNull()
  })

  it('🔴 全部可选字段为 null 时不抛异常，归一为默认值', async () => {
    stubFetch([
      toolFrame({
        toolCallId: '9001',
        toolType: null,
        toolKey: null,
        riskLevel: null,
        status: null,
        round: null,
        summary: null,
        argsSummary: null,
        resultSummary: null,
        truncated: null,
        errorCode: null,
        retryAfterSeconds: null,
      }),
      DONE_FRAME,
    ])

    const { events, onEvent } = collectStreamEvents()
    await expect(streamChat(streamOptions(onEvent))).resolves.toBeUndefined()

    const tool = events[0] as ToolStreamEvent
    expect(tool.status).toBe('')
    expect(tool.round).toBe(0)
    expect(tool.truncated).toBe(false)
    expect(events[1].type).toBe('done')
  })

  it('🔴 非法 JSON 的 tool 帧被跳过，后续 tool / done 不受影响', async () => {
    stubFetch([
      'event: tool\ndata: {"toolCallId":"9001",\n\n',
      toolFrame({ ...FULL_PAYLOAD, status: 'succeeded' }),
      DONE_FRAME,
    ])

    const { events, onEvent } = collectStreamEvents()
    await expect(streamChat(streamOptions(onEvent))).resolves.toBeUndefined()

    expect(events.map((event) => event.type)).toEqual(['tool', 'done'])
  })

  it('tool 帧跨网络 chunk 边界拆分时仍能正确拼接', async () => {
    const frame = toolFrame(FULL_PAYLOAD)
    const cut = Math.floor(frame.length / 2)
    stubFetch([frame.slice(0, cut), frame.slice(cut), DONE_FRAME])

    const tool = await firstToolEvent([])

    expect(tool.toolCallId).toBe('9001')
    expect(tool.argsSummary).toBe('city=上海')
  })

  it('归一函数对空对象也安全（历史消息 toolCalls 字段可缺可空）', () => {
    const call = normalizeToolCall({})

    expect(call.toolCallId).toBe('')
    expect(call.status).toBe('')
    // toolType 缺省按 mcp 处理（本地工具必然显式下发 local）
    expect(call.toolType).toBe('mcp')
    expect(call.errorCode).toBeNull()
  })
})
