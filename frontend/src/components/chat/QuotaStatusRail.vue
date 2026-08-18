<template>
  <div
    v-if="mounted"
    :id="QUOTA_RAIL_ID"
    class="quota-rail"
    role="group"
    :aria-label="t('chat.quota.railLabel')"
  >
    <!--
      🔴 稳定状态轨（design-system §15.2）：四套模板叠在同一个单格 Grid 内，
      非激活模板保留在文档流参与最大尺寸计算（visibility:hidden + opacity:0 + aria-hidden），
      切换只做原位 opacity 交叉 —— Composer 输入面与发送按钮的 bounding box 恒定不变。
    -->
    <div class="quota-rail-slot">
      <!-- ① 正常 / 偏低 / 仅剩一次 -->
      <div
        class="quota-panel quota-panel--count"
        :class="{ 'is-active': activePanel === 'count', 'is-last-one': state === 'lastOne' }"
        :aria-hidden="activePanel === 'count' ? undefined : 'true'"
      >
        <p class="quota-line quota-line--main" :class="{ 'is-warning': warningLevel }">
          <Gauge v-if="state === 'low'" :size="ICON_SIZE_INLINE" aria-hidden="true" />
          <TriangleAlert v-else-if="state === 'lastOne'" :size="ICON_SIZE_INLINE" aria-hidden="true" />
          <span class="quota-value">{{ countMainText }}</span>
        </p>
        <p class="quota-line quota-line--meta">
          <span class="quota-value">{{ metaText }}</span>
        </p>
      </div>

      <!-- ② QPM 限流中（10005）：秒级等待 + 自动恢复，🔴 不出现任何阈值 -->
      <div
        class="quota-panel quota-panel--rate"
        :class="{ 'is-active': activePanel === 'rate' }"
        :aria-hidden="activePanel === 'rate' ? undefined : 'true'"
      >
        <!-- 🔴 可见秒数容器整体 aria-hidden：避免每秒进入读屏（§15.7.1） -->
        <p class="quota-line quota-line--main is-warning" aria-hidden="true">
          <Clock3 :size="ICON_SIZE_INLINE" aria-hidden="true" />
          <span class="quota-value">{{ rateMainText }}</span>
        </p>
        <p class="quota-line quota-line--meta">
          <span class="quota-value">{{ rateMetaText }}</span>
        </p>
      </div>

      <!-- ③ 日额度用尽（30070）：中性持久阻断面 + 绝对重置时刻，🔴 不用红色、不倒计时 -->
      <div
        class="quota-panel quota-panel--exhausted"
        :class="{ 'is-active': activePanel === 'exhausted' }"
        :aria-hidden="activePanel === 'exhausted' ? undefined : 'true'"
      >
        <p class="quota-line quota-line--main is-blocked">
          <CalendarX2 :size="ICON_SIZE_INLINE" aria-hidden="true" />
          <span class="quota-value">{{ exhaustedMainText }}</span>
        </p>
        <p class="quota-line quota-line--meta">
          <span class="quota-value">{{ exhaustedMetaText }}</span>
        </p>
      </div>

      <!-- ④ 不限量 / 加载中 / 查询失败（契约已有的补充状态，§15.3.1） -->
      <div
        class="quota-panel quota-panel--notice"
        :class="{ 'is-active': activePanel === 'notice' }"
        :aria-hidden="activePanel === 'notice' ? undefined : 'true'"
      >
        <p class="quota-line quota-line--main">
          <CircleAlert v-if="state === 'unavailable'" :size="ICON_SIZE_INLINE" aria-hidden="true" />
          <span class="quota-value">{{ noticeText }}</span>
        </p>
        <p class="quota-line quota-line--meta">
          <button
            v-if="state === 'unavailable'"
            type="button"
            class="quota-retry"
            @click="emit('retry')"
          >
            {{ t('chat.quota.retry') }}
          </button>
        </p>
      </div>
    </div>

    <!--
      🔴 唯一播报节点（§15.7.1）：与可见倒计时解耦，polite + atomic。
      不使用 assertive（额度变化不该打断正在朗读的回答）；倒计时 tick 绝不进入本节点。
    -->
    <span class="sr-only" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * Composer 每日额度状态轨（design-system.md §15 / api-spec.md §7.15）。
 *
 * 🔴 纪律：
 *   1. 纯 props 组件：不访问 Store、不发请求 —— 数据与重取路径归 `stores/quota.ts`
 *   2. 🔴 匿名（phase=hidden）时整体不挂载：无请求、无占位、无骨架（AC-QUOTA-013）
 *   3. 🔴 四态判定优先级收口在 `utils/quotaSnapshot.resolveQuotaDisplayState`，
 *      本组件不做第二套判定；日额度用尽恒高于 QPM
 *   4. 🔴 重置时刻一律按响应中的 IANA `timezone` 渲染，禁止浏览器本地时区推算
 *   5. 🔴 状态切换只交叉 opacity：所有模板同尺寸、同 padding、同 1px 边框占位，
 *      Composer 布局不跳动；无位移 / 缩放 / 脉冲
 *   6. 🔴 QPM 与日额度文案语义严格区分："N 秒后可继续" vs "今日额度已用完，X 时重置"
 */
