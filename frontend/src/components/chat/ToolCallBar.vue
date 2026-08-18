<template>
  <div class="tool-bar" :class="`tool-bar--${tone}`" role="group" :aria-label="t('chat.toolCallTitle')">
    <div class="tool-bar-row">
      <component
        :is="icon"
        class="tool-bar-icon"
        :class="[`tool-bar-icon--${tone}`, { 'tool-bar-icon--spin': spinning }]"
        :size="ICON_SIZE_INLINE"
        aria-hidden="true"
      />
      <span class="tool-bar-key">{{ call.toolKey }}</span>
      <!-- 🔴 状态不只靠颜色：图标 + 文字 + 状态值三者始终一致 -->
      <span
        :id="headingId"
        ref="heading"
        class="tool-bar-status"
        :class="`tool-bar-status--${tone}`"
        tabindex="-1"
        >{{ statusLabel }}</span
      >
      <span v-if="errorText.length > 0" class="tool-bar-error">{{ errorText }}</span>
      <!--
        无错误时内联当前阶段摘要：🔴 让用户**不展开也能看见工具做了什么**。
        原先只有展开才可见，成功态状态条几乎无信息量，容易被当成分隔线忽略。
      -->
      <span v-else-if="call.summary.length > 0" class="tool-bar-preview">{{ call.summary }}</span>

      <button
        v-if="sections.length > 0"
        type="button"
        class="tool-bar-toggle"
        :aria-expanded="expanded ? 'true' : 'false'"
        :aria-controls="panelId"
        :aria-label="t('a11y.toolCallSummaryToggle')"
        @click="expanded = !expanded"
      >
        <ChevronDown
          class="tool-bar-chevron"
          :class="{ 'tool-bar-chevron--open': expanded }"
          :size="ICON_SIZE_INLINE"
          aria-hidden="true"
        />
      </button>
    </div>

    <!-- 展开内容瞬态参与布局，仅内部淡入；🔴 不动画 height / max-height -->
    <div v-if="expanded && sections.length > 0" :id="panelId" class="tool-bar-panel">
      <p v-for="section in sections" :key="section.labelKey" class="tool-bar-section">
        <span class="tool-bar-section-label">{{ t(section.labelKey) }}</span>
        <span class="tool-bar-section-text">{{ section.text }}</span>
      </p>
      <!-- 🔴 只提示"被截断"，不显示阈值数字、不提供虚假的"查看全部" -->
      <p v-if="call.truncated" class="tool-bar-truncated">
        {{ t('chat.toolCall.summaryTruncated') }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 工具调用状态条（design-system.md §14.2）。
 *
 * 🔴 纪律：
 *   1. 8 种状态全覆盖，未知状态降级为中性视觉 + 通用文案（不崩、不显示原始枚举值）
 *   2. 状态文案由父级从 `sys_config: display.toolStatusLabels` 解析后传入，locales 仅兜底
 *   3. 摘要只显示后端脱敏值；`truncated` 只给提示，🔴 前端不截断、不展示字符阈值
 *   4. 展开控件是原生 `button`（44×44px）+ `aria-expanded` / `aria-controls`，禁止可点击 div
 */
import { ChevronDown } from 'lucide-vue-next'
import { computed, ref, type Component } from 'vue'

import { t } from '@/locales'
import type { ToolCallSummary } from '@/types/tool'
import { toolSummarySections } from '@/utils/toolCall'
import { toolErrorDescriptionKey } from '@/utils/toolErrors'
import { toolStatusIcon, toolStatusTone } from '@/utils/toolVisual'
import { ICON_SIZE_INLINE } from '@/utils/uiConstants'

interface Props {
  call: ToolCallSummary
  /** 已解析的状态文案（来源优先级：sys_config → locales 兜底） */
  statusLabel: string
}

const props = defineProps<Props>()

const expanded = ref(false)
const heading = ref<HTMLElement | null>(null)

const panelId = computed(() => `tool-panel-${props.call.toolCallId}`)
const headingId = computed(() => `tool-status-${props.call.toolCallId}`)

const icon = computed<Component>(() => toolStatusIcon(props.call.status))
const tone = computed(() => toolStatusTone(props.call.status))
const spinning = computed(() => props.call.status === 'running')

const sections = computed(() => toolSummarySections(props.call))

const errorText = computed(() => {
  const key = toolErrorDescriptionKey(props.call.status, props.call.errorCode)
  return key.length === 0 ? '' : t(key)
})

/**
 * 供父级在焦点即将丢失时调用（design-system §14.3.3）：
 * 确认卡收敛为状态条后，把焦点移到本条只读标题，避免焦点掉回 body。
 */
function focusHeading(): void {
  heading.value?.focus()
}

defineExpose({ focusHeading })
</script>

<style scoped>
.tool-bar {
  border: 1px solid var(--color-tool-border);
  /* 🔴 左侧 3px 强调边条：状态色在此承担"视觉锚点"，
     使工具节点在阅读面上可被一眼定位，而不是被误读为分隔线 */
  border-left-width: 3px;
  border-left-style: solid;
  border-radius: var(--radius-sm);
  background-color: var(--color-tool-surface);
  box-shadow: var(--shadow-none);
  font-size: var(--font-size-footnote);
  /* 新状态条只淡入，不位移（design-system §14.7） */
  animation: tool-bar-in var(--duration-fast) var(--ease-enter);
}

/* 强调边条颜色跟随状态语义（与图标 / 文字同源，不引入第二套色彩口径） */
.tool-bar--neutral {
  border-left-color: var(--color-tool-status-neutral);
}

.tool-bar--running {
  border-left-color: var(--color-tool-status-running);
}

.tool-bar--success {
  border-left-color: var(--color-tool-status-success);
}

.tool-bar--warning {
  border-left-color: var(--color-tool-status-warning);
}

.tool-bar--danger {
  border-left-color: var(--color-tool-status-danger);
}

.tool-bar-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-height: var(--control-touch-size);
  padding-left: var(--spacing-md);
  padding-right: var(--spacing-xs);
}

