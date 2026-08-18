<template>
  <section class="reasoning" :aria-label="t('chat.reasoning.title')">
    <button
      type="button"
      class="reasoning-toggle"
      :aria-expanded="expanded ? 'true' : 'false'"
      :aria-controls="panelId"
      @click="toggle"
    >
      <component
        :is="streaming ? Loader2 : Brain"
        class="reasoning-icon"
        :class="{ 'reasoning-icon--spin': streaming }"
        :size="ICON_SIZE_INLINE"
        aria-hidden="true"
      />
      <!-- 🔴 状态不只靠图标：生成中/已完成各有独立文案 -->
      <span class="reasoning-label">{{ label }}</span>
      <!-- 折叠时也要能看出「过程里调了几次工具」，否则折叠等于信息消失 -->
      <span v-if="toolCount > 0" class="reasoning-meta">
        {{ t('chat.reasoning.toolCount', { count: toolCount }) }}
      </span>
      <ChevronDown
        class="reasoning-chevron"
        :class="{ 'reasoning-chevron--open': expanded }"
        :size="ICON_SIZE_INLINE"
        aria-hidden="true"
      />
    </button>

    <!-- 展开内容瞬态参与布局，仅内部淡入；🔴 不动画 height（design-system §14.7） -->
    <div v-if="expanded" :id="panelId" class="reasoning-panel">
      <!--
        🔴 思考文本与工具栏在面板**内部**按真实顺序交替。
        工具调用发生「在思考之中」，不是思考的分隔符 ——
        把它当分隔符会把一段连续推理切成两个面板，暗示模型中途停止了思考。
      -->
      <template v-for="item in items" :key="item.key">
        <p v-if="item.kind === 'reasoning'" class="reasoning-text">{{ item.text }}</p>
        <ToolCallTimeline
          v-else
          class="reasoning-tools"
          :message-id="messageId"
          :calls="item.calls"
        />
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
/**
 * 思考过程折叠面板（推理型模型的 `delta.reasoning`，api-spec §5.2）。
 *
 * 🔴 纪律：
 *   1. **生成中默认展开、思考结束后自动折叠**：过程值得实时可见，但结束后必须让位给答案。
 *      🔴 自动折叠**仅在用户未手动操作时生效** —— 一旦用户点过展开/折叠，
 *      就以用户意图为准，绝不再被自动逻辑推翻（否则用户读到一半会被强行合上）。
 *   2. **历史消息默认折叠**：加载历史会话时思考已是既成事实，展开会把回答挤出视口。
 *   3. 🔴 **思考文本必须纯文本渲染**：绝不走 Markdown ——
 *      思维链是模型的中间产物，可能含未闭合的代码围栏 / HTML 片段，
 *      交给 Markdown 渲染既会破版，也会把 XSS 净化面无谓地扩大到这条通道。
 *      （工具栏是结构化数据，走自己的组件，不受此限）
 *   4. 展开控件是原生 `button` + `aria-expanded` / `aria-controls`，禁止可点击 div
 *   5. 🔴 **工具栏嵌在面板内部**：见模板注释 —— 工具调用是思考的一部分，不是边界
 */
import { Brain, ChevronDown, Loader2 } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'

import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import { t } from '@/locales'
import type { MessageProcessItem } from '@/utils/messageTimeline'
import { ICON_SIZE_INLINE } from '@/utils/uiConstants'

interface Props {
  /** 过程节点（思考文本与工具调用按真实顺序交替） */
  items: MessageProcessItem[]
  /** 是否仍在生成（决定图标、文案与自动展开/折叠） */
  streaming: boolean
  /** 折叠面板的稳定 DOM id 来源 */
  messageKey: string
  /** 所属消息 ID，透传给工具栏用于确认交互 */
  messageId: string
}

const props = defineProps<Props>()

/** 生成中默认展开；历史消息（非流式）默认折叠 */
const expanded = ref(props.streaming)

/** 🔴 用户是否手动干预过 —— 一旦为 true，自动折叠逻辑永久让位 */
const userToggled = ref(false)

function toggle(): void {
  userToggled.value = true
  expanded.value = !expanded.value
}

watch(
  () => props.streaming,
  (streaming, previous) => {
    if (userToggled.value) {
      return
    }
    if (streaming) {
      // 同一组件实例被复用于新一轮生成时重新展开
      expanded.value = true
      return
    }
    if (previous === true) {
      // 思考结束 → 自动折叠，把视觉重心交还给答案正文
      expanded.value = false
    }
  },
)

const panelId = computed(() => `reasoning-panel-${props.messageKey}`)

const label = computed(() =>
  props.streaming ? t('chat.reasoning.streaming') : t('chat.reasoning.done'),
)

const toolCount = computed(() =>
  props.items.reduce((sum, item) => (item.kind === 'tools' ? sum + item.calls.length : sum), 0),
)
</script>

<style scoped>
.reasoning {
  margin-bottom: var(--spacing-sm);
}

.reasoning-toggle {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-sm) 0 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-footnote);
  cursor: pointer;
}

.reasoning-toggle:hover {
  color: var(--color-text-secondary);
}

.reasoning-icon {
  flex: 0 0 auto;
}

.reasoning-icon--spin {
  animation: reasoning-spin var(--duration-second) linear infinite;
}

.reasoning-label {
  white-space: nowrap;
}

.reasoning-meta {
  /* 🔴 不声明 color：继承 .reasoning-toggle，hover 时与主文案同步变化 */
  white-space: nowrap;
}

.reasoning-chevron {
  transition: transform var(--duration-fast) var(--ease-enter);
}

.reasoning-chevron--open {
  transform: rotate(180deg);
}

.reasoning-panel {
  padding: var(--spacing-sm) var(--spacing-md);
  border-left: 2px solid var(--color-tool-border);
  animation: reasoning-in var(--duration-fast) var(--ease-enter);
}

.reasoning-text {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  line-height: var(--line-height-base);
  /* 思维链常含换行与缩进，保留原始排版；超长 token 强制折行避免横向溢出 */
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 相邻思考段之间留白，避免多轮思考视觉上黏成一坨 */
.reasoning-text + .reasoning-text {
  margin-top: var(--spacing-sm);
}

/* 工具栏在思考流中上下留白，读者能看清"思考 → 查询 → 继续思考" */
.reasoning-tools {
  margin: var(--spacing-sm) 0;
}

@keyframes reasoning-in {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

@keyframes reasoning-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .reasoning-panel {
    animation-duration: var(--duration-reduced);
  }

  .reasoning-icon--spin {
    animation: none;
  }

  .reasoning-chevron {
    transition: none;
  }
}
</style>
