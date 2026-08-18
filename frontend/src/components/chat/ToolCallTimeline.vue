<template>
  <div v-if="calls.length > 0" class="timeline">
    <div v-for="group in groups" :key="group.round" class="timeline-round">
      <!-- 轮次标签仅在出现两轮及以上时显示一次；不画彩色时间线、不播 stagger -->
      <p v-if="showRoundLabel && group.round > 0" class="timeline-round-label">
        {{ t('chat.toolCall.round', { round: group.round }) }}
      </p>

      <template v-for="call in group.calls" :key="call.toolCallId">
        <ToolConfirmCard
          v-if="call.status === AWAITING"
          :ref="(el) => setCardRef(call.toolCallId, el)"
          :call="call"
          :risk-label="riskLabel(call)"
          :confirm-wait-seconds="confirmStore.waitSecondsOf(call.toolCallId)"
          :deadline-at="confirmStore.deadlineOf(call.toolCallId)"
          :submitting="confirmStore.submitting[call.toolCallId] ?? null"
          :decided="confirmStore.decided[call.toolCallId] ?? null"
          :stopping="confirmStore.stopping"
          :conflicted="confirmStore.conflicted[call.toolCallId] === true"
          @decide="handleDecide(call.toolCallId, $event)"
        />
        <ToolCallBar
          v-else
          :ref="(el) => setBarRef(call.toolCallId, el)"
          :call="call"
          :status-label="statusLabel(call)"
        />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具调用时间线（design-system.md §14.1）。
 *
 * 职责边界：本组件是**唯一**把 `sys_config` 与确认 Store 接到展示层的地方，
 * `ToolCallBar` / `ToolConfirmCard` 保持纯展示，便于单测直接挂载。
 *
 * 🔴 纪律：
 *   1. `toolCallId` 为稳定 key，状态更新原位进行（不重建消息节点、不改变 Tab 顺序）
 *   2. 状态文案取 `display.toolStatusLabels`，风险文案取 `display.toolRiskLabels`，
 *      🔴 未下发时状态回退 locales、风险直接不渲染（绝不硬编码风险文案）
 *   3. 确认卡收敛为状态条时，仅在焦点即将丢失的情况下做焦点修复（§14.3.3）
 *   4. 🔴 确认倒计时总时长取 `confirmStore.waitSecondsOf(toolCallId)`（帧内实际剩余秒数优先，
 *      回退全局 `tool.confirmWaitSeconds`），**不得**直接传全局值 —— 否则倒计时会显示
 *      一个被生成预算否决的假上限（api-spec §5.2 `confirmExpiresInSeconds`）
 */
import { computed, nextTick, ref, watch } from 'vue'

import ToolCallBar from '@/components/chat/ToolCallBar.vue'
import ToolConfirmCard from '@/components/chat/ToolConfirmCard.vue'
import { t } from '@/locales'
import { useConfigStore } from '@/stores/config'
import { useToolConfirmStore } from '@/stores/toolConfirm'
import { isToolCallStatus, type ToolCallSummary, type ToolConfirmDecision } from '@/types/tool'
import { groupToolCallsByRound, resolveConfigLabel, shouldShowRoundLabel } from '@/utils/toolCall'

interface Props {
  /** 承载这些工具调用的 assistant 消息 ID（确认接口路径参数，取自 SSE meta.messageId） */
  messageId: string
  calls: ToolCallSummary[]
}

const props = defineProps<Props>()

const AWAITING = 'awaiting_confirmation'

const configStore = useConfigStore()
const confirmStore = useToolConfirmStore()

interface BarExposed {
  focusHeading: () => void
}
interface CardExposed {
  containsFocus: () => boolean
}

const barRefs = ref(new Map<string, BarExposed>())
const cardRefs = ref(new Map<string, CardExposed>())
let awaitingIds: readonly string[] = []

const groups = computed(() => groupToolCallsByRound(props.calls))
const showRoundLabel = computed(() => shouldShowRoundLabel(groups.value))

function statusLabel(call: ToolCallSummary): string {
  const configured = resolveConfigLabel(
    configStore.raw('display', 'toolStatusLabels'),
    call.status,
  )
  if (configured.length > 0) {
    return configured
  }
  // 🔴 未知状态不显示原始枚举值，改用通用文案（向后兼容，§5.4.1）
  return isToolCallStatus(call.status)
    ? t(`chat.toolStatus.${call.status}`)
    : t('chat.toolStatus.unknown')
}

function riskLabel(call: ToolCallSummary): string {
  return resolveConfigLabel(configStore.raw('display', 'toolRiskLabels'), call.riskLevel)
}

function handleDecide(toolCallId: string, decision: ToolConfirmDecision): void {
  void confirmStore.submit(props.messageId, toolCallId, decision)
}

function setBarRef(id: string, el: unknown): void {
  if (el === null || el === undefined) {
    barRefs.value.delete(id)
    return
  }
  barRefs.value.set(id, el as BarExposed)
}

function setCardRef(id: string, el: unknown): void {
  if (el === null || el === undefined) {
    cardRefs.value.delete(id)
    return
  }
  cardRefs.value.set(id, el as CardExposed)
}

/**
 * 焦点修复：确认卡被状态条替换时，若焦点还停留在即将卸载的按钮上，
 * 把焦点移到同一调用的只读状态标题；🔴 焦点不在卡内时绝不移动焦点。
 * flush:'pre' 保证在 DOM 更新前完成"焦点是否在卡内"的判定。
 */
watch(
  () => props.calls.map((call) => `${call.toolCallId}:${call.status}`).join('|'),
  () => {
    const nextAwaiting = props.calls
      .filter((call) => call.status === AWAITING)
      .map((call) => call.toolCallId)
    const leaving = awaitingIds.filter((id) => !nextAwaiting.includes(id))
    const needsFocus = leaving.filter((id) => cardRefs.value.get(id)?.containsFocus() === true)
    awaitingIds = nextAwaiting
    if (needsFocus.length === 0) {
      return
    }
    void nextTick(() => {
      needsFocus.forEach((id) => barRefs.value.get(id)?.focusHeading())
    })
  },
  { flush: 'pre', immediate: true },
)
</script>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  /* 跨轮间距（design-system §14.1） */
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-sm);
}

.timeline-round {
  display: flex;
  flex-direction: column;
  /* 同轮状态条间距 */
  gap: var(--spacing-xs);
}

.timeline-round-label {
  margin: 0;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-caption);
}
</style>
