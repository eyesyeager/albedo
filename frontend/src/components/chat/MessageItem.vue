<template>
  <article
    class="message"
    :class="[`message--${message.role}`, { 'message--cross': crossRole }]"
    :aria-label="roleLabel"
  >
    <div v-if="message.role === 'user'" class="message-bubble">{{ message.content }}</div>

    <div v-else class="message-assistant">
      <!--
        🔴 按真实时序渲染：思考（含其中的工具调用）→ 正文。
        绝不再按"全部思考 / 全部工具 / 全部正文"分块 —— 那会把工具节点固定堆到末尾，
        让用户以为模型"先想完所有事才调工具"，与实际因果相反。
        🔴 工具栏渲染在思考面板**内部**（见 buildMessageTimeline 的合并规则）：
        它是思考中途的一次外部查询，不构成思考的阶段边界。
      -->
      <template v-for="block in timeline" :key="block.key">
        <!-- 含思考文本 → 套折叠面板；只有工具（非推理模型）→ 裸渲染，不假造"思考过程"外壳 -->
        <ReasoningPanel
          v-if="block.kind === 'process' && block.hasReasoning"
          :items="block.items"
          :streaming="block.key === activeProcess"
          :message-key="`${message.clientId}-${block.key}`"
          :message-id="message.messageId"
        />
        <template v-else-if="block.kind === 'process'">
          <ToolCallTimeline
            v-for="item in block.items"
            :key="item.key"
            :calls="item.kind === 'tools' ? item.calls : []"
          />
        </template>
        <MarkdownContent v-else :source="block.text" class="message-md" />
      </template>

      <p v-if="showThinking" class="message-status">
        <Loader2 class="message-status-icon" :size="14" aria-hidden="true" />
        {{ t('chat.thinking') }}
      </p>

      <!-- M3 错误码分层：能在工具节点解释的错误不升级为消息级块（design-system §14.5） -->
      <MessageErrorBlock
        v-if="showErrorBlock"
        :code="message.errorCode"
        :message="message.errorMessage ?? ''"
        :has-tool-node="hasToolNode"
        :retry-after-seconds="rateLimitRemaining"
      />

      <p v-else-if="statusNote.length > 0" class="message-status" :class="{ 'message-status--danger': isFailed }">
        <component :is="isFailed ? AlertCircle : CircleSlash" :size="14" aria-hidden="true" />
        {{ statusNote }}
      </p>

      <div v-if="showActions" class="message-actions">
        <AppButton
          variant="ghost"
          icon-only
          :aria-label="t('a11y.copyMessage')"
          @click="handleCopy"
        >
          <template #icon>
            <Check v-if="copied" :size="16" aria-hidden="true" />
            <Copy v-else :size="16" aria-hidden="true" />
          </template>
        </AppButton>
        <AppButton
          v-if="canRegenerate"
          variant="ghost"
          :aria-label="regenerateLabel"
          @click="emit('retry', message.clientId)"
        >
          <template #icon><RefreshCw :size="16" aria-hidden="true" /></template>
          {{ regenerateLabel }}
        </AppButton>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
/**
 * 单条消息（design-system.md §6.6）：
 * 用户消息右对齐轻气泡；助手消息左对齐**无气泡**，直接落在阅读面上。
 * 状态（停止 / 失败）始终以图标 + 文字表达，不只靠颜色。
 */
import { AlertCircle, Check, CircleSlash, Copy, Loader2, RefreshCw } from 'lucide-vue-next'
import { computed } from 'vue'

import AppButton from '@/components/common/AppButton.vue'
import MarkdownContent from '@/components/chat/MarkdownContent.vue'
import MessageErrorBlock from '@/components/chat/MessageErrorBlock.vue'
import ReasoningPanel from '@/components/chat/ReasoningPanel.vue'
import ToolCallTimeline from '@/components/chat/ToolCallTimeline.vue'
import { useClipboard } from '@/composables/useClipboard'
import { t } from '@/locales'
import type { ChatMessageView } from '@/types/chat'
import { activeProcessKey, buildMessageTimeline } from '@/utils/messageTimeline'
import { errorDisplayLevel, messageErrorText } from '@/utils/toolErrors'

interface Props {
  message: ChatMessageView
  /** 与上一条消息角色不同 → 使用更大的组间距 */
  crossRole: boolean
  /** 会话是否只读（只读时不提供重新生成） */
  readOnly: boolean
  /** 限流剩余秒数（仅在错误码为 10005 时用于渲染等待语义） */
  rateLimitRemaining?: number | null
}