.tool-bar-icon {
  flex: 0 0 auto;
  width: var(--tool-icon-size);
  height: var(--tool-icon-size);
}

.tool-bar-icon--neutral,
.tool-bar-status--neutral {
  color: var(--color-tool-status-neutral);
}

.tool-bar-icon--running,
.tool-bar-status--running {
  color: var(--color-tool-status-running);
}

.tool-bar-icon--success,
.tool-bar-status--success {
  color: var(--color-tool-status-success);
}

.tool-bar-icon--warning,
.tool-bar-status--warning {
  color: var(--color-tool-status-warning);
}

.tool-bar-icon--danger,
.tool-bar-status--danger {
  color: var(--color-tool-status-danger);
}

.tool-bar-icon--spin {
  animation: tool-bar-spin var(--duration-second) linear infinite;
}

.tool-bar-key {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-primary);
  font-family: var(--font-family-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-bar-status {
  flex: 0 0 auto;
}

.tool-bar-error {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

/**
 * 内联摘要预览：单行截断，🔴 只展示后端已脱敏的 summary。
 * 前端不做二次截断计数，超出由 ellipsis 处理，完整内容仍在展开面板里。
 */
.tool-bar-preview {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-tertiary);
  font-family: var(--font-family-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-bar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: var(--control-touch-size);
  height: var(--control-touch-size);
  margin-left: auto;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.tool-bar-toggle:focus-visible {
  /* 焦点环由 styles/index.css 的全局 :focus-visible 统一提供（2px + 2px offset） */
  border-radius: var(--radius-sm);
}

.tool-bar-chevron {
  transition: transform var(--duration-fast) var(--ease-enter);
}

.tool-bar-chevron--open {
  transform: rotate(180deg);
}

.tool-bar-panel {
  padding: 0 var(--spacing-md) var(--spacing-sm);
  animation: tool-bar-in var(--duration-fast) var(--ease-enter);
}

.tool-bar-section {
  margin: 0 0 var(--spacing-xs);
}

.tool-bar-section-label {
  display: block;
  color: var(--color-text-tertiary);
}

.tool-bar-section-text {
  display: block;
  color: var(--color-text-secondary);
  font-family: var(--font-family-mono);
  line-height: var(--line-height-base);
  overflow-wrap: anywhere;
}

.tool-bar-truncated {
  margin: 0;
  color: var(--color-text-tertiary);
}

@keyframes tool-bar-in {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@keyframes tool-bar-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 🔴 Reduced Motion：≤100ms 交叉淡化，无位移 / 无旋转（design-system §14.7） */
@media (prefers-reduced-motion: reduce) {
  .tool-bar,
  .tool-bar-panel {
    animation-duration: var(--duration-reduced);
  }

  .tool-bar-icon--spin {
    animation: none;
  }

  .tool-bar-chevron {
    transition: none;
  }
}
</style>
