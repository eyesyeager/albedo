/**
 * 产品埋点上报（契约：docs/api-spec.md §7.10.1、PRD §15.2）。
 *
 * 🔴 五条纪律（违反即为隐私缺陷）：
 *   1. **白名单事件名**：只有命中 `sys_config: observability.analyticsAllowedEvents` 的事件才上报，
 *      未命中直接丢弃并 `console.error`（🔴 白名单由后端下发，前端不得内置业务白名单）
 *   2. **字段白名单**：未列入 `AnalyticsEvent` 的字段一律剔除
 *   3. **敏感拦截**：出现禁止键名（token / content / phone / email …）或值级命中
 *      手机号 / 邮箱 / 长数字时，🔴 整条事件丢弃
 *   4. **批量上限**取 `observability.analyticsBatchMax`，🔴 前端不得写死批量大小
 *   5. **静默降级**：上报失败只 `console.error`，绝不影响主链路、绝不阻塞交互
 */
import { analyticsApi, type AnalyticsEvent } from '@/api/events'
import { useConfigStore } from '@/stores/config'
import { newIdempotencyKey } from '@/utils/idempotency'
import { isLoggedIn } from '@/utils/request'
import { ANALYTICS_FLUSH_DELAY_MS } from '@/utils/uiConstants'

/** 事件名常量（仅代码标识；是否允许上报以后端下发白名单为准）。 */
export const ANALYTICS_EVENT = {
  tenantSiteView: 'tenantSiteView',
  authClick: 'authClick',
  authResult: 'authResult',
  agentSelect: 'agentSelect',
  conversationCreate: 'conversationCreate',
  messageSend: 'messageSend',
  messageFirstToken: 'messageFirstToken',
  messageComplete: 'messageComplete',
  toolCallResult: 'toolCallResult',
  conversationRename: 'conversationRename',
  conversationDelete: 'conversationDelete',
} as const

/** 调用方可传字段（clientEventId / occurredAt / loginState / pagePath 由本模块补全）。 */
export type AnalyticsPayload = Omit<
  AnalyticsEvent,
  'clientEventId' | 'eventName' | 'occurredAt' | 'loginState' | 'pagePath'
>

/** 🔴 禁止键名（不区分大小写的子串匹配，api-spec §7.10.1）。 */
const FORBIDDEN_KEY_FRAGMENTS: readonly string[] = [
  'messagecontent',
  'content',
  'text',
  'prompt',
  'systemprompt',
  'skillinstruction',
  'instruction',
  'token',
  'authorization',
  'jwt',
  'credential',
  'apikey',
  'secret',
  'password',
  'phone',
  'mobile',
  'email',
  'idcard',
  'bankcard',
]

/**
 * 🔴 禁止键名的**精确豁免**（仅限契约白名单字段且值形态被强约束者）。
 *
 * 存在理由（D-008）：禁止键名用「不区分大小写子串匹配」，
 * 合法字段 `tokenUsage` 会命中 `token` 片段而被整条丢弃 ——
 * 结果是 api-spec §7.10.1 明确要求上报的 token 用量永久缺失。
 * 🔴 豁免必须是**精确键名**匹配，且该字段值经 `pickTokenUsage` 归一为三个 number，
 * 不存在携带凭据的可能；禁止扩大为前缀 / 子串豁免。
 */
const FORBIDDEN_KEY_EXACT_EXEMPT: readonly string[] = ['tokenUsage']

/** 允许上报的字段名（字段白名单，api-spec §7.10.1）。 */
const ALLOWED_FIELDS: readonly string[] = [
  'clientEventId',
  'eventName',
  'occurredAt',
  'loginState',
  'configVersion',
  'conversationId',
  'agentId',
  'agentVersion',
  'toolType',
  'toolKey',
  'status',
  'result',
  'errorCode',
  'durationMs',
  'latencyMs',
  'charCount',
  'tokenUsage',
  'source',
  'pagePath',
  'action',
]

/** 值级敏感判定：手机号 / 邮箱 / ≥12 位长数字（身份证、银行卡）。 */
const SENSITIVE_VALUE_PATTERNS: readonly RegExp[] = [
  /^\+?\d{1,3}?[-\s]?1[3-9]\d{9}$/,
  /^1[3-9]\d{9}$/,
  /[^\s@]+@[^\s@]+\.[^\s@]+/,
  /\d{12,}/,
]

/**
 * 免值级扫描的字段：系统标识（雪花 ID 天然是 18~19 位长数字，
 * 若参与"≥12 位长数字"判定会把全部合法事件误杀）。
 * 🔴 这些字段本身不含个人信息，且已在服务端做同租户归属校验。
 */
const IDENTIFIER_FIELDS: readonly string[] = ['conversationId', 'agentId', 'toolKey']

/** `tokenUsage` 的合法子字段。 */
const TOKEN_USAGE_FIELDS: readonly string[] = ['promptTokens', 'completionTokens', 'totalTokens']

/** 生成去重键：`^[A-Za-z0-9_-]{8,64}$`（服务端按 uk(tenant_id, client_event_id) 去重）。 */
export function newClientEventId(): string {
  return `e_${newIdempotencyKey()}`
}

