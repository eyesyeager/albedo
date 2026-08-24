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
 * 滚动策略：模型生成期间（思考过程 + 正文流式输出）**始终跟随底部**，
 * 确保用户实时看到思考与正文内容；用户向上阅读历史时显示"回到最新"，
 * 生成结束后尊重阅读位置、🔴 不强制拉回底部。
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

/**
 * 🔴 生成期间强制贴底：即使"回到最新"按钮被用户滚动顶上去，也不显示，
 * 因为流式输出会自动跟随到底部，按钮已无意义。
 */
const showJumpToLatest = computed(
  () => !nearBottom.value && !props.generating && props.messages.length > 0,
)

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

/**
 * 内容总长度：流式期间随分片增长，用它作为"是否需要跟随滚动"的触发源。
 *
 * 🔴 必须同时统计思考过程与正文：思考阶段（`delta.reasoning` 累积）时 `content` 尚未增长，
 * 只算 `content` 会让思考流式输出期间 watch 不触发、底部不跟随 —— 用户看不到实时思考。
 * 正文与 `segments[].text` 是同一内容的不同投影，取 `content`（权威）即可，避免重复计数。
 */
const contentLength = computed(() =>
  props.messages.reduce(
    (sum, message) =>
      sum +
      message.content.length +
      message.reasoning.length +
      message.segments.reduce((acc, segment) => acc + segment.reasoning.length, 0),
    0,
  ),
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
    // 生成期间始终跟随底部，实时呈现思考过程与正文；否则仅当用户已在底部时跟随
    if (!props.generating && !nearBottom.value) {
      return
    }
    await nextTick()
    scrollToBottom()
  },
)

// 生成开始（如用户从底部点发送）→ 主动贴底；生成结束 → 若用户仍在底部则归位
watch(
  () => props.generating,
  async () => {
    if (props.generating || nearBottom.value) {
      await nextTick()
      scrollToBottom()
    }
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
