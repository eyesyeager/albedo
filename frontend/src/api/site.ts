/**
 * 租户站点配置接口。契约：docs/api-spec.md §4.2.2
 *
 * 🔴 站点品牌文案一律来自本接口，禁止在前端硬编码任何租户文案。
 */
import request from '@/utils/request'

export interface SiteConfig {
  tenantId: string
  configVersion: number
  timezone: string
  locale: string
  siteTitle: string
  logoUrl: string
  faviconUrl: string
  welcomeText: string
  inputPlaceholder: string
  loginText: string
  registerText: string
  newChatText: string
  emptySessionText: string
  agentUnavailableText: string
  footerDisclaimer: string
  themePrimaryColor: string
}

/** 站点级状态（api-spec.md §4.2.1：后端唯一允许返回非 200 的端点）。 */
export type SiteProbeResult = 'ok' | 'notFound' | 'suspended' | 'unavailable' | 'unreachable'

const PROBE_STATUS: Readonly<Record<number, SiteProbeResult>> = {
  200: 'ok',
  404: 'notFound',
  403: 'suspended',
  503: 'unavailable',
}

export const siteApi = {
  config: (): Promise<SiteConfig> => request.get<SiteConfig>('/api/v1/site/config'),

  /**
   * 探测站点级状态，供状态页"重试"使用。
   *
   * 🔴 故意不走 request.ts：`/site/status` 是站点级 HTML 端点（非 200 是正常语义），
   *    且无需鉴权 —— 因此这里不注入 token、不做错误码分流，不构成第二套鉴权实现。
   */
  probeStatus: async (): Promise<SiteProbeResult> => {
    const response = await fetch('/site/status', {
      method: 'GET',
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'manual',
    }).catch(() => null)
    if (response === null) {
      return 'unreachable'
    }
    return PROBE_STATUS[response.status] ?? 'unavailable'
  },
}
