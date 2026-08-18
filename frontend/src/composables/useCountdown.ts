/**
 * 倒计时（design-system.md §14.3.2 / §14.4）。
 *
 * 🔴 纪律：
 *   1. **以截止时间反推剩余值**，不用 `remaining--`：页面隐藏 / 后台节流 / 唤醒后必须自动校正
 *   2. 使用 `performance.now()` 单调时钟，避免系统时间被修改导致倒计时跳变
 *   3. 只驱动文本与 `transform: scaleX()`，🔴 不动画 width/height，不逐秒进 aria-live
 *   4. 总时长必须由调用方从 `sys_config` 取得，🔴 本文件不含任何默认秒数
 */
import { onScopeDispose, ref, type Ref } from 'vue'

import { COUNTDOWN_TICK_MS } from '@/utils/uiConstants'

export interface Countdown {
  /** 剩余整秒（向上取整），未启动时为 0 */
  remaining: Ref<number>
  /** 剩余比例 1 → 0，用于 `scaleX`；未启动时为 0 */
  ratio: Ref<number>
  /** 是否正在倒计时（剩余 > 0 且未暂停结束） */
  running: Ref<boolean>
  /** 启动 / 重启：totalSeconds ≤ 0 时视为「不可用」，不渲染倒计时 */
  start: (totalSeconds: number, deadlineAt?: number) => void
  /** 冻结：保留当前剩余值与进度（停止生成期间使用） */
  pause: () => void
  resume: () => void
  stop: () => void
  /** 供调用方持久化的截止时刻（`performance.now()` 基准）；未启动时为 null */
  deadlineAt: Ref<number | null>
}

export interface CountdownOptions {
  /** 归零回调：只用于禁用操作 + 等待服务端，🔴 绝不用于伪造终态 */
  onEnd?: () => void
}

export function useCountdown(options: CountdownOptions = {}): Countdown {
  const remaining = ref(0)
  const ratio = ref(0)
  const running = ref(false)
  const deadlineAt = ref<number | null>(null)

  let total = 0
  let timer: ReturnType<typeof setInterval> | null = null
  let ended = false

  function now(): number {
    return typeof performance !== 'undefined' ? performance.now() : Date.now()
  }

  function recalculate(): void {
    const deadline = deadlineAt.value
    if (deadline === null || total <= 0) {
      return
    }
    const leftMs = Math.max(0, deadline - now())
    remaining.value = Math.ceil(leftMs / COUNTDOWN_TICK_MS)
    ratio.value = Math.min(1, Math.max(0, leftMs / (total * COUNTDOWN_TICK_MS)))
    if (leftMs > 0) {
      return
    }
    running.value = false
    clearTimer()
    if (!ended) {
      ended = true
      options.onEnd?.()
    }
  }

  function clearTimer(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function start(totalSeconds: number, presetDeadline?: number): void {
    if (!Number.isFinite(totalSeconds) || totalSeconds <= 0) {
      stop()
      return
    }
    total = Math.floor(totalSeconds)
    deadlineAt.value = presetDeadline ?? now() + total * COUNTDOWN_TICK_MS
    ended = false
    running.value = true
    recalculate()
    clearTimer()
    if (running.value) {
      timer = setInterval(recalculate, COUNTDOWN_TICK_MS)
    }
  }

  function pause(): void {
    running.value = false
    clearTimer()
  }

  function resume(): void {
    if (deadlineAt.value === null || total <= 0) {
      return
    }
    running.value = true
    recalculate()
    clearTimer()
    if (running.value) {
      timer = setInterval(recalculate, COUNTDOWN_TICK_MS)
    }
  }

  function stop(): void {
    clearTimer()
    running.value = false
    remaining.value = 0
    ratio.value = 0
    deadlineAt.value = null
    total = 0
    ended = false
  }

  function handleVisibility(): void {
    if (document.visibilityState === 'visible' && running.value) {
      recalculate()
    }
  }

  if (typeof document !== 'undefined') {
    document.addEventListener('visibilitychange', handleVisibility)
  }

  onScopeDispose(() => {
    clearTimer()
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', handleVisibility)
    }
  })

  return { remaining, ratio, running, start, pause, resume, stop, deadlineAt }
}
