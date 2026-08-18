/**
 * 对话控制接口（契约：docs/api-spec.md §4.6.2）。
 *
 * 发送消息与重新生成走 SSE，见 `utils/streamRequest.ts`（禁止 EventSource）。
 */
import type { StopResult } from '@/types/chat'
import request from '@/utils/request'

export const chatApi = {
  /**
   * 停止生成：幂等，已是终态时不报错。
   * 🔴 前端 abort 之后**仍需**调用本接口，否则服务端不会把已生成内容持久化为 stopped。
   */
  stop: (messageId: string): Promise<StopResult> =>
    request.post<StopResult>(`/api/v1/messages/${encodeURIComponent(messageId)}/stop`),
}
