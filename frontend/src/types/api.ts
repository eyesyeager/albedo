/**
 * 接口通用类型 —— 与 docs/api-spec.md §1.2 / §1.3 一一对应。
 *
 * 🔴 全项目禁止 any；🔴 所有 id / uid 为 string（BIGINT 精度保护，ADR-004）。
 */

/** 统一响应体（HTTP 恒 200，业务结果由 code 承载）。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  timestamp: number
}

/** 统一分页结构。 */
export interface PageData<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** 分页查询基类。 */
export interface PageQuery {
  page?: number
  pageSize?: number
}

/**
 * 错误码常量（唯一来源：docs/api-spec.md §2.2 错误码登记表）。
 * 🔴 禁止在业务代码中出现未登记的裸数字错误码。
 */
export const ERROR_CODE = {
  SUCCESS: 0,
  VALIDATION_FAILED: 10001,
  PERMISSION_DENIED: 10003,
  RESOURCE_NOT_FOUND: 10004,
  RATE_LIMITED: 10005,
  AUTH_FORBIDDEN: 20000,
  AUTH_TOKEN_INVALID: 20001,
  AUTH_TOKEN_EXPIRED: 20002,
  AUTH_ACCOUNT_FROZEN: 20003,
  AUTH_ACCOUNT_NOT_FOUND: 20004,
  AUTH_ROLE_ILLEGAL: 20005,
  AUTH_PARAM_ILLEGAL: 20008,
  TENANT_NOT_FOUND: 30010,
  TENANT_SUSPENDED: 30011,
  TENANT_CONFIG_UNAVAILABLE: 30012,
  TENANT_CONTEXT_MISSING: 30013,
  VERSION_CONFLICT: 30020,
  PUBLISH_VALIDATE_FAILED: 30021,
  AGENT_UNAVAILABLE: 30030,
  AGENT_DISABLED: 30031,
  CONVERSATION_READONLY: 30040,
  MESSAGE_TOO_LONG: 30041,
  TOOL_DENIED: 30050,
  TOOL_TIMEOUT: 30051,
  MCP_UNAVAILABLE: 30052,
  TOOL_ARGS_INVALID: 30053,
  TOOL_LOOP_LIMIT_EXCEEDED: 30054,
  TOOL_CONFIRM_CONFLICT: 30055,
  TOOL_RETRY_BLOCKED: 30056,
  TOOL_EXECUTION_FAILED: 30057,
  RUNTIME_CONFIG_INVALID: 30060,
  CACHE_INVALIDATION_FAILED: 30061,
  /**
   * 今日对话额度已用尽（api-spec §2.2 / §7.15，M3.1 新增）。
   * 🔴 `data` 恒为额度快照（9 键），🔴 不含 `retryAfterSeconds`：
   *    它不是秒级可恢复的频率限制，绝不得进入 QPM 倒计时。
   */
  DAILY_QUOTA_EXHAUSTED: 30070,
  UPSTREAM_UNAVAILABLE: 50002,
  INTERNAL_ERROR: 50003,
} as const

/**
 * 鉴权失效错误码：命中即「清 token + 整页跳 SSO」。
 * 🔴 20000~20999 是耶瞳保留段，业务码禁止占用；30000+ 一律按业务错误展示，不得跳登录。
 */
export const AUTH_ERROR_CODES: readonly number[] = [
  ERROR_CODE.AUTH_FORBIDDEN,
  ERROR_CODE.AUTH_TOKEN_INVALID,
  ERROR_CODE.AUTH_TOKEN_EXPIRED,
  ERROR_CODE.AUTH_ACCOUNT_FROZEN,
  ERROR_CODE.AUTH_ACCOUNT_NOT_FOUND,
  ERROR_CODE.AUTH_ROLE_ILLEGAL,
]

/** 站点级不可用错误码：前端应路由到对应状态视图。 */
export const SITE_UNAVAILABLE_CODES: readonly number[] = [
  ERROR_CODE.TENANT_NOT_FOUND,
  ERROR_CODE.TENANT_SUSPENDED,
  ERROR_CODE.TENANT_CONFIG_UNAVAILABLE,
]

/**
 * 业务错误码段（api-spec.md §2.2）：契约内**可预期**的业务失败，展示层必有确定呈现路径。
 * 🔴 50000+ 系统异常不在此段：它们代表真故障，日志必须留 error 级。
 */
export const BUSINESS_ERROR_CODE_MIN = 30000
export const BUSINESS_ERROR_CODE_MAX = 39999

/**
 * 通用段（1xxxx）中**已被界面消费**的错误码：命中即表示"产品逻辑已按契约呈现"，不是缺陷信号。
 *   - 10003 无权限 → 成员禁用提示
 *   - 10004 资源不存在 → 错误态 + 重试（含跨租户隔离的正确表现）
 *   - 10005 频率超限 → 限流倒计时
 * 🔴 10001 参数错误**不得**列入：它意味着前端传参违约，必须保留 error 级日志暴露。
 */
export const UI_HANDLED_COMMON_ERROR_CODES: readonly number[] = [
  ERROR_CODE.PERMISSION_DENIED,
  ERROR_CODE.RESOURCE_NOT_FOUND,
  ERROR_CODE.RATE_LIMITED,
]
