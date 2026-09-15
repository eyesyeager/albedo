<template>
  <div v-if="calls.length > 0" class="timeline">
    <div v-for="group in groups" :key="group.round" class="timeline-round">
      <!-- 轮次标签仅在出现两轮及以上时显示一次；不画彩色时间线、不播 stagger -->
      <p v-if="showRoundLabel && group.round > 0" class="timeline-round-label">
        {{ t('chat.toolCall.round', { round: group.round }) }}
      </p>

      <ToolCallBar
        v-for="call in group.calls"
        :key="call.toolCallId"
        :call="call"
        :status-label="statusLabel(call)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具调用时间线（design-system.md §14.1）。
 *
 * 职责边界：本组件是**唯一**把 `sys_config` 接到展示层的地方，`ToolCallBar` 保持纯展示。
 *
 * 🔴 纪律：
 *   1. `toolCallId` 为稳定 key，状态更新原位进行（不重建消息节点、不改变 Tab 顺序）
 *   2. 状态文案取 `display.toolStatusLabels`，🔴 未下发时回退 locales（绝不硬编码）
 */
import { computed } from 'vue'

import ToolCallBar from '@/components/chat/ToolCallBar.vue'
import { t } from '@/locales'
import { useConfigStore } from '@/stores/config'
import { isToolCallStatus, type ToolCallSummary } from '@/types/tool'
import { groupToolCallsByRound, resolveConfigLabel, shouldShowRoundLabel } from '@/utils/toolCall'

interface Props {
  calls: ToolCallSummary[]
}

const props = defineProps<Props>()

const configStore = useConfigStore()

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
