/**
 * 限流状态 Store（契约：docs/api-spec.md §7.12、design-system.md §14.4）。
 *
 * 🔴 纪律：
 *   1. 剩余秒数**只**来自 HTTP `data.retryAfterSeconds` 或 SSE `error.retryAfterSeconds`，
 *      🔴 前端不得硬编码等待秒数，也不得硬编码限流阈值
 *   2. 倒计时结束只恢复"可发送"，🔴 绝不自动发送、绝不自动重试
 *   3. 表达为"需要等待"，不是"系统故障"；🔴 不弹窗打断（展示层由 Composer 内联说明承担）
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { useCountdown } from '@/composables/useCountdown'
import { ERROR_CODE } from '@/types/api'
import { ApiError } from '@/utils/request'
import { readRetryAfterSeconds } from '@/utils/toolErrors'

export const useRateLimitStore = defineStore('rateLimit', () => {
  const countdown = useCountdown()
  /** 本轮等待是否已结束（用于展示一次"已恢复"说明后自动移除） */
  const recovered = ref(false)

  /** 剩余等待秒数（0 表示未处于限流等待） */
  const remainingSeconds = computed(() => countdown.remaining.value)
  /** 是否处于限流等待（此时禁用发送，但保留草稿、不影响停止生成） */
  const waiting = computed(() => countdown.remaining.value > 0)

  /**
   * 按服务端给出的剩余秒数开始等待。
   *
   * @param retryAfterSeconds 服务端下发值；null / ≤0 时不启动（🔴 不猜测等待时长）
   */
  function start(retryAfterSeconds: number | null): void {
    if (retryAfterSeconds === null || retryAfterSeconds <= 0) {
      return
    }
    recovered.value = false
    countdown.start(retryAfterSeconds)
  }

  /**
   * 从异常对象中识别限流并启动等待。
   *
   * @returns 是否命中 `10005`
   */
  function startFromError(error: unknown): boolean {
    if (!(error instanceof ApiError) || error.code !== ERROR_CODE.RATE_LIMITED) {
      return false
    }
    start(readRetryAfterSeconds(error.data))
    return true
  }

  function markRecovered(): void {
    recovered.value = true
  }

  function reset(): void {
    countdown.stop()
    recovered.value = false
  }

  return { remainingSeconds, waiting, recovered, start, startFromError, markRecovered, reset }
})
