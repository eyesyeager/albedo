/**
 * 统一请求封装 —— 本项目**唯一**鉴权实现。
 *
 * 🔴 纪律（框架 §14.3 / docs/architecture.md ADR-003）：
 *   1. 全站只用 fetch，禁止再引入 axios：SSE 必须用 fetch，两套客户端会导致鉴权逻辑分叉
 *   2. 业务代码禁止自行处理 20000~20005，禁止 router.push('/login')（本项目无 /login 路由）
 *   3. 业务码 30000+ 抛 ApiError 由调用方按语义展示，绝不跳登录
 *   4. Token 只存 localStorage、只走 Header，禁止落 URL / 日志 / 埋点
 */
import { AUTH_ERROR_CODES, type ApiResult } from '@/types/api'

const TOKEN_KEY = 'authorization'
const HEADER_TOKEN = 'authorization'

const API_DOMAIN: string = import.meta.env.VITE_API_DOMAIN ?? ''
const SSO_URL: string = import.meta.env.VITE_SSO_URL ?? ''
const SSO_CLIENT_ID: string = import.meta.env.VITE_SSO_CLIENT_ID ?? ''

/**
 * 解析后端 API 域名。
 *
 * 🔴 前后端分域名部署（生产）：前端 `albedo-{tenant}.eyescode.top` → API `api-albedo-{tenant}.eyescode.top`。
 *    租户号是运行时才能从 `location.hostname` 得知，无法在构建期写入，
 *    因此这里按约定前缀在运行时推导，避免「每个租户打一份产物」。
 *
 * 优先级：
 *   1. 显式配置 `VITE_API_DOMAIN`（本地调试 / 非标准域名时使用，例如 .env 里填具体地址）
 *   2. 本地开发无 API 域名时留空 → 走 Vite proxy 同源（localhost:5173 / 127.0.0.1:5173）
 *   3. 生产按约定推导：albedo-* → api-albedo-*
 */
function resolveApiDomain(): string {
  if (API_DOMAIN) {
    return API_DOMAIN
  }
  const host = location.hostname
  // 只有形如 albedo-{tenant}.… 的前端域名才需要推导 API 域名；
  // localhost / IP 等本地开发域名保持同源（空 = 相对路径，由 Vite proxy / 同源 Nginx 兜底）。
  if (!host.startsWith('albedo-')) {
    return ''
  }
  return `https://api-${host}`
}

const DEFAULT_TIMEOUT_MS = 60_000

/** 业务错误（含参数错误 / 业务错误 / 系统错误）。 */
export class ApiError extends Error {
  constructor(
    readonly code: number,
    message: string,
    /**
     * 服务端 `data` 载荷。
     * 🔴 部分错误码的必要信息只在 data 中（如 `10005` 的 `retryAfterSeconds`，api-spec §7.12），
     * 丢弃它会导致前端只能硬编码等待秒数 —— 故一并透传，由调用方按码读取。
     */
    readonly data: unknown = null,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** 网络层错误（超时、断网、非 JSON 响应等）。 */
export class NetworkError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NetworkError'
  }
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function isLoggedIn(): boolean {
  const token = getToken()
  return token !== null && token.length > 0
}

/** 构造耶瞳 SSO 授权地址（回跳地址恒为当前页完整 URL）。 */
export function buildSsoUrl(redirectUrl: string = location.href): string {
  return `${SSO_URL}/OAuth2?clientId=${SSO_CLIENT_ID}&redirectUrl=${encodeURIComponent(redirectUrl)}`
}

/**
 * 清 token 并整页跳转耶瞳 SSO（登录 / 注册 / 鉴权失效 / 退出登录的唯一出口）。
 * 🔴 必须整页跳转，不得使用站内路由。
 */
export function redirectToSso(redirectUrl: string = location.href): void {
  clearToken()
  location.href = buildSsoUrl(redirectUrl)
}

/**
 * 解析 SSO 回跳携带的 token 并立即从 URL 清除（AC-AUTH-002）。
 * 由 main.ts 在应用启动最前调用，保证 token 不进入日志 / Referer / 埋点。
 *
 * @returns 是否成功接收到 token
 */
export function consumeTokenFromUrl(): boolean {
  const params = new URLSearchParams(location.search)
  const token = params.get('authorization')
  if (token === null || token.length === 0) {
    return false
  }
  setToken(token)
  // 保留原路径与除 authorization 外的安全业务参数
  params.delete('authorization')
  const query = params.toString()
  const cleanUrl = `${location.pathname}${query.length > 0 ? `?${query}` : ''}${location.hash}`
  history.replaceState(null, '', cleanUrl)
  return true
}

/** auth-type=1 续期：响应头出现新 token 时立即回写，否则下次请求会失败。 */
export function applyRenewedToken(headers: Headers): void {
  const renewed = headers.get(HEADER_TOKEN)
  if (renewed !== null && renewed.length > 0 && renewed !== getToken()) {
    setToken(renewed)
  }
}

/** 请求头统一构造（供 request.ts 与 streamRequest.ts 共用，保证鉴权行为一致）。 */
export function buildAuthHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...(extra ?? {}) }
  const token = getToken()
  if (token !== null && token.length > 0) {
    headers[HEADER_TOKEN] = token
  }
  return headers
}