/** 是否命中敏感值（导出以便测试直接断言拦截规则）。 */
export function hasSensitiveValue(value: unknown): boolean {
  if (value === undefined || value === null) {
    return false
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return false
  }
  if (typeof value === 'string') {
    return SENSITIVE_VALUE_PATTERNS.some((pattern) => pattern.test(value))
  }
  if (typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some(hasSensitiveValue)
  }
  // 函数 / symbol 等一律视为不可上报
  return true
}

/** 是否命中禁止键名。 */
export function hasForbiddenKey(payload: Readonly<Record<string, unknown>>): boolean {
  return Object.keys(payload).some((key) => {
    if (FORBIDDEN_KEY_EXACT_EXEMPT.includes(key)) {
      return false
    }
    const lower = key.toLowerCase()
    return FORBIDDEN_KEY_FRAGMENTS.some((fragment) => lower.includes(fragment))
  })
}

/**
 * 构造合法事件；不合法返回 null（调用方静默丢弃）。
 *
 * @param allowedEvents 后端下发的事件名白名单
 */
export function buildAnalyticsEvent(
  eventName: string,
  payload: Readonly<Record<string, unknown>>,
  allowedEvents: readonly string[],
): AnalyticsEvent | null {
  if (!allowedEvents.includes(eventName)) {
    console.error('[analytics] 事件名不在下发白名单内，已丢弃', eventName)
    return null
  }
  if (hasForbiddenKey(payload)) {
    console.error('[analytics] 载荷命中禁止键名，已整条丢弃', eventName)
    return null
  }
  const sensitiveEntry = Object.entries(payload).find(
    ([key, value]) => !IDENTIFIER_FIELDS.includes(key) && hasSensitiveValue(value),
  )
  if (sensitiveEntry !== undefined) {
    console.error('[analytics] 载荷命中敏感值规则，已整条丢弃', eventName)
    return null
  }
  const event: AnalyticsEvent = {
    clientEventId: newClientEventId(),
    eventName,
    occurredAt: new Date().toISOString(),
    loginState: isLoggedIn() ? 'logged_in' : 'anonymous',
    // 🔴 只取站内 path，主动去除 query 与 hash（防 Token 经 URL 泄露，AC-AUTH-002）
    pagePath: location.pathname,
  }
  Object.entries(payload).forEach(([key, value]) => {
    if (!ALLOWED_FIELDS.includes(key) || value === undefined || value === null) {
      return
    }
    if (key === 'tokenUsage') {
      const usage = pickTokenUsage(value)
      if (usage !== null) {
        event.tokenUsage = usage
      }
      return
    }
    Object.assign(event, { [key]: value })
  })
  return event
}

function pickTokenUsage(value: unknown): AnalyticsEvent['tokenUsage'] | null {
  if (value === null || typeof value !== 'object') {
    return null
  }
  const source = value as Record<string, unknown>
  const picked: Record<string, number> = {}
  TOKEN_USAGE_FIELDS.forEach((field) => {
    const item = source[field]
    picked[field] = typeof item === 'number' ? item : 0
  })
  return {
    promptTokens: picked.promptTokens,
    completionTokens: picked.completionTokens,
    totalTokens: picked.totalTokens,
  }
}

let queue: AnalyticsEvent[] = []
let timer: ReturnType<typeof setTimeout> | null = null
let listenersBound = false

/** 采集一条事件（失败静默；🔴 绝不抛出，绝不阻塞调用方）。 */
export function track(eventName: string, payload: AnalyticsPayload = {}): void {
  const config = useConfigStore()
  if (!config.bool('observability', 'analyticsEnabled', true)) {
    return
  }
  // 后端按 hash(clientEventId) 稳定采样；仅在下发采样率为 0 时提前跳过，避免无意义请求
  if (config.num('observability', 'analyticsSampleRate', 1) <= 0) {
    return
  }
  const allowed = config.json<readonly string[]>('observability', 'analyticsAllowedEvents', [])
  const event = buildAnalyticsEvent(eventName, payload as Record<string, unknown>, allowed)
  if (event === null) {
    return
  }
  queue.push(event)
  bindLifecycleListeners()
  if (queue.length >= batchMax()) {
    void flush()
    return
  }
  scheduleFlush()
}

/** 立即上报队列中的事件（分批，单批不超过下发上限）。 */
export async function flush(): Promise<void> {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
  if (queue.length === 0) {
    return
  }
  const batch = queue.slice(0, batchMax())
  queue = queue.slice(batch.length)
  try {
    await analyticsApi.report(batch)
  } catch (e: unknown) {
    // 🔴 埋点失败不得影响主链路，也不做无限重试（避免故障放大）
    console.error('[analytics] 上报失败，已丢弃本批事件', e)
  }
  if (queue.length > 0) {
    scheduleFlush()
  }
}

/** 仅供测试与页面卸载使用：清空未上报队列。 */
export function resetAnalyticsQueue(): void {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
  queue = []
}

function batchMax(): number {
  const config = useConfigStore()
  const value = config.num('observability', 'analyticsBatchMax', 0)
  // 未下发上限时按「单条即发」处理，🔴 不猜测批量大小
  return value >= 1 ? Math.floor(value) : 1
}

function scheduleFlush(): void {
  if (timer !== null) {
    return
  }
  timer = setTimeout(() => {
    timer = null
    void flush()
  }, ANALYTICS_FLUSH_DELAY_MS)
}

function bindLifecycleListeners(): void {
  if (listenersBound || typeof document === 'undefined') {
    return
  }
  listenersBound = true
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      void flush()
    }
  })
}
