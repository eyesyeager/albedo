/**
 * 产品埋点上报接口（契约：docs/api-spec.md §7.10.1）。
 *
 * 🔴 纪律：
 *   1. 字段白名单以本文件的 `AnalyticsEvent` 为唯一形态，未列出的字段不允许出现
 *   2. 🔴 绝不上报：消息正文 / token / 凭据 / 完整手机号 / 完整邮箱 / 工具输入输出正文
 *   3. 上报失败**永不影响主链路**（服务端一律 code=0；网络异常由调用方静默降级）
 */
import request from '@/utils/request'

/** 单条事件（字段白名单，api-spec §7.10.1）。 */
export interface AnalyticsEvent {
  /** 去重键，`^[A-Za-z0-9_-]{8,64}$`，客户端生成 */
  clientEventId: string
  /** 必须命中 `observability.analyticsAllowedEvents` 白名单 */
  eventName: string
  /** ISO-8601 UTC */
  occurredAt: string
  loginState?: 'anonymous' | 'logged_in'
  configVersion?: number
  conversationId?: string
  agentId?: string
  agentVersion?: number
  toolType?: string
  toolKey?: string
  status?: string
  result?: 'success' | 'failed' | 'denied'
  errorCode?: number
  durationMs?: number
  latencyMs?: number
  /** 🔴 只允许长度，禁止正文 */
  charCount?: number
  tokenUsage?: { promptTokens: number; completionTokens: number; totalTokens: number }
  source?: string
  /** 🔴 仅站内 path，禁止携带 query / hash（防 Token 经 URL 泄露） */
  pagePath?: string
  action?: string
}

export interface AnalyticsIngestResult {
  accepted: number
  duplicated: number
  discarded: number
}

export const analyticsApi = {
  report: (events: readonly AnalyticsEvent[]): Promise<AnalyticsIngestResult> =>
    request.post<AnalyticsIngestResult>('/api/v1/events', { events }),
}