import { CalendarX2, CircleAlert, Clock3, Gauge, TriangleAlert } from 'lucide-vue-next'
import { computed, onBeforeUnmount, ref, watch } from 'vue'

import { useMediaQuery } from '@/composables/useMediaQuery'
import { t } from '@/locales'
import type { QuotaSnapshot, QuotaView } from '@/types/quota'
import { resolveQuotaDisplayState } from '@/utils/quotaSnapshot'
import { formatTenantClock, formatTenantDate, tenantDayRelation } from '@/utils/quotaTime'
import {
  ICON_SIZE_INLINE,
  QUOTA_RAIL_ID,
  RATE_LIMIT_RECOVERED_NOTE_MS,
  TABLET_MEDIA_QUERY,
} from '@/utils/uiConstants'

interface Props {
  /** 额度视图（阶段 + 权威快照） */
  quota: QuotaView
  /** QPM 剩余等待秒数（只来自服务端 `retryAfterSeconds`；0 表示未处于限流） */
  rateLimitRemaining?: number
}

const props = withDefaults(defineProps<Props>(), { rateLimitRemaining: 0 })
const emit = defineEmits<{ retry: [] }>()

/** 段落分隔符（纯排版符号，不承载语义，故不进 locales）。 */
const META_SEPARATOR = ' · '

const wide = useMediaQuery(TABLET_MEDIA_QUERY)
const showRecovered = ref(false)
/**
 * 播报文本。
 * 🔴 挂载即处于 QPM 等待（如切页面后回来）时先给出一次等待语义；
 * 额度数值的**初次加载**则不主动播报（§15.7.1）。
 */
const announcement = ref(
  props.rateLimitRemaining > 0
    ? t('chat.quota.a11yRateLimited', { remaining: props.rateLimitRemaining })
    : '',
)
let recoveredTimer: ReturnType<typeof setTimeout> | null = null

const state = computed(() => resolveQuotaDisplayState(props.quota, props.rateLimitRemaining))
const mounted = computed(() => state.value !== 'hidden')
const snapshot = computed<QuotaSnapshot | null>(() => props.quota.snapshot)
const warningLevel = computed(() => state.value === 'low' || state.value === 'lastOne')

/** 当前激活模板（🔴 恒只有一个，避免两块内容同时可见）。 */
const activePanel = computed<'count' | 'rate' | 'exhausted' | 'notice'>(() => {
  if (state.value === 'exhausted') {
    return 'exhausted'
  }
  if (state.value === 'rateLimited' || showRecovered.value) {
    return 'rate'
  }
  if (state.value === 'unlimited' || state.value === 'loading' || state.value === 'unavailable') {
    return 'notice'
  }
  return 'count'
})

// ===================== 文案组装（🔴 全部走 locales + 服务端数值） =====================

