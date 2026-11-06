import { ApiError } from './errors'
import { newRequestId } from './id'
import type { WorkspaceData } from '../types/domain'
import { defaultSystemConfig } from './defaults'
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
  const csrf = readCookie('albedo_csrf')
  return {
    'X-Request-Id': newRequestId(),
    ...(csrf ? { 'X-CSRF-Token': csrf } : {}),
    ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
    ...headers,
  }
}

async function request(path: string, options: HttpOptions = {}): Promise<Response> {
  const configured = useWorkspace.getState().systemConfig.limits.httpRequestTimeoutMs
  const { timeoutMs = configured || 15000, headers, ...rest } = options
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(`${BASE}${path}`, {
      ...rest,
      credentials: 'include',
      signal: controller.signal,
      headers: buildHeaders(headers, rest.body),
    })
    if (!response.ok) {
      let code = 'validation_error'
      let message = `请求失败（${response.status}）`
      try {
        const body = await response.json()
        code = body?.error?.code ?? code
        message = body?.error?.message ?? message
      } catch {
        if (response.status === 401) {
          code = 'invalid_api_key'
          message = '登录状态已失效，请重新登录'
        }
      }
      throw new ApiError(code, message)
    }
    return response
  } catch (reason) {
    if (reason instanceof ApiError) throw reason
    if (reason instanceof DOMException && reason.name === 'AbortError') {
      throw new ApiError('upstream_timeout', '请求超时，请稍后重试')
    }
    throw new ApiError('upstream_unavailable', '无法连接后端服务，请确认后端已启动')
  } finally {
    window.clearTimeout(timer)
  }
}

export async function http<T>(path: string, options: HttpOptions = {}): Promise<T> {
  const response = await request(path, options)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
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

export async function replaceFromServer(): Promise<WorkspaceData> {
  const data = await fetchBootstrap()
  useWorkspace.getState().replaceAll(data)
  return data
}
