/**
 * 高风险工具确认 Store（契约：docs/api-spec.md §7.8、design-system.md §14.3）。
 *
 * 🔴 纪律：
 *   1. 倒计时总时长**优先取本帧 `confirmExpiresInSeconds`**（服务端按剩余生成预算收紧后的真实
 *      剩余秒数，api-spec §5.2 / ADR-017 ③ⓑ）；该字段缺失 / null 时才回退
 *      `sys_config: tool.confirmWaitSeconds`；两者都不可用则**不渲染倒计时**，
 *      🔴 绝不用 120 或任何替代秒数兜底
 *   2. 截止时刻按 `toolCallId` 记忆一次：同一调用重复收到 `awaiting_confirmation` 帧不重置剩余时间；
 *      停止失败恢复时也沿用原截止时刻（design-system §14.3.4）
 *   3. 本地倒计时到 0 只禁用操作 + 等待服务端，🔴 终态只以 SSE `timed_out` 为准
 *   4. `30055`（相反决定）→ 🔴 不重试、不弹 Toast，仅一次礼貌播报并按服务端最新帧收敛
 *   5. 确认接口 🔴 不携带 `Idempotency-Key`（§7.8.2）
 *   6. 🔴 `replayed=true` 且响应状态**已是终态** → 据实收敛（见 `syncReplayedTerminal`）；
 *      其余成功响应一律**继续等 SSE**，绝不乐观展示成功
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { toolApi } from '@/api/tool'
import { announceOnce } from '@/composables/useLiveAnnouncer'
import { t } from '@/locales'
import { ERROR_CODE } from '@/types/api'
import type {
  ToolCallStatusValue,
  ToolCallSummary,
  ToolConfirmDecision,
  ToolConfirmResult,
} from '@/types/tool'
import { isTerminalToolStatus } from '@/types/tool'
import { ApiError } from '@/utils/request'
import { resolveConfirmWaitSeconds } from '@/utils/toolCall'
import { COUNTDOWN_TICK_MS } from '@/utils/uiConstants'

import { useConfigStore } from './config'

/** 等待确认的状态值（唯一触发确认卡的状态）。 */
const AWAITING = 'awaiting_confirmation'