const remainingText = computed(() => {
  const remaining = snapshot.value?.remaining
  if (remaining === null || remaining === undefined) {
    return ''
  }
  return state.value === 'lastOne'
    ? t('chat.quota.lastOne')
    : t('chat.quota.remaining', { remaining })
})

const countMainText = computed(() => remainingText.value)

const usedTotalText = computed(() => {
  const current = snapshot.value
  if (current === null || current.limit === null) {
    return ''
  }
  return t('chat.quota.usedTotal', { used: current.used, limit: current.limit })
})

/**
 * 重置说明。
 * 🔴 `zone`：≥768px 显示真实 IANA 名称；375px 为避免挤占显示"租户时区"，
 *    但可访问描述（a11y*）恒使用真实 IANA 名称（§15.4.4）。
 */
function buildResetText(useZoneName: boolean): string {
  const current = snapshot.value
  if (current === null) {
    return ''
  }
  const time = formatTenantClock(current.resetsAt, current.timezone)
  const relation = tenantDayRelation(current.resetsAt, current.timezone)
  if (time === null || relation === null) {
    return '' // 时区非法：🔴 不猜测、不回落浏览器时区，整段省略
  }
  const day =
    relation === 'today'
      ? t('chat.quota.dayToday')
      : relation === 'tomorrow'
        ? t('chat.quota.dayTomorrow')
        : (formatTenantDate(current.resetsAt, current.timezone) ?? '')
  return t('chat.quota.resetAt', {
    day,
    time,
    zone: useZoneName ? current.timezone : t('chat.quota.tenantTimezone'),
  })
}

const resetText = computed(() => buildResetText(wide.value))
const resetTextA11y = computed(() => buildResetText(true))

const metaText = computed(() => join([usedTotalText.value, resetText.value]))

const rateMainText = computed(() =>
  props.rateLimitRemaining > 0
    ? t('chat.quota.rateLimited', { remaining: props.rateLimitRemaining })
    : t('chat.quota.rateLimitRecovered'),
)

/** QPM 期间仍展示当日额度副信息（🔴 但绝不展示每分钟阈值 —— 后端根本不下发）。 */
const rateMetaText = computed(() =>
  join([remainingText.value, usedTotalText.value, resetText.value]),
)

const exhaustedMainText = computed(() =>
  join([t('chat.quota.exhausted'), usedTotalText.value]),
)

const exhaustedMetaText = computed(() =>
  join([resetText.value, t('chat.quota.contactAdmin')]),
)

const noticeText = computed(() => {
  if (state.value === 'unlimited') {
    return t('chat.quota.unlimited')
  }
  return state.value === 'unavailable' ? t('chat.quota.unavailable') : t('chat.quota.loading')
})

function join(parts: readonly string[]): string {
  return parts.filter((part) => part.length > 0).join(META_SEPARATOR)
}

// ===================== 播报（🔴 同态去重，不逐秒、不抢焦点） =====================

/** 额度播报签名：只按 `status + remaining` 去重，🔴 不按 `asOf` 重复播报。 */
const quotaSignature = computed(() => {
  const current = snapshot.value
  return current === null ? '' : `${current.status}:${String(current.remaining)}`
})

watch(quotaSignature, (next, previous) => {
  // 初次加载成功不主动播报（§15.7.1）：组件可通过可访问名称与 aria-describedby 读取
  if (next.length === 0 || previous.length === 0 || next === previous) {
    return
  }
  announcement.value = describeQuota(previous)
})

watch(
  () => props.rateLimitRemaining,
  (next, previous) => {
    if (next > 0 && previous <= 0) {
      clearRecoveredTimer()
      showRecovered.value = false
      announcement.value = t('chat.quota.a11yRateLimited', { remaining: next })
      return
    }
    if (next > 0 || previous <= 0) {
      return
    }
    // 等待刚结束：展示一次"现在可以继续发送"后淡出，🔴 绝不自动重发
    announcement.value = t('chat.quota.a11yRateLimitRecovered')
    showRecovered.value = true
    clearRecoveredTimer()
    recoveredTimer = setTimeout(() => {
      showRecovered.value = false
      recoveredTimer = null
    }, RATE_LIMIT_RECOVERED_NOTE_MS)
  },
)

