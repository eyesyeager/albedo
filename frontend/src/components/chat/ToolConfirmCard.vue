<template>
  <section
    ref="root"
    class="confirm"
    role="group"
    :aria-labelledby="titleId"
    :aria-describedby="descriptionId"
    @keydown.esc="handleEscape"
  >
    <!--
      🔴 单根元素：注释必须放在 section 内部。放在 <template> 根级会让组件在 dev 下
      变成 Fragment 根（注释也是节点），导致 role / aria-* 等根属性无法被外部读取，
      且 dev 与 prod（构建期剥离注释）行为不一致 —— 已由单测守护。
      🔴 内联在消息时间线内的确认卡：不是 Dialog / Popover / Toast，移动端也不吸底。
      🔴 role="group"（非 alertdialog）+ 出现时绝不移动焦点。
    -->
    <p :id="titleId" class="confirm-title">
      <ShieldAlert class="confirm-title-icon" :size="ICON_SIZE_INLINE" aria-hidden="true" />
      <span class="confirm-title-text">{{ t('chat.toolConfirm.title') }}</span>
      <!-- 风险文案取 sys_config: display.toolRiskLabels；🔴 未下发则不显示，绝不硬编码"高风险" -->
      <span v-if="riskLabel.length > 0" class="confirm-risk" :class="riskClass">{{ riskLabel }}</span>
    </p>

    <p class="confirm-key">{{ call.toolKey }}</p>

    <div :id="descriptionId" class="confirm-args">
      <span class="confirm-args-label">{{ t('chat.toolConfirm.argsLabel') }}</span>
      <!-- 只展示服务端已脱敏、已截断的摘要；🔴 无"查看原始参数"入口 -->
      <span class="confirm-args-text">{{ call.argsSummary }}</span>
    </div>

    <!-- 倒计时视觉收口在 ToolConfirmCountdown：轨道 + scaleX + 每秒文本，🔴 不进 aria-live -->
    <ToolConfirmCountdown
      v-if="countdownVisible"
      :remaining-seconds="remainingSeconds"
      :ratio="ratio"
    />

    <!-- 🔴 安全优先：DOM 与视觉均"拒绝在前、允许在后"，不设默认按钮 -->
    <div class="confirm-actions">
      <AppButton
        variant="secondary"
        :disabled="denyDisabled"
        :loading="submitting === 'deny'"
        @click="emit('decide', 'deny')"
      >
        {{ t('chat.toolConfirm.deny') }}
      </AppButton>
      <AppButton
        variant="primary"
        :disabled="allowDisabled"
        :loading="submitting === 'allow'"
        @click="emit('decide', 'allow')"
      >
        {{ t('chat.toolConfirm.allow') }}
      </AppButton>
    </div>

    <p v-if="noteText.length > 0" class="confirm-note">{{ noteText }}</p>
  </section>
</template>

<script setup lang="ts">
/**
 * 高风险工具确认卡片（design-system.md §14.3 / api-spec.md §7.8）。
 *
 * 🔴 不可退让的行为：
 *   1. 出现时**不抢焦点**：不 autofocus、不滚动、不移动读屏焦点；播报由消息流的单一 polite
 *      live region 承担（见 `useLiveAnnouncer`），本组件只在"临近结束"追加一次提醒
 *   2. 倒计时总时长只来自服务端：本帧 `confirmExpiresInSeconds` 优先，其次
 *      `sys_config: tool.confirmWaitSeconds`；两者都不可用 → 不渲染倒计时，
 *      🔴 绝不用 120 或任何替代秒数
 *   3. 本地归零只禁用操作并显示"正在同步"，🔴 终态只以 SSE `timed_out` 为准
 *   4. Esc 不代表拒绝、不提交决定：仅把焦点移到"停止生成"按钮
 *   5. 提交后两个按钮原位锁定，🔴 不乐观展示成功
 */
import { ShieldAlert } from 'lucide-vue-next'
import { computed, ref } from 'vue'

import AppButton from '@/components/common/AppButton.vue'
import ToolConfirmCountdown from '@/components/chat/ToolConfirmCountdown.vue'
import { useToolConfirmCardState } from '@/composables/useToolConfirmCardState'
import { t } from '@/locales'
import type { ToolCallSummary, ToolConfirmDecision } from '@/types/tool'
import { COMPOSER_STOP_BUTTON_ID, ICON_SIZE_INLINE } from '@/utils/uiConstants'

