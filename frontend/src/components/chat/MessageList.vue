<template>
  <div ref="scroller" class="message-list" @scroll.passive="handleScroll">
    <div class="message-list-inner">
      <SkeletonRows v-if="loading" :rows="4" />

      <StateBlock
        v-else-if="error !== null"
        :title="error"
        :action-text="t('common.retry')"
        :icon="AlertCircle"
        tone="danger"
        @action="emit('reload')"
      />

      <template v-else>
        <MessageItem
          v-for="(message, index) in messages"
          :key="message.clientId"
          :message="message"
          :cross-role="isCrossRole(index)"
          :read-only="readOnly"
          :rate-limit-remaining="rateLimitRemaining"
          @retry="emit('retry', $event)"
        />
      </template>

      <!--
        流式状态与工具语义共用**唯一** live region（🔴 不移动焦点、不逐秒播报）：
        倒计时逐秒变化、心跳、running 分片一律不进入（design-system §14.3.3）
      -->
      <p class="sr-only" role="status" aria-live="polite">{{ liveStatus }}</p>
    </div>

    <Transition name="jump">
      <AppButton
        v-if="showJumpToLatest"
        class="message-list-jump"
        variant="secondary"
        @click="scrollToBottom(true)"
      >
        <template #icon><ArrowDown :size="16" aria-hidden="true" /></template>
        {{ jumpLabel }}
      </AppButton>
    </Transition>
  </div>
</template>

<script setup lang="ts">
/**
 * 消息流（design-system.md §10.2）。
 *
 * 滚动策略：仅当用户"接近底部"时跟随新内容；用户向上阅读后显示"回到最新"，
 * 🔴 不强制拉回底部、🔴 流式更新不抢焦点。
 */
import { AlertCircle, ArrowDown } from 'lucide-vue-next'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import MessageItem from '@/components/chat/MessageItem.vue'
import AppButton from '@/components/common/AppButton.vue'
import SkeletonRows from '@/components/common/SkeletonRows.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import { liveAnnouncement } from '@/composables/useLiveAnnouncer'
import { t } from '@/locales'
import type { ChatMessageView } from '@/types/chat'
import { NEAR_BOTTOM_TOLERANCE_PX } from '@/utils/uiConstants'

interface Props {
  messages: ChatMessageView[]
  loading: boolean
  error: string | null
  generating: boolean
  readOnly: boolean
  /** 限流剩余秒数（透传给消息级错误块） */
  rateLimitRemaining?: number | null
}

const props = withDefaults(defineProps<Props>(), { rateLimitRemaining: null })
const emit = defineEmits<{ retry: [clientId: string]; reload: [] }>()

const scroller = ref<HTMLElement | null>(null)
const nearBottom = ref(true)

const showJumpToLatest = computed(() => !nearBottom.value && props.messages.length > 0)

/** 是否存在待确认的工具调用：用户不在底部时不强制滚动，只增强"回到最新"的语义 */
const hasPendingConfirm = computed(() =>
  props.messages.some((message) =>
    message.toolCalls.some((call) => call.status === 'awaiting_confirmation'),
  ),
)

const jumpLabel = computed(() =>
  hasPendingConfirm.value ? t('chat.toolConfirm.pendingBadge') : t('chat.backToLatest'),
)

/** 单一 live region 的内容：工具语义播报优先于通用生成状态 */
const liveStatus = computed(() =>
  liveAnnouncement.value.length > 0
    ? liveAnnouncement.value
    : props.generating
      ? t('chat.generating')
      : '',
)

/** 内容总长度：流式期间随分片增长，用它作为"是否需要跟随滚动"的触发源 */
const contentLength = computed(() =>
  props.messages.reduce((sum, message) => sum + message.content.length, 0),
)

function isCrossRole(index: number): boolean {
  if (index === 0) {
    return true
  }
  return props.messages[index - 1].role !== props.messages[index].role
}

function handleScroll(): void {
  const el = scroller.value
  if (el === null) {
    return
  }
  nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight <= NEAR_BOTTOM_TOLERANCE_PX
}

function scrollToBottom(smooth = false): void {
  const el = scroller.value
  if (el === null) {
    return
  }
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
  nearBottom.value = true
}

watch(
  () => [props.messages.length, contentLength.value],
  async () => {
    if (!nearBottom.value) {
      return
    }
    await nextTick()
    scrollToBottom()
  },
)

onMounted(() => {
  scrollToBottom()
})

defineExpose({ scrollToBottom })
</script>

<style scoped>
.message-list {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.message-list-inner {
  width: 100%;
  max-width: var(--layout-content-max-width);
  margin: 0 auto;
  padding: var(--spacing-xl) var(--spacing-base) var(--spacing-lg);
}

@media (min-width: 768px) {
  .message-list-inner {
    padding-left: var(--spacing-lg);
    padding-right: var(--spacing-lg);
  }
}

.message-list-jump {
  position: sticky;
  bottom: var(--spacing-base);
  left: 50%;
  transform: translateX(-50%);
  box-shadow: var(--shadow-md);
}

.jump-enter-active,
.jump-leave-active {
  transition: opacity var(--duration-fast) var(--ease-enter);
}

.jump-enter-from,
.jump-leave-to {
  opacity: 0;
}

/* 🔴 Reduced Motion：≤100ms 纯淡化（design-system §8.1 / §14.7） */
@media (prefers-reduced-motion: reduce) {
  .jump-enter-active,
  .jump-leave-active {
    transition-duration: var(--duration-reduced);
  }
}
</style>
