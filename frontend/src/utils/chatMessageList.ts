/**
 * 消息列表的可变操作（`stores/chat.ts` 的内部实现细节）。
 *
 * 抽出理由：`chat.ts` 同时承担"会话生命周期 + 流式编排 + 消息列表维护"，
 * 单文件已超 300 行。列表维护是纯函数式操作，可脱离 Pinia 独立测试与推理。
 *
 * 🔴 纪律：
 *   1. `patch` 按 `clientId` 定位，🔴 绝不按索引猜测目标
 *   2. `undefined` 视为"不修改"，避免可选字段被误清空
 *   3. 停止后的工具收敛只把**非终态**转 `cancelled`，🔴 不伪造超时 / 成功
 *   4. 单个工具的状态收敛只覆盖**非终态**行，🔴 绝不改写已到达的终态（不篡改历史）
 */
import type { ChatMessageView } from '@/types/chat'
import type { ToolCallStatusValue, ToolCallSummary } from '@/types/tool'
import { isTerminalToolStatus } from '@/types/tool'

export function findByClientId(
  messages: readonly ChatMessageView[],
  clientId: string,
): ChatMessageView | undefined {
  return messages.find((item) => item.clientId === clientId)
}

/**
 * 原位更新一条消息。
 *
 * @param changes `undefined` 值一律跳过（区别于 `null`：`null` 是有意义的"清空"）
 */
export function patchMessage(
  messages: readonly ChatMessageView[],
  clientId: string,
  changes: Partial<ChatMessageView>,
): void {
  const target = findByClientId(messages, clientId)
  if (target === undefined) {
    return
  }
  Object.entries(changes).forEach(([key, value]) => {
    if (value !== undefined) {
      Object.assign(target, { [key]: value })
    }
  })
}

/**
 * 重试前的状态复位（清空上一次生成的全部产物，回到排队态）。
 *
 * 🔴 必须连 `reasoning` / `toolCalls` / `segments` 一起清：
 * 重试是**重新生成**，这三者都是累加式写入（`reasoning +=`、工具按 id upsert、段落 push），
 * 不清空就会把上一次的思考与工具节点叠加到新结果上。
 */
export function retryPatch(): Partial<ChatMessageView> {
  return {
    content: '',
    reasoning: '',
    toolCalls: [],
    segments: [],
    status: 'queued',
    streaming: true,
    errorMessage: null,
    errorCode: null,
    finishReason: '',
  }
}

/**
 * 服务端已确认 stopped：把未终态的工具调用原位收敛为 `cancelled`。
 *
 * @returns 需要写回的工具列表；`null` 表示无需变更
 */
export function cancelPendingToolCalls(
  target: ChatMessageView | undefined,
): ChatMessageView['toolCalls'] | null {
  if (target === undefined || target.toolCalls.length === 0) {
    return null
  }
  return target.toolCalls.map((call) =>
    isTerminalToolStatus(call.status) ? call : { ...call, status: 'cancelled' },
  )
}

/** 单个工具调用的状态收敛结果（需要写回哪条消息的哪份列表）。 */
export interface ToolCallStatusPatch {
  clientId: string
  toolCalls: ToolCallSummary[]
}

/**
 * 按 `toolCallId` 原位收敛某个工具调用的状态（confirm 响应回放终态时的兜底路径）。
 *
 * 为什么按全列表查找：`toolCallId` 全局唯一（api-spec §5.2），
 * 而回放收敛发生在流之外，调用方手上没有 `clientId`。
 *
 * @returns 需要写回的消息与列表；`null` 表示无需变更（未找到 / 状态相同 / 本地已是终态）
 */
export function applyToolCallStatus(
  messages: readonly ChatMessageView[],
  toolCallId: string,
  status: ToolCallStatusValue,
): ToolCallStatusPatch | null {
  if (toolCallId.length === 0 || status.length === 0) {
    return null
  }
  for (const message of messages) {
    const index = message.toolCalls.findIndex((call) => call.toolCallId === toolCallId)
    if (index < 0) {
      continue
    }
    const current = message.toolCalls[index]
    // 🔴 已是终态（SSE 已给出确定结果）则以既有终态为准，不被回放值改写
    if (current.status === status || isTerminalToolStatus(current.status)) {
      return null
    }
    const toolCalls = [...message.toolCalls]
    // 🔴 只改 status：摘要 / errorCode 等字段 confirm 响应并不携带，绝不清空
    toolCalls[index] = { ...current, status }
    return { clientId: message.clientId, toolCalls }
  }
  return null
}
