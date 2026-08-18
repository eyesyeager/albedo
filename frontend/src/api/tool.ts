/**
 * 高风险工具逐次确认接口（契约：docs/api-spec.md §7.8.2）。
 *
 * 🔴 硬约束：
 *   1. **不使用 `Idempotency-Key`**（§1.4 / §7.8.2：即便携带也被忽略）——
 *      幂等语义由服务端以 `tool_calls` 行锁 + 决定状态机保证
 *   2. 重复提交同一 decision → `code=0` 且 `replayed=true`（静默回放，不提示用户）
 *   3. 提交相反 decision → `30055`，🔴 前端不重试，按服务端最新 `tool` 帧刷新卡片
 */
import type { ToolConfirmPayload, ToolConfirmResult } from '@/types/tool'
import request from '@/utils/request'

export const toolApi = {
  confirm: (
    messageId: string,
    toolCallId: string,
    payload: ToolConfirmPayload,
  ): Promise<ToolConfirmResult> =>
    request.post<ToolConfirmResult>(
      `/api/v1/messages/${encodeURIComponent(messageId)}/tool-calls/${encodeURIComponent(toolCallId)}/confirm`,
      payload,
    ),
}
