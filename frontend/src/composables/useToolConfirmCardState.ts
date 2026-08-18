/**
 * 确认卡片的倒计时编排与派生展示态（design-system.md §14.3.2 / §14.7）。
 *
 * 从 `ToolConfirmCard.vue` 抽出的理由：卡片同时承担"结构 + 倒计时生命周期 +
 * 锁定态推导 + 一次性播报"四类职责，单文件已超 300 行。抽出后卡片只留结构与事件，
 * 时序逻辑可脱离 DOM 单独推理。
 *
 * 🔴 纪律（与卡片一致，抽出不得放宽）：
 *   1. 总时长只来自调用方传入的**已解析**等待上限（帧内 `confirmExpiresInSeconds` 优先，
 *      回退 `sys_config: tool.confirmWaitSeconds`），≤0 → 不启动、不渲染倒计时，
 *      🔴 绝不用 120 或任何替代秒数
 *   2. 本地归零只锁定操作，🔴 绝不伪造 `timed_out`（终态只认 SSE）
 *   3. 停止生成期间冻结（pause），恢复时按**原截止时刻**继续，不重置等待上限
 *   4. allow / deny / 冲突 → 立即取消倒计时
 *   5. "临近结束"提醒按总时长比例计算且只播报一次，🔴 不逐秒进 aria-live
 */
import { computed, onMounted, watch, type ComputedRef, type Ref } from 'vue'

import { useCountdown } from '@/composables/useCountdown'
import { announceOnce } from '@/composables/useLiveAnnouncer'
import { t } from '@/locales'
import type { ToolConfirmDecision } from '@/types/tool'
import { CONFIRM_EXPIRING_RATIO } from '@/utils/uiConstants'

/** 卡片传入的响应式来源（用 getter 形式，避免解构丢失响应性）。 */
export interface ConfirmCardStateSource {
  toolCallId: () => string
  /** 已解析的确认等待上限秒数（帧内实际剩余优先，回退 sys_config；≤0 表示不可用） */
  confirmWaitSeconds: () => number
  /** 已记忆的截止时刻（performance.now 基准） */
  deadlineAt: () => number | null
  submitting: () => ToolConfirmDecision | null
  decided: () => ToolConfirmDecision | null
  stopping: () => boolean
  conflicted: () => boolean
}

export interface ConfirmCardState {
  /** 剩余整秒（未启动为 0） */
  remainingSeconds: ComputedRef<number>
  /** 剩余比例 1 → 0，用于 scaleX */
  ratio: Ref<number>
  /** 是否渲染倒计时（🔴 未下发上限时恒为 false） */
  countdownVisible: ComputedRef<boolean>
  /** 本地已到期：只禁用操作 + 等待服务端 */
  expired: ComputedRef<boolean>
  allowDisabled: ComputedRef<boolean>
  denyDisabled: ComputedRef<boolean>
  /** 卡片底部说明（空串表示不渲染） */
  noteText: ComputedRef<string>
}

export function useToolConfirmCardState(source: ConfirmCardStateSource): ConfirmCardState {
  const countdown = useCountdown()

  const countdownVisible = computed(
    () => source.confirmWaitSeconds() > 0 && countdown.deadlineAt.value !== null,
  )

  const remainingSeconds = computed(() => countdown.remaining.value)

  const expired = computed(() => countdownVisible.value && countdown.remaining.value === 0)

  const locked = computed(
    () =>
      source.submitting() !== null ||
      source.decided() !== null ||
      source.conflicted() ||
      source.stopping() ||
      expired.value,
  )

  const allowDisabled = computed(() => locked.value && source.submitting() !== 'allow')
  const denyDisabled = computed(() => locked.value && source.submitting() !== 'deny')

  const noteText = computed(() => {
    if (source.stopping()) {
      return t('chat.toolConfirm.stopping')
    }
    if (source.conflicted()) {
      return t('errors.toolConfirmConflict.description')
    }
    if (source.submitting() !== null || source.decided() !== null) {
      return t('chat.toolConfirm.submitting')
    }
    if (expired.value) {
      return t('chat.toolConfirm.expiredSyncing')
    }
    return ''
  })

  onMounted(() => {
    if (source.confirmWaitSeconds() > 0) {
      countdown.start(source.confirmWaitSeconds(), source.deadlineAt() ?? undefined)
    }
  })

  // 停止生成期间冻结倒计时视觉；恢复时按原截止时刻继续（不重置完整等待上限）
  watch(source.stopping, (stopping) => {
    if (stopping) {
      countdown.pause()
      return
    }
    countdown.resume()
  })

  // 决定已提交 / 冲突后立即取消倒计时（design-system §14.7）
  watch(
    () => source.submitting() !== null || source.decided() !== null || source.conflicted(),
    (settled) => {
      if (settled) {
        countdown.pause()
      }
    },
  )

  // 临近结束只提醒一次，提醒点按总时长比例计算（🔴 不硬编码秒数）
  watch(
    () => countdown.ratio.value,
    (ratio) => {
      if (ratio > 0 && ratio <= CONFIRM_EXPIRING_RATIO) {
        announceOnce(`toolConfirmExpiring:${source.toolCallId()}`, t('chat.toolConfirm.expiring'))
      }
    },
  )

  return {
    remainingSeconds,
    ratio: countdown.ratio,
    countdownVisible,
    expired,
    allowDisabled,
    denyDisabled,
    noteText,
  }
}
