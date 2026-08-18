/**
 * 流式失败的落地形态（`stores/chat.ts` 的内部实现细节）。
 *
 * 🔴 纪律：
 *   1. 鉴权失效（20000~20005）已由 `streamRequest` 统一清退，🔴 此处不重复判定、不跳登录
 *   2. 业务码（10001 / 30000+ / 50000+）原样带上 `errorCode`，交展示层按 §14.5 分层
 *   3. 非 `ApiError`（网络 / 协议）用 locales 兜底文案，🔴 绝不把技术错误串给用户
 */
import { t } from '@/locales'
import type { ChatMessageView } from '@/types/chat'
import { ApiError } from '@/utils/request'

export function streamFailurePatch(error: unknown): Partial<ChatMessageView> {
  return {
    status: 'failed',
    streaming: false,
    errorMessage: error instanceof ApiError ? error.message : t('common.networkError'),
    errorCode: error instanceof ApiError ? error.code : null,
  }
}
