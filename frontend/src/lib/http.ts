import { ApiError } from './errors'
import { newRequestId } from './id'
import type { WorkspaceData } from '../types/domain'
import { defaultSystemConfig, emptyWorkspace } from './defaults'
import { useAuth } from '../store/auth'
import { useWorkspace } from '../store/workspace'

const BASE = '/api/v1'

function readCookie(name: string): string {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : ''
}

export interface HttpOptions extends Omit<RequestInit, 'headers'> {
  headers?: Record<string, string>
  timeoutMs?: number
}

function buildHeaders(headers: Record<string, string> | undefined, body: BodyInit | null | undefined): Record<string, string> {
  // 生产使用 __Host- 前缀的 CSRF Cookie；dev 仍是旧名称。
  const csrf = readCookie('__Host-albedo_csrf') || readCookie('albedo_csrf')
  return {
    'X-Request-Id': newRequestId(),
    ...(csrf ? { 'X-CSRF-Token': csrf } : {}),
    ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
    ...headers,
  }
}

/** 会话失效或主动登出时同步清空全局工作区，且不依赖网络请求是否成功。 */
export function clearSessionState(): void {
  useWorkspace.getState().replaceAll(emptyWorkspace())
  useAuth.getState().logout()
}

async function ensureOk(response: Response, skipAuthRedirect = false): Promise<Response> {
  if (response.ok) return response
  let code = 'validation_error'
  let message = `请求失败（${response.status}）`
  try {
    const body = await response.json()
    code = body?.error?.code ?? code
    message = body?.error?.message ?? message
  } catch {
    // 网关 HTML / 空响应体：保留默认文案，但仍要按状态码处理会话失效
  }
  if (response.status === 401) {
    if (!skipAuthRedirect) clearSessionState()
    if (code === 'validation_error') code = 'invalid_api_key'
    if (message === `请求失败（${response.status}）`) message = '登录状态已失效，请重新登录'
  }
  throw new ApiError(code, message)
}

function createTimeoutController(timeoutMs: number, externalSignal?: AbortSignal | null) {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  const onExternalAbort = (): void => controller.abort()
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort()
    else externalSignal.addEventListener('abort', onExternalAbort, { once: true })
  }
  const release = (): void => {
    window.clearTimeout(timer)
    if (externalSignal) externalSignal.removeEventListener('abort', onExternalAbort)
  }
  return { controller, release }
}

async function request(path: string, options: HttpOptions = {}): Promise<Response> {
  const configured = useWorkspace.getState().systemConfig.limits.httpRequestTimeoutMs
  const { timeoutMs = configured || 15000, headers, signal: externalSignal, ...rest } = options
  const { controller, release } = createTimeoutController(timeoutMs, externalSignal)
  try {
    const response = await fetch(`${BASE}${path}`, {
      ...rest,
      credentials: 'include',
      signal: controller.signal,
      headers: buildHeaders(headers, rest.body),
    })
    // 登录接口自身的 401 是「Key 错误」，不应清空工作区或跳登录页
    return await ensureOk(response, path.startsWith('/auth/login'))
  } catch (reason) {
    if (reason instanceof ApiError) throw reason
    if (reason instanceof DOMException && reason.name === 'AbortError') {
      throw new ApiError('upstream_timeout', '请求超时，请稍后重试')
    }
    throw new ApiError('upstream_unavailable', '无法连接后端服务，请确认后端已启动')
  } finally {
    release()
  }
}

export interface EventStreamHandle {
  reader: ReadableStreamDefaultReader<Uint8Array>
  /** 流消费结束后必须调用，用于释放超时计时器。 */
  close: () => void
}

/**
 * 以 SSE 方式发起请求：超时覆盖整个流的生命周期，而不是只覆盖响应头返回。
 */
export async function openEventStream(path: string, options: HttpOptions = {}): Promise<EventStreamHandle> {
  const configured = useWorkspace.getState().systemConfig.limits.chatTimeoutMs
  const { timeoutMs = configured || 150000, headers, signal: externalSignal, ...rest } = options
  const { controller, release } = createTimeoutController(timeoutMs, externalSignal)
  const close = (): void => release()
  try {
    const response = await fetch(`${BASE}${path}`, {
      ...rest,
      credentials: 'include',
      signal: controller.signal,
      headers: buildHeaders(headers, rest.body),
    })
    await ensureOk(response)
    if (!response.body) throw new ApiError('stream_unsupported', '当前浏览器不支持流式响应')
    return { reader: response.body.getReader(), close }
  } catch (reason) {
    close()
    if (reason instanceof ApiError) throw reason
    if (reason instanceof DOMException && reason.name === 'AbortError') {
      throw new ApiError('upstream_timeout', '等待模型响应超时，请稍后重试')
    }
    throw new ApiError('upstream_unavailable', '无法连接后端服务，请确认后端已启动')
  }
}

export async function http<T>(path: string, options: HttpOptions = {}): Promise<T> {
  const response = await request(path, options)
  if (response.status === 204) return undefined as T
  try {
    return (await response.json()) as T
  } catch {
    throw new ApiError('invalid_response', '服务端返回了无法解析的响应，请稍后重试')
  }
}

export function jsonBody(value: unknown): string {
  return JSON.stringify(value)
}

export async function httpBlob(path: string): Promise<Blob> {
  const response = await request(path)
  return await response.blob()
}

export function hydrate(data: WorkspaceData): WorkspaceData {
  return {
    ...data,
    systemConfig: data.systemConfig ?? defaultSystemConfig(),
    settings: { ...data.settings, accessKey: '' },
    chatSessions: data.chatSessions ?? {},
    exportTasks: data.exportTasks ?? [],
    trends: data.trends ?? { d14: [], d30: [] },
  }
}

export async function fetchBootstrap(): Promise<WorkspaceData> {
  return hydrate(await http<WorkspaceData>('/workspace/bootstrap'))
}

export async function fetchSession(): Promise<{ authenticated: boolean; workspaceName?: string }> {
  return await http<{ authenticated: boolean; workspaceName?: string }>('/auth/session')
}

// 全量快照只允许最新一次生效，避免较慢返回的旧快照覆盖期间已完成的局部写操作。
let bootstrapEpoch = 0

export async function replaceFromServer(): Promise<WorkspaceData> {
  const epoch = ++bootstrapEpoch
  const data = await fetchBootstrap()
  if (epoch === bootstrapEpoch) useWorkspace.getState().replaceAll(data)
  return data
}