export function resolveUrl(path: string, params?: RequestParams): string {
  const base = path.startsWith('http') ? path : `${resolveApiDomain()}${path}`
  if (params === undefined) {
    return base
  }
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    search.append(key, String(value))
  })
  const query = search.toString()
  return query.length > 0 ? `${base}${base.includes('?') ? '&' : '?'}${query}` : base
}

export type RequestParams = Record<string, string | number | boolean | undefined | null>

export interface RequestOptions {
  params?: RequestParams
  headers?: Record<string, string>
  timeoutMs?: number
  signal?: AbortSignal
}

async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  options: RequestOptions = {},
): Promise<T> {
  const controller = new AbortController()
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  if (options.signal !== undefined) {
    options.signal.addEventListener('abort', () => controller.abort(), { once: true })
  }

  let response: Response
  try {
    response = await fetch(resolveUrl(path, options.params), {
      method,
      headers: buildAuthHeaders({
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(options.headers ?? {}),
      }),
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      credentials: 'omit', // 本项目鉴权不依赖 Cookie
    })
  } catch (e: unknown) {
    throw new NetworkError(e instanceof Error ? e.message : 'network error')
  } finally {
    clearTimeout(timer)
  }

  // ① 续期 token 回写（必须在解析 body 之前完成，避免异常路径漏写）
  applyRenewedToken(response.headers)

  // ② /api/v1/** 恒为 HTTP 200；非 200 属于框架级故障
  if (!response.ok) {
    throw new NetworkError(`HTTP ${response.status}`)
  }

  let payload: ApiResult<T>
  try {
    payload = (await response.json()) as ApiResult<T>
  } catch {
    // 技术性错误信息（仅进日志）；用户可见文案由调用方取 locales，🔴 不直接展示本串
    throw new NetworkError('response is not valid JSON')
  }

  // ③ 鉴权失效 → 清 token + 整页跳 SSO（本项目无 /login 路由）
  if (AUTH_ERROR_CODES.includes(payload.code)) {
    redirectToSso()
    throw new ApiError(payload.code, payload.message, payload.data)
  }

  // ④ 其余非 0（10001 参数 / 30000+ 业务 / 50000+ 系统）→ 抛业务错误，由调用方展示
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message, payload.data)
  }

  // ⑤ 成功统一解包 data
  return payload.data
}

export default {
  get: <T>(path: string, options?: RequestOptions): Promise<T> =>
    request<T>('GET', path, undefined, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> =>
    request<T>('POST', path, body, options),
  put: <T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> =>
    request<T>('PUT', path, body, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions): Promise<T> =>
    request<T>('PATCH', path, body, options),
  delete: <T>(path: string, options?: RequestOptions): Promise<T> =>
    request<T>('DELETE', path, undefined, options),
}