interface Props {
  call: ToolCallSummary
  /** 风险等级展示文案（来自 sys_config；空串表示未下发 → 不渲染标签） */
  riskLabel: string
  /**
   * 倒计时总秒数 = 本次确认**实际生效**的等待上限（由父级 `waitSecondsOf` 解析：
   * 帧内 `confirmExpiresInSeconds` 优先，回退 `sys_config: tool.confirmWaitSeconds`）；
   * ≤0 表示不可用 → 不渲染倒计时。
   */
  confirmWaitSeconds: number
  /** 已记忆的截止时刻（performance.now 基准）：重复帧 / 停止失败恢复时不重置剩余时间 */
  deadlineAt: number | null
  /** 正在提交中的决定 */
  submitting: ToolConfirmDecision | null
  /** 已提交并等待服务端收敛的决定 */
  decided: ToolConfirmDecision | null
  /** 用户点击了"停止生成"：冻结倒计时 + 禁用决策 */
  stopping: boolean
  /** 命中 30055：以服务端既有决定为准，原位刷新且不重试 */
  conflicted: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{ decide: [decision: ToolConfirmDecision] }>()

const root = ref<HTMLElement | null>(null)

const titleId = computed(() => `tool-confirm-title-${props.call.toolCallId}`)
const descriptionId = computed(() => `tool-confirm-desc-${props.call.toolCallId}`)

const riskClass = computed(() =>
  props.call.riskLevel.length > 0 ? `confirm-risk--${props.call.riskLevel}` : '',
)

/** 倒计时生命周期与锁定态推导收口在 composable（见其头部纪律说明）。 */
const { remainingSeconds, ratio, countdownVisible, allowDisabled, denyDisabled, noteText } =
  useToolConfirmCardState({
    toolCallId: () => props.call.toolCallId,
    confirmWaitSeconds: () => props.confirmWaitSeconds,
    deadlineAt: () => props.deadlineAt,
    submitting: () => props.submitting,
    decided: () => props.decided,
    stopping: () => props.stopping,
    conflicted: () => props.conflicted,
  })

/** Esc：🔴 不提交任何决定，只把焦点交给"停止生成"（用户主动按键，允许移动焦点）。 */
function handleEscape(): void {
  document.getElementById(COMPOSER_STOP_BUTTON_ID)?.focus()
}

defineExpose({
  /** 供父级判断焦点是否仍在卡内（状态收敛时的焦点修复） */
  containsFocus: (): boolean =>
    root.value !== null && document.activeElement !== null && root.value.contains(document.activeElement),
})
</script>

<style scoped>
.confirm {
  padding: var(--spacing-base);
  border: 1px solid var(--color-tool-confirm-border);
  border-radius: var(--radius-lg);
  background-color: var(--color-tool-confirm-surface);
  box-shadow: var(--shadow-none);
  font-size: var(--font-size-footnote);
  /* 入场：轻微上移 + 淡入（design-system §14.7） */
  animation: confirm-in var(--duration-normal) var(--ease-enter);
}

.confirm-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin: 0 0 var(--spacing-sm);
  color: var(--color-text-primary);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
}

.confirm-title-icon {
  flex: 0 0 auto;
  color: var(--color-tool-status-warning);
}

.confirm-title-text {
  min-width: 0;
}

.confirm-risk {
  padding: 0 var(--spacing-sm);
  border-radius: var(--radius-xs);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
}

.confirm-risk--low {
  background-color: var(--color-tool-risk-low-bg);
  color: var(--color-tool-risk-low);
}

.confirm-risk--medium {
  background-color: var(--color-tool-risk-medium-bg);
  color: var(--color-tool-risk-medium);
}

.confirm-risk--high {
  background-color: var(--color-tool-risk-high-bg);
  color: var(--color-tool-risk-high);
}

.confirm-key {
  margin: 0 0 var(--spacing-sm);
  overflow: hidden;
  color: var(--color-text-secondary);
  font-family: var(--font-family-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.confirm-args {
  margin-bottom: var(--spacing-md);
}

.confirm-args-label {
  display: block;
  color: var(--color-text-tertiary);
}

.confirm-args-text {
  display: block;
  color: var(--color-text-primary);
  font-family: var(--font-family-mono);
  line-height: var(--line-height-base);
  overflow-wrap: anywhere;
}

.confirm-actions {
  display: grid;
  /*
   * 375px 下双列等宽；可用宽度不足或 200% 缩放时 auto-fit 自动降为单列，
   * 每项仍 ≥44px（design-system §14.3.5）。
   */
  grid-template-columns: repeat(auto-fit, minmax(calc(var(--control-touch-size) * 3), 1fr));
  gap: var(--spacing-sm);
}

.confirm-note {
  margin: var(--spacing-sm) 0 0;
  color: var(--color-text-secondary);
  animation: confirm-fade var(--duration-instant) var(--ease-enter);
}

@media (min-width: 768px) {
  .confirm-actions {
    grid-template-columns: auto auto;
    justify-content: flex-end;
  }
}

@keyframes confirm-in {
  from {
    opacity: 0;
    transform: translateY(var(--spacing-xs));
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes confirm-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .confirm {
    animation: confirm-fade var(--duration-reduced) var(--ease-enter);
  }
}
</style>
