<template>
  <div v-if="visible" class="rate-note">
    <Clock class="rate-note-icon" :size="ICON_SIZE_INLINE" aria-hidden="true" />
    <span v-if="waiting" class="rate-note-text">{{
      t('errors.rateLimited.description', { remaining: remainingSeconds })
    }}</span>
    <span v-else class="rate-note-text">{{ t('errors.rateLimited.recovered') }}</span>
    <!-- 可视秒数持续更新，但 live region 只在“开始等待 / 已恢复”时改值，避免逐秒播报。 -->
    <span class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{
      announcement
    }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * 限流内联说明（design-system.md §14.4 / api-spec.md §7.12）。
 *
 * 🔴 纪律：
 *   1. 剩余秒数只来自服务端 `retryAfterSeconds`，🔴 前端不硬编码等待时长与限流阈值
 *   2. 表达"需要等待"，不用"系统故障"语气；🔴 不弹 Dialog / Toast 打断
 *   3. 倒计时结束只把说明交叉淡化为"可以继续发送"，短暂保留后移除；
 *      🔴 绝不自动发送、绝不自动重试
 */
import { Clock } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { t } from '@/locales'
import { ICON_SIZE_INLINE, RATE_LIMIT_RECOVERED_NOTE_MS } from '@/utils/uiConstants'

interface Props {
  /** 剩余等待秒数（0 表示未处于限流等待） */
  remainingSeconds: number
}

const props = defineProps<Props>()

const showRecovered = ref(false)
const announcement = ref(
  props.remainingSeconds > 0
    ? t('errors.rateLimited.description', { remaining: props.remainingSeconds })
    : '',
)
let timer: ReturnType<typeof setTimeout> | null = null

const waiting = computed(() => props.remainingSeconds > 0)
const visible = computed(() => waiting.value || showRecovered.value)

watch(
  () => props.remainingSeconds,
  (next, previous) => {
    if (next > 0 && previous <= 0) {
      clear()
      showRecovered.value = false
      announcement.value = t('errors.rateLimited.description', { remaining: next })
      return
    }
    if (next > 0 || previous <= 0) {
      return
    }
    // 等待刚刚结束：可视说明与 live region 各更新一次，短暂保留后移除。
    showRecovered.value = true
    announcement.value = t('errors.rateLimited.recovered')
    clear()
    timer = setTimeout(() => {
      showRecovered.value = false
      timer = null
    }, RATE_LIMIT_RECOVERED_NOTE_MS)
  },
)

function clear(): void {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
}

onBeforeUnmount(clear)
</script>

<style scoped>
.rate-note {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: var(--spacing-xs) 0 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-rate-limit-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-rate-limit-surface);
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  animation: rate-note-fade var(--duration-instant) var(--ease-enter);
}

.rate-note-icon {
  flex: 0 0 auto;
}

.rate-note-text {
  min-width: 0;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

@keyframes rate-note-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

/* 🔴 Reduced Motion：≤100ms 纯淡化，无位移（design-system §14.7） */
@media (prefers-reduced-motion: reduce) {
  .rate-note {
    animation-duration: var(--duration-reduced);
  }
}
</style>
