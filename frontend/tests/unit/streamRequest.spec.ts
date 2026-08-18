import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { streamChat, type ChatStreamEvent } from '@/utils/streamRequest'
import { ApiError } from '@/utils/request'

import { jsonResponse, sseResponse, stubLocation } from './helpers'

/**
 * SSE 解析契约测试（docs/api-spec.md §5）。
 *
 * 覆盖：事件顺序、分片跨 chunk 边界、心跳注释帧、CRLF、
 * 续期 token 回写、流未建立的 JSON 错误分流、鉴权失效整页跳 SSO。
 */
const TOKEN_KEY = 'authorization'

function collect(): { events: ChatStreamEvent[]; onEvent: (e: ChatStreamEvent) => void } {
  const events: ChatStreamEvent[] = []
  return { events, onEvent: (e) => events.push(e) }
}

function options(onEvent: (e: ChatStreamEvent) => void) {
  return {
    conversationId: 'new',
    body: { content: '你好' },
    idempotencyKey: 'idem-key-1',
    onEvent,
  }
}

describe('streamRequest · SSE 解析', () => {
  beforeEach(() => {
    localStorage.clear()
    stubLocation()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('按契约顺序解析 meta → delta* → done，并忽略心跳注释帧', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            'event: meta\ndata: {"conversationId":"1001","messageId":"5002","agentVersion":5,"userMessageId":"5001"}\n\n',
            ': ping\n\n',
            'event: delta\ndata: {"text":"你好"}\n\n',
            'event: delta\ndata: {"text":"，世界"}\n\n',
            'event: done\ndata: {"finishReason":"stop","messageId":"5002","status":"completed","title":"打招呼"}\n\n',
          ]),
        ),
      ),
    )

    const { events, onEvent } = collect()
    await streamChat(options(onEvent))

    expect(events.map((e) => e.type)).toEqual(['meta', 'delta', 'delta', 'done'])
    const meta = events[0]
    expect(meta.type === 'meta' && meta.conversationId).toBe('1001')
    expect(meta.type === 'meta' && meta.messageId).toBe('5002')
    // 🔴 id 必须保持 string（ADR-004），不得被转成 number
    expect(meta.type === 'meta' && typeof meta.messageId).toBe('string')
    const merged = events
      .filter((e): e is Extract<ChatStreamEvent, { type: 'delta' }> => e.type === 'delta')
      .map((e) => e.text)
      .join('')
    expect(merged).toBe('你好，世界')
    const done = events[3]
    expect(done.type === 'done' && done.status).toBe('completed')
    expect(done.type === 'done' && done.title).toBe('打招呼')
  })

  it('单个事件被拆分到多个网络 chunk 时仍能正确拼接', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            'event: del',
            'ta\ndata: {"text":"分片',
            '拼接"}\n\nevent: done\ndata: {"finishReason":"stop","messageId":"1","status":"completed","title":null}\n\n',
          ]),
        ),
      ),
    )

    const { events, onEvent } = collect()
    await streamChat(options(onEvent))

    const delta = events[0]
    expect(delta.type === 'delta' && delta.text).toBe('分片拼接')
    expect(events[1].type).toBe('done')
  })

  it('兼容 CRLF 帧分隔符，并解析 error 事件的数字业务码', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse([
            'event: error\r\ndata: {"code":50002,"message":"模型暂不可用"}\r\n\r\n',
            'event: done\r\ndata: {"finishReason":"failed","messageId":"9","status":"failed","title":null}\r\n\r\n',
          ]),
        ),
      ),
    )

    const { events, onEvent } = collect()
    await streamChat(options(onEvent))

    const error = events[0]
    expect(error.type === 'error' && error.code).toBe(50002)
    expect(typeof (error.type === 'error' ? error.code : '')).toBe('number')
    expect(events[1].type).toBe('done')
  })

  it('响应头出现新 token 时立即回写 localStorage（auth-type=1 续期，ADR-007）', async () => {
    localStorage.setItem(TOKEN_KEY, 'old-token')
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          sseResponse(['event: done\ndata: {"finishReason":"stop","messageId":"1","status":"completed","title":null}\n\n'], {
            authorization: 'renewed-token',
          }),
        ),
      ),
    )

    const { onEvent } = collect()
    await streamChat(options(onEvent))

    expect(localStorage.getItem(TOKEN_KEY)).toBe('renewed-token')
  })

  it('流未建立即失败时按标准 JSON Result 分流为业务错误（不跳登录）', async () => {
    localStorage.setItem(TOKEN_KEY, 'valid-token')
    const stub = stubLocation()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ code: 30041, message: '内容超长' }))))

    const { onEvent } = collect()
    const error = await streamChat(options(onEvent)).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe(30041)
    // 30000+ 是业务错误：token 必须保留，且不得跳转
    expect(localStorage.getItem(TOKEN_KEY)).toBe('valid-token')
    expect(stub.href).not.toContain('OAuth2')
  })

  it('鉴权失效（20002）时清 token 并整页跳耶瞳 SSO', async () => {
    localStorage.setItem(TOKEN_KEY, 'expired-token')
    const stub = stubLocation()
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(jsonResponse({ code: 20002, message: 'token 已过期' }))))

    const { onEvent } = collect()
    await streamChat(options(onEvent)).catch(() => undefined)

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(stub.href).toContain('/OAuth2?clientId=')
  })

  it('流内 error 事件命中鉴权段时同样清 token 跳 SSO，且不向上抛事件', async () => {
    localStorage.setItem(TOKEN_KEY, 'expired-token')
    const stub = stubLocation()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(sseResponse(['event: error\ndata: {"code":20001,"message":"token 非法"}\n\n']))),
    )

    const { events, onEvent } = collect()
    await streamChat(options(onEvent))

    expect(events).toHaveLength(0)
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(stub.href).toContain('/OAuth2?clientId=')
  })

  it('请求必须携带 authorization 与 Idempotency-Key 头', async () => {
    localStorage.setItem(TOKEN_KEY, 'my-token')
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      Promise.resolve(sseResponse(['event: done\ndata: {"finishReason":"stop","messageId":"1","status":"completed","title":null}\n\n'])),
    )
    vi.stubGlobal('fetch', fetchMock)

    const { onEvent } = collect()
    await streamChat(options(onEvent))

    const headers = (fetchMock.mock.calls[0][1]?.headers ?? {}) as Record<string, string>
    expect(headers.authorization).toBe('my-token')
    expect(headers['Idempotency-Key']).toBe('idem-key-1')
    expect(headers.Accept).toBe('text/event-stream')
  })
})
