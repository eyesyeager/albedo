/**
 * 每日对话额度类型（契约：docs/api-spec.md §7.15.2 额度快照，🔴 恰 9 键）。
 *
 * 🔴 纪律：
 *   1. 快照形态**只有一个**：`GET /api/v1/me/quota` 成功响应与 `30070` 的 `data` 同形
 *   2. 🔴 后端**不下发** QPM 阈值，也**不下发** `retryAfterSeconds`：
 *      QPM 与日额度是两个独立概念（恢复条件相差 5 个数量级），
 *      本文件因此**不声明**任何秒级等待字段，从类型层就杜绝"日额度倒计时"
 *   3. 🔴 `limit` / `remaining` 在 `enabled=false` 时必须为 null（禁止伪造数值上限）
 *   4. 时间一律 ISO-8601 UTC；展示由前端按 `timezone` 渲染（禁止用浏览器本地时区推算）
 */

/** 额度状态（与 `enabled` / `remaining` 恒一致，api-spec §7.15.2）。 */
export type QuotaStatus = 'available' | 'exhausted' | 'unlimited'

/** 额度快照（🔴 恰 9 键，多一键或少一键均为契约缺陷）。 */
export interface QuotaSnapshot {
  /** 当前有效策略下每日限额是否启用 */
  enabled: boolean
  /** 当前有效日总量；`enabled=false` 时恒为 null */
  limit: number | null
  /** 当日**已结算**次数（不含在途预占） */
  used: number
  /** 剩余可用次数（含在途预占的保守口径）；`enabled=false` 时恒为 null */
  remaining: number | null
  status: QuotaStatus
  /** 当前额度日起点（UTC） */
  periodStart: string
  /** 下一个租户当地零点（UTC） */
  resetsAt: string
  /** 租户 IANA 时区，🔴 渲染 resetsAt 的唯一依据 */
  timezone: string
  /** 快照计算时刻（UTC） */
  asOf: string
}

/** 🔴 快照必备键（api-spec §7.15.2 / 核对项 K13）：解析层逐键校验。 */
export const QUOTA_SNAPSHOT_KEYS = [
  'enabled',
  'limit',
  'used',
  'remaining',
  'status',
  'periodStart',
  'resetsAt',
  'timezone',
  'asOf',
] as const

/**
 * 额度视图阶段。
 *
 * - `hidden`：🔴 匿名用户（uid === null）—— 不请求、不展示、不占位、不骨架（AC-QUOTA-013）
 * - `loading`：已登录且首次查询在途
 * - `unavailable`：已登录但查询失败且无可信快照（🔴 不展示旧主体快照）
 * - `ready`：持有当前主体的权威快照
 */
export type QuotaPhase = 'hidden' | 'loading' | 'unavailable' | 'ready'

/** 传给展示组件的额度视图（🔴 组件不直接访问 Store，便于纯 props 测试）。 */
export interface QuotaView {
  phase: QuotaPhase
  snapshot: QuotaSnapshot | null
}

/**
 * 状态轨展示态（design-system.md §15.1.1 / §15.3 / §15.3.1）。
 *
 * 🔴 判定优先级（不可调整）：
 *   hidden → exhausted → rateLimited → unlimited → loading / unavailable → lastOne → low → normal
 * 🔴 `exhausted` 高于 `rateLimited`：日额度用尽时不得启动或保留 QPM 秒级倒计时。
 */
export type QuotaDisplayState =
  | 'hidden'
  | 'loading'
  | 'unavailable'
  | 'unlimited'
  | 'normal'
  | 'low'
  | 'lastOne'
  | 'rateLimited'
  | 'exhausted'