export const useToolConfirmStore = defineStore('toolConfirm', () => {
  const configStore = useConfigStore()

  /** 各调用的截止时刻（`performance.now()` 基准；仅在等待上限可用时存在） */
  const deadlines = ref<Record<string, number>>({})
  /**
   * 各调用**实际生效**的等待上限秒数（与 `deadlines` 同时登记、同时释放）。
   *
   * 🔴 存在理由（BUG-MCP-004）：确认卡的进度条比例 = 剩余 / **总时长**，
   * 若总时长仍取全局 120 而截止时刻按服务端 30s 计算，进度条会从 25% 起跳。
   * 因此"总时长"必须与"截止时刻"同源，二者只能一起算、一起存。
   */
  const waitSecondsByCall = ref<Record<string, number>>({})
  /** 提交中的决定（按钮 loading + 双按钮锁定） */
  const submitting = ref<Record<string, ToolConfirmDecision>>({})
  /** 已提交的决定（原位锁定，等待 SSE 收敛；🔴 不乐观展示成功） */
  const decided = ref<Record<string, ToolConfirmDecision>>({})
  /** 命中 `30055` 的调用：以服务端既有决定为准，不再允许操作 */
  const conflicted = ref<Record<string, boolean>>({})
  /**
   * 由 confirm 响应回放出的**服务端既有终态**（`toolCallId → status`）。
   *
   * 🔴 存在理由（BUG-MCP-003）：`replayed=true` 表示本次提交**没有改变**服务端状态
   * （api-spec §7.8.2 裁决④），若该状态已是终态，则生成线程早已收敛、SSE 也可能已断开，
   * 后续**不会**再有 `tool` 帧 —— 继续等 SSE 会让卡片永久停在"正在提交你的决定…"。
   * 🔴 这不是"乐观展示"：写入的状态直接来自服务端响应体，与刷新后看到的终态一致。
   */
  const syncedStatus = ref<Record<string, ToolCallStatusValue>>({})
  /** 用户已点击「停止生成」：冻结倒计时并禁用决策按钮 */
  const stopping = ref(false)

  /** 确认等待上限（秒）；0 表示未下发 → 不渲染倒计时 */
  const confirmWaitSeconds = computed(() => configStore.num('tool', 'confirmWaitSeconds', 0))

  /**
   * 消费一帧工具事件：登记截止时刻并做一次礼貌播报。
   * 🔴 只在首次进入 `awaiting_confirmation` 时播报，重复帧不重复播报、不重置倒计时。
   */
  function observe(call: ToolCallSummary): void {
    const id = call.toolCallId
    if (id.length === 0) {
      return
    }
    if (call.status === AWAITING) {
      if (deadlines.value[id] === undefined) {
        // 🔴 取值优先级收口在 resolveConfirmWaitSeconds：帧内实际剩余秒数 > 全局配置
        const seconds = resolveConfirmWaitSeconds(call, confirmWaitSeconds.value)
        if (seconds > 0) {
          waitSecondsByCall.value = { ...waitSecondsByCall.value, [id]: seconds }
          deadlines.value = {
            ...deadlines.value,
            [id]: nowMs() + seconds * COUNTDOWN_TICK_MS,
          }
        }
      }
      announceOnce(`toolConfirm:${id}`, t('chat.toolConfirm.announcement'))
      return
    }
    if (isTerminalToolStatus(call.status)) {
      // 终态后不再需要提交态（保留 decided 便于回溯本次会话内的用户决定）
      release(id)
    }
  }

  /** 提交决定；🔴 防重复提交，🔴 `30055` 不重试。 */
  async function submit(
    messageId: string,
    toolCallId: string,
    decision: ToolConfirmDecision,
  ): Promise<void> {
    if (messageId.length === 0 || toolCallId.length === 0) {
      return
    }
    if (isLocked(toolCallId)) {
      return
    }
    submitting.value = { ...submitting.value, [toolCallId]: decision }
    try {
      const result = await toolApi.confirm(messageId, toolCallId, { decision })
      decided.value = { ...decided.value, [toolCallId]: result.decision }
      // result.replayed=true 表示重复提交同一决定 → 🔴 静默回放，不提示用户
      syncReplayedTerminal(toolCallId, result)
    } catch (e: unknown) {
      handleSubmitError(toolCallId, e)
    } finally {
      const next = { ...submitting.value }
      delete next[toolCallId]
      submitting.value = next
    }
  }

  /**
   * 回放终态的据实收敛（BUG-MCP-003）。
   *
   * 判据必须**同时**满足，缺一不可：
   *   ① `replayed=true`：服务端状态未被本次提交改变，因此**不会**再唤醒生成线程、
   *      也不会再产生新的 `tool` 帧（典型场景：SSE 整体超时后行已被置 `cancelled`）；
   *   ② `status` 已是终态：终态不再迁移，据实展示与刷新结果一致。
   *
   * 🔴 `replayed=false`（首次决定）一律**不收敛**：那是刚刚推动状态机的成功提交，
   *    生成线程会被唤醒并继续下发 `tool` 帧，终态仍以 SSE 为准（design-system §14.3 纪律）。
   * 🔴 `replayed=true` 但状态非终态（如 `running`）也不收敛：流仍在推进。
   */
  function syncReplayedTerminal(toolCallId: string, result: ToolConfirmResult): void {
    // 🔴 防御式读取：旧后端 / 代理可能省略 status 字段，缺失即视为"无终态可同步"
    const status: ToolCallStatusValue = typeof result.status === 'string' ? result.status : ''
    if (result.replayed !== true || !isTerminalToolStatus(status)) {
      return
    }
    syncedStatus.value = { ...syncedStatus.value, [toolCallId]: status }
    // 终态已确定：释放倒计时与提交态（与 SSE 终态帧走同一条 release 路径）
    release(toolCallId)
  }

  function handleSubmitError(toolCallId: string, e: unknown): void {
    if (e instanceof ApiError && e.code === ERROR_CODE.TOOL_CONFIRM_CONFLICT) {
      // 🔴 与服务端既有决定相反：不重试、不弹窗，按最新 tool 帧原位刷新并礼貌播报一次
      conflicted.value = { ...conflicted.value, [toolCallId]: true }
      announceOnce(`toolConflict:${toolCallId}`, t('chat.toolConfirm.stateSynced'))
      console.error('[toolConfirm] 决定与服务端既有状态冲突，已按服务端状态收敛', toolCallId)
      return
    }
    // 其余错误（10004 无待确认 / 50003 审计失败等）：解锁按钮，等待 SSE 给出确定状态
    console.error('[toolConfirm] 提交确认决定失败', toolCallId, e)
  }

  /** 用户点击停止生成：冻结倒计时并禁用决策按钮（🔴 停止 ≠ 拒绝）。 */
  function markStopping(): void {
    stopping.value = true
  }

  function clearStopping(): void {
    stopping.value = false
  }

  function isLocked(toolCallId: string): boolean {
    return (
      submitting.value[toolCallId] !== undefined ||
      decided.value[toolCallId] !== undefined ||
      conflicted.value[toolCallId] === true ||
      stopping.value
    )
  }

  function deadlineOf(toolCallId: string): number | null {
    return deadlines.value[toolCallId] ?? null
  }

  /**
   * 该调用**实际生效**的倒计时总秒数（≤0 表示不渲染倒计时）。
   *
   * 🔴 未登记（历史回显 / 未经 `observe` 的卡片）时回退全局 `tool.confirmWaitSeconds`，
   * 与 `deadlineOf` 返回 `null` 配合，行为与本次修复前完全一致。
   */
  function waitSecondsOf(toolCallId: string): number {
    return waitSecondsByCall.value[toolCallId] ?? confirmWaitSeconds.value
  }

  function release(toolCallId: string): void {
    if (submitting.value[toolCallId] !== undefined) {
      const next = { ...submitting.value }
      delete next[toolCallId]
      submitting.value = next
    }
    if (deadlines.value[toolCallId] !== undefined) {
      const next = { ...deadlines.value }
      delete next[toolCallId]
      deadlines.value = next
    }
    if (waitSecondsByCall.value[toolCallId] !== undefined) {
      const next = { ...waitSecondsByCall.value }
      delete next[toolCallId]
      waitSecondsByCall.value = next
    }
  }

  /** 新一轮生成 / 切换会话时重置（避免跨消息串状态）。 */
  function reset(): void {
    deadlines.value = {}
    waitSecondsByCall.value = {}
    submitting.value = {}
    decided.value = {}
    conflicted.value = {}
    syncedStatus.value = {}
    stopping.value = false
  }

  return {
    submitting,
    decided,
    conflicted,
    syncedStatus,
    stopping,
    confirmWaitSeconds,
    observe,
    submit,
    markStopping,
    clearStopping,
    isLocked,
    deadlineOf,
    waitSecondsOf,
    reset,
  }
})

function nowMs(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now()
}