function describeQuota(previousSignature: string): string {
  const current = snapshot.value
  if (current === null) {
    return ''
  }
  const reset = resetTextA11y.value
  if (current.status === 'exhausted') {
    return t('chat.quota.a11yExhausted', {
      used: current.used,
      limit: String(current.limit ?? ''),
      reset,
    })
  }
  if (current.status === 'unlimited') {
    return t('chat.quota.unlimited')
  }
  // 从用尽恢复（管理员上调 / 跨过重置）→ 明确播报"已恢复"，发送按钮恢复但不自动聚焦
  if (previousSignature.startsWith('exhausted:')) {
    return t('chat.quota.restored', { remaining: String(current.remaining ?? '') })
  }
  return t('chat.quota.a11ySummary', {
    remaining: String(current.remaining ?? ''),
    used: current.used,
    limit: String(current.limit ?? ''),
    reset,
  })
}

function clearRecoveredTimer(): void {
  if (recoveredTimer !== null) {
    clearTimeout(recoveredTimer)
    recoveredTimer = null
  }
}

onBeforeUnmount(clearRecoveredTimer)
</script>

<style scoped>
.quota-rail {
  margin-top: var(--spacing-xs);
}

/* 单格 Grid：所有模板同一 grid-area，最大模板决定高度 */
.quota-rail-slot {
  display: grid;
  min-width: 0;
  /* 最坏情况两行文本预留（375px 规格），避免首次加载→就绪时轨道增高 */
  min-height: calc(var(--font-size-footnote) * var(--line-height-base) * 2);
}

.quota-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  grid-area: 1 / 1;
  min-width: 0;
  /* 🔴 所有状态同 padding + 同 1px 边框：几何恒定，切换只换颜色与 opacity */
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-footnote);
  transition:
    opacity var(--duration-instant) var(--ease-enter),
    background-color var(--duration-instant) var(--ease-enter),
    border-color var(--duration-instant) var(--ease-enter);
}

.quota-panel:not(.is-active) {
  visibility: hidden;
  opacity: 0;
  pointer-events: none;
}

/* 仅剩一次：warning 描边 + 极轻底色（不脉冲、不闪烁） */
.quota-panel--count.is-last-one.is-active {
  border-color: var(--color-warning);
  background-color: var(--color-bg-subtle);
}

/* QPM：限流专用中性底 + 描边（与"用尽"皮肤不同） */
.quota-panel--rate.is-active {
  border-color: var(--color-rate-limit-border);
  background-color: var(--color-rate-limit-surface);
}

/* 日额度用尽：🔴 中性阻断面，不用 danger、不用 brand */
.quota-panel--exhausted.is-active {
  border-color: var(--color-border-strong);
  background-color: var(--color-bg-subtle);
}

.quota-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 0;
  margin: 0;
}

.quota-line--main {
  color: var(--color-text-secondary);
  font-weight: var(--font-weight-medium);
}

.quota-line--main.is-warning {
  color: var(--color-warning);
}

.quota-line--main.is-blocked {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}

.quota-line--meta {
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}

.quota-value {
  min-width: 0;
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
}

.quota-retry {
  min-height: var(--control-compact-size);
  padding: 0 var(--spacing-sm);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg-elevated);
  color: var(--color-text-secondary);
  font-family: var(--font-family-body);
  font-size: var(--font-size-caption);
  cursor: pointer;
}

/* ≥768px：一行两区（左主状态、右已用/总量 + 重置），DOM 顺序仍先主后次 */
@media (min-width: 768px) {
  .quota-rail-slot {
    min-height: calc(var(--font-size-footnote) * var(--line-height-base));
  }

  .quota-panel {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--spacing-md);
  }
}

/* 🔴 Reduced Motion：直接切换（无位移、无缩放、无脉冲） */
@media (prefers-reduced-motion: reduce) {
  .quota-panel {
    transition-duration: var(--duration-reduced);
  }
}
</style>
