/**
 * 错误日志分级 —— 判定「该错误是否已有确定的 UI 呈现路径」的**唯一**入口。
 *
 * 为什么需要它：`catch` 里无差别 `console.error` 会把"产品已正确呈现的业务失败"
 * （如跨租户 10004 的资源不存在错误态、10005 的限流倒计时）误报成 error 级日志，
 * 污染控制台并让"关键页面 console 零 error"这条验收项失去信号价值（BUG-20260816-001）。
 *
 * 🔴 纪律：
 *   1. 判定只依赖 `types/api.ts` 的错误码登记表与码段常量，
 *      🔴 禁止在业务代码里散落 `10004 / 10005` 之类的裸码白名单
 *   2. 只降级"已被消费"的业务错误；网络失败、SSE 断流、响应结构非法、
 *      50000+ 系统异常、10001 参数违约一律仍走 `console.error`，🔴 绝不一刀切静音
 *   3. 本文件只回答"要不要报 error"，不决定 UI 表现 —— 展示逻辑仍归各调用方
 */
import {
  AUTH_ERROR_CODES,
  BUSINESS_ERROR_CODE_MAX,
  BUSINESS_ERROR_CODE_MIN,
  UI_HANDLED_COMMON_ERROR_CODES,
} from '@/types/api'
import { ApiError } from '@/utils/request'

/**
 * 该错误码是否属于「契约内、界面已消费」的业务失败。
 *
 * 归入的三类：
 *   - 通用段已消费码（10003 / 10004 / 10005）
 *   - 业务段 30000~39999（站点状态视图、版本冲突、Agent 不可用、工具类错误等均有确定呈现）
 *   - 鉴权失效 20000~20005（已由 request / streamRequest 统一清退并整页跳 SSO）
 */
export function isUiHandledErrorCode(code: number): boolean {
  return (
    UI_HANDLED_COMMON_ERROR_CODES.includes(code) ||
    AUTH_ERROR_CODES.includes(code) ||
    (code >= BUSINESS_ERROR_CODE_MIN && code <= BUSINESS_ERROR_CODE_MAX)
  )
}

/**
 * 该异常是否「已被界面正确消费」→ 可降级为 debug 级日志。
 *
 * 🔴 只有 `ApiError`（请求成功抵达且服务端按契约返回业务码）才可能为真；
 *    `NetworkError` / 解析失败 / 运行时异常恒为 false，保持 error 级不被吞掉。
 */
export function isUiHandledError(error: unknown): boolean {
  return error instanceof ApiError && isUiHandledErrorCode(error.code)
}
