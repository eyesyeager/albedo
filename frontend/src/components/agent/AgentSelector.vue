<template>
  <div class="agent-selector">
    <ElSelect
      v-if="agentStore.available"
      :model-value="selectedId"
      class="agent-select"
      :aria-label="t('a11y.agentSelector')"
      :teleported="true"
      popper-class="agent-popper"
      @update:model-value="handleSelect"
    >
      <template #prefix>
        <Bot :size="16" aria-hidden="true" />
      </template>
      <ElOption
        v-for="agent in agentStore.agents"
        :key="agent.agentId"
        :value="agent.agentId"
        :label="agent.name"
        class="agent-option"
      >
        <span class="agent-option-name">
          {{ agent.name }}
          <Check v-if="agent.agentId === selectedId" class="agent-option-check" :size="14" aria-hidden="true" />
        </span>
        <span v-if="agent.description.length > 0" class="agent-option-desc">{{ agent.description }}</span>
      </ElOption>
    </ElSelect>

    <SkeletonRows v-else-if="agentStore.loading" class="agent-skeleton" :rows="1" />

    <!-- 无可用 Agent：禁用并给出**租户配置**的不可用语义（EX-010 / 30030） -->
    <p v-else class="agent-unavailable">
      <AlertTriangle :size="14" aria-hidden="true" />
      <span class="agent-unavailable-text">{{ unavailableText }}</span>
    </p>
  </div>
</template>

<script setup lang="ts">
/**
 * Agent 选择器（design-system.md §6.4）。
 *
 * 只展示已发布且启用的 Agent（后端过滤 + 默认项置顶）；带描述副行；
 * 当前项使用 check 图标 + brand-subtle 底（不只靠颜色）。
 * 无可用 Agent 时不渲染下拉，直接展示租户配置的不可用文案并由父组件禁用输入。
 */
import { ElOption, ElSelect } from 'element-plus'
import { AlertTriangle, Bot, Check } from 'lucide-vue-next'
import { computed } from 'vue'

import SkeletonRows from '@/components/common/SkeletonRows.vue'
import { t } from '@/locales'
import { useAgentStore } from '@/stores/agent'
import { useSiteStore } from '@/stores/site'

const agentStore = useAgentStore()
const siteStore = useSiteStore()

const selectedId = computed(() => agentStore.selected?.agentId ?? '')

/** 不可用语义优先用租户配置文案，缺失时回退静态文案 */
const unavailableText = computed(() => {
  const configured = siteStore.config?.agentUnavailableText ?? ''
  return configured.length > 0 ? configured : t('chat.agentUnavailable')
})

function handleSelect(value: string | number | boolean | undefined): void {
  if (typeof value === 'string' && value.length > 0) {
    agentStore.select(value)
  }
}
</script>

<style scoped>
.agent-selector {
  display: flex;
  align-items: center;
  /* 触发器最大 280px（design-system §6.4）；窄屏收缩但不塌陷 */
  width: var(--layout-sidebar-width);
  max-width: 100%;
  min-width: 0;
}

.agent-select {
  width: 100%;
}

.agent-select :deep(.el-select__wrapper) {
  min-height: var(--control-touch-size);
  border-radius: var(--radius-md);
  box-shadow: none;
  background-color: transparent;
}

.agent-select :deep(.el-select__wrapper:hover) {
  background-color: var(--color-fill-subtle);
}

.agent-select :deep(.el-select__selected-item) {
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
}

.agent-skeleton {
  width: var(--layout-sidebar-width);
  max-width: 100%;
}

.agent-unavailable {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 0;
  margin: 0;
  color: var(--color-warning);
  font-size: var(--font-size-footnote);
}

.agent-unavailable-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-option-name {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  color: var(--color-text-primary);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
  line-height: var(--line-height-base);
}

.agent-option-check {
  color: var(--color-brand);
}

.agent-option-desc {
  display: block;
  max-width: var(--dropdown-max-width);
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  line-height: var(--line-height-base);
  text-overflow: ellipsis;
}
</style>

<!--
  下拉浮层被 teleport 到 body，选项容器的高度/内边距必须在全局作用域覆盖。
  只作用于本组件的 popper-class，且全部取自 Token（不写死尺寸/色值）。
-->
<style>
.agent-popper {
  max-width: var(--dropdown-max-width);
}

.agent-popper .el-select-dropdown__item {
  height: auto;
  min-height: var(--control-touch-size);
  padding: var(--spacing-sm) var(--spacing-md);
  line-height: var(--line-height-base);
}

.agent-popper .el-select-dropdown__item.is-selected {
  background-color: var(--color-brand-subtle);
}
</style>