const props = withDefaults(defineProps<Props>(), { rateLimitRemaining: null })
const emit = defineEmits<{ retry: [clientId: string] }>()

const { copied, copy } = useClipboard()

const hasToolNode = computed(() => props.message.toolCalls.length > 0)

/** 顶层渲染块：过程块（思考 + 其中的工具）与正文块 */
const timeline = computed(() => buildMessageTimeline(props.message))

/**
 * 仍在进行中的那个过程块。
 *
 * 🔴 只有**最后一个**过程块才算"正在思考" —— 之前的阶段早已结束；
 * 若把 `message.streaming` 原样传给所有面板，已完成的阶段会一起保持展开并转圈。
 * 🔴 正文一旦开始到达，最后一个块变为 `text` → 无进行中面板 → 自动折叠让位给答案。
 */
const activeProcess = computed(() => activeProcessKey(timeline.value, props.message.streaming))

/** 只有"无法在工具节点解释"的错误才升级为消息级块 */
const errorText = computed(() => messageErrorText(props.message.errorCode, hasToolNode.value))

/**
 * 🔴 D-009：错误块必须真的有内容可展示，否则让位给 statusNote，
 * 避免"错误码未登记文案 + 服务端 message 为空"时整条消息静默无解释。
 */
const showErrorBlock = computed(
  () =>
    props.message.errorCode !== null &&
    errorDisplayLevel(props.message.errorCode, hasToolNode.value) === 'message' &&
    (errorText.value !== null || (props.message.errorMessage ?? '').length > 0),
)

const roleLabel = computed(() =>
  props.message.role === 'user' ? t('chat.roleUser') : t('chat.roleAssistant'),
)

const isFailed = computed(() => props.message.status === 'failed')
const isStopped = computed(() => props.message.status === 'stopped')

/**
 * 生成中且**尚无任何可见内容**时显示"正在生成"，避免空白容器。
 * 🔴 已有思考面板或工具节点时不再显示：那两者自带进行中状态，叠加会变成重复噪声。
 */
const showThinking = computed(
  () =>
    props.message.streaming &&
    props.message.content.length === 0 &&
    props.message.reasoning.length === 0 &&
    !hasToolNode.value,
)

const statusNote = computed(() => {
  if (isFailed.value) {
    return props.message.errorMessage ?? t('chat.failed')
  }
  if (isStopped.value) {
    return t('chat.stopped')
  }
  return props.message.errorMessage ?? ''
})

const showActions = computed(() => !props.message.streaming && props.message.role === 'assistant')

const canRegenerate = computed(() => !props.readOnly)

const regenerateLabel = computed(() =>
  isFailed.value ? t('common.retry') : t('chat.regenerate'),
)

async function handleCopy(): Promise<void> {
  await copy(props.message.content)
}
</script>

<style scoped>
.message {
  display: flex;
  margin-top: var(--message-gap-same-role);
}

.message--cross {
  margin-top: var(--message-gap-cross-role);
}

.message--user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: var(--bubble-user-max-width);
  padding: var(--spacing-md) var(--spacing-base);
  border-radius: var(--radius-lg);
  background-color: var(--color-bubble-user-bg);
  color: var(--color-bubble-user-text);
  font-size: var(--font-size-body);
  line-height: var(--line-height-base);
  white-space: pre-wrap;
  overflow-wrap: break-word;
}

.message-assistant {
  width: 100%;
  min-width: 0;
}

/* 流式文本只做淡入，不做逐字位移（design-system §8.2） */
.message-md {
  animation: message-fade var(--duration-instant) var(--ease-enter);
}

@keyframes message-fade {
  from {
    opacity: 0;
  }

  to {
    opacity: 1;
  }
}

.message-status {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: var(--spacing-sm) 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
}

.message-status--danger {
  color: var(--color-danger);
}

.message-status-icon {
  animation: message-spin var(--duration-second) linear infinite;
}

@keyframes message-spin {
  to {
    transform: rotate(360deg);
  }
}

.message-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-sm);
  opacity: 1;
  transition: opacity var(--duration-fast) var(--ease-enter);
}

/* 精细指针设备：操作区默认淡出，hover / 键盘聚焦时显现（不产生布局位移） */
@media (hover: hover) and (pointer: fine) {
  .message-actions {
    opacity: 0;
  }

  .message-assistant:hover .message-actions,
  .message-assistant:focus-within .message-actions {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .message-md {
    animation: none;
  }

  .message-status-icon {
    animation: none;
  }
}
</style>
