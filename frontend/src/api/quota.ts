/**
 * 当前用户每日对话额度接口（契约：docs/api-spec.md §7.15.1）。
 *
 * 🔴 纪律：
 *   1. **零参数**：禁止传 `tenantId` / `uid` / `date`（后端只认可信 Host + 认证 uid，
 *      传入一律忽略并记安全日志）—— 参数从类型层就不存在
 *   2. 🔴 `@Permission(USER)`：调用前必须确认已登录，匿名调用会返回 20001/20002
 *      并触发 `request.ts` 的「清 token + 整页跳 SSO」，导致用户被无故弹去 SSO
 *      （匿名不请求的纪律收口在 `stores/quota.ts`）
 *   3. 响应 `data` 恰 9 键，🔴 一律经 `utils/quotaSnapshot.ts` 校验后再进 Store
 */
import type { QuotaSnapshot } from '@/types/quota'
import request from '@/utils/request'

export const quotaApi = {
  me: (): Promise<QuotaSnapshot> => request.get<QuotaSnapshot>('/api/v1/me/quota'),
}
