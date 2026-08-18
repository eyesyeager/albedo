/**
 * 测试辅助：location 打桩。
 *
 * 目的：`request.ts` 的鉴权失效链路以 `location.href = ...` 整页跳转，
 * jsdom 不实现真实导航，必须替换全局 `location` 才能断言跳转地址。
 */
import { createPinia, setActivePinia } from 'pinia'
import { vi } from 'vitest'

import { useConfigStore } from '@/stores/config'
import type { SysConfigMap } from '@/api/sysConfig'
import type { ChatStreamEvent } from '@/utils/streamRequest'

export interface LocationStub {
  href: string
  pathname: string
  search: string
  hash: string
  host: string
  origin: string
  reload: () => void
}

export function stubLocation(initial = 'http://localhost:5173/chat'): LocationStub {
  const url = new URL(initial)
  const stub: LocationStub = {
    href: url.href,
    pathname: url.pathname,
    search: url.search,
    hash: url.hash,
    host: url.host,
    origin: url.origin,
    reload: () => undefined,
  }
  vi.stubGlobal('location', stub)
  return stub
}

/** 构造 SSE 响应（content-type: text/event-stream + ReadableStream body）。 */
export function sseResponse(chunks: readonly string[], headers: Record<string, string> = {}): Response {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)))
      controller.close()
    },
  })
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'text/event-stream;charset=utf-8', ...headers }),
    body: stream,
  } as unknown as Response
}

/** 构造标准 JSON Result 响应（流未建立即失败的形态）。 */
export function jsonResponse(
  body: { code: number; message: string; data?: unknown; timestamp?: number },
  headers: Record<string, string> = {},
): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-type': 'application/json', ...headers }),
    json: () => Promise.resolve({ data: null, timestamp: Date.now(), ...body }),
  } as unknown as Response
}

/**
 * SSE 事件收集器：把 `onEvent` 回调按到达顺序收进数组，供顺序 / 字段断言使用。
 */
export function collectStreamEvents(): {
  events: ChatStreamEvent[]
  onEvent: (event: ChatStreamEvent) => void
} {
  const events: ChatStreamEvent[] = []
  return { events, onEvent: (event) => events.push(event) }
}

/** `streamChat` 的最小可用参数（幂等键必填，见 api-spec §1.4）。 */
export function streamOptions(onEvent: (event: ChatStreamEvent) => void) {
  return {
    conversationId: 'new',
    body: { content: '你好' },
    idempotencyKey: 'idem-key-test',
    onEvent,
  }
}

/**
 * 建立测试用 Pinia，并把 `sys_config` 预置为给定内容。
 *
 * 🔴 存在理由：M3 的阈值 / 文案全部来自后端下发配置，测试必须显式注入配置，
 * 才能证明「组件读的是配置，而不是硬编码」。不传配置即模拟「后端未下发」。
 */
export function setupPinia(config: SysConfigMap = {}): void {
  setActivePinia(createPinia())
  const store = useConfigStore()
  store.config = config
  store.loaded = true
}

/** 取最近一次 fetch 调用的请求头（大小写不敏感地比对键名用）。 */
export function lastRequestHeaders(mock: {
  mock: { calls: unknown[][] }
}): Record<string, string> {
  const calls = mock.mock.calls
  const init = calls[calls.length - 1]?.[1] as RequestInit | undefined
  return (init?.headers ?? {}) as Record<string, string>
}

/**
 * 取第 index 次 fetch 调用的请求 URL。
 *
 * 存在理由：`vi.fn(() => …)` 的调用签名被推断为空元组，
 * 直接写 `mock.mock.calls[0][0]` 会触发 TS2493；统一走本 helper 收口。
 */
export function requestUrlAt(mock: { mock: { calls: unknown[][] } }, index = 0): string {
  return String(mock.mock.calls[index]?.[0] ?? '')
}

/** 取最近一次 fetch 调用的 JSON 请求体。 */
export function lastRequestBody<T>(mock: { mock: { calls: unknown[][] } }): T {
  const calls = mock.mock.calls
  const init = calls[calls.length - 1]?.[1] as RequestInit | undefined
  return JSON.parse(String(init?.body ?? 'null')) as T
}

