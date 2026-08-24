<template>
  <form class="composer" :aria-label="t('a11y.messageInput')" @submit.prevent="handleSubmit">
    <label class="sr-only" :for="inputId">{{ t('a11y.messageInput') }}</label>

    <div class="composer-shell" :class="{ 'composer-shell--disabled': inputDisabled }">
      <textarea
        :id="inputId"
        ref="textarea"
        v-model="draft"
        class="composer-input"
        rows="1"
        :placeholder="placeholder"
        :disabled="inputDisabled"
        :aria-describedby="describedBy"
        :aria-invalid="overLimit ? 'true' : undefined"
        @input="handleInput"
        @compositionstart="keys.onCompositionStart"
        @compositionend="keys.onCompositionEnd"
        @keydown="keys.onKeydown"
      />

      <div class="composer-actions">
        <AppButton
          v-if="generating"
          :id="COMPOSER_STOP_BUTTON_ID"
          variant="secondary"
          icon-only
          :aria-label="t('chat.stop')"
          @click="emit('stop')"
        >
          <template #icon><Square :size="16" aria-hidden="true" /></template>
        </AppButton>
        <AppButton
          v-else
          type="submit"
          variant="primary"
          icon-only
          :disabled="!canSubmit"
          :aria-label="t('chat.send')"
          :aria-describedby="describedBy"
        >
          <template #icon><ArrowUp :size="18" aria-hidden="true" /></template>
        </AppButton>
      </div>
    </div>

    <!--
      额度状态轨：已登录时承载「正常 / 偏低 / QPM 倒计时 / 日额度用尽」四态（design-system §15）。
      🔴 与 RateLimitNote 二选一，绝不同时出现两套限流皮肤；
      🔴 匿名（phase=hidden）时状态轨自身不挂载，此时仍保留 RateLimitNote 作为 10005 的兜底呈现。
    -->
    <QuotaStatusRail
      v-if="quotaMounted"
      :quota="quota"
      :rate-limit-remaining="rateLimitRemaining"
      @retry="emit('quotaRetry')"
    />
    <!-- 限流说明：内联在 note 区域，🔴 保留草稿、不弹窗、不自动重发（design-system §14.4） -->
    <RateLimitNote v-else :remaining-seconds="rateLimitRemaining" />

    <p :id="noteId" class="composer-note" :class="{ 'composer-note--danger': overLimit }">
      <template v-if="overLimit">
        <AlertCircle :size="14" aria-hidden="true" />
        {{ t('chat.inputTooLong', { max: maxChars }) }}
      </template>
      <template v-else-if="disabledReason.length > 0">{{ disabledReason }}</template>
      <span v-else-if="showCounter" class="composer-counter">
        {{ t('chat.charCount', { current: charCount, max: maxChars }) }}
      </span>
    </p>
  </form>
</template>

<script setup lang="ts">
/**
 * 消息输入区（design-system.md §6.3 / PRD UI-007）。
 *
 * 硬性要求：
 *   1. Enter 发送、Shift+Enter 换行、**输入法组词期间绝不误发**（见 useComposerKeys）
 *   2. 长度上限来自 `sys_config: chat.messageMaxChars`，🔴 不硬编码
 *   3. 发送 / 停止占用同一操作位，避免控件跳动
 *   4. 自动增长直接改高度，不做 height 动画（保持输入延迟最低）
 *   5. 未登录时不禁用输入：点击发送先存草稿再整页跳 SSO（AC-CON-001）
 *   6. M3.1：日额度用尽只禁用「发送按钮 + Enter 发送」，🔴 输入 / 选区 / 复制 / 草稿 / 停止生成全部保留
 */
import { AlertCircle, ArrowUp, Square } from 'lucide-vue-next'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import AppButton from '@/components/common/AppButton.vue'
import QuotaStatusRail from '@/components/chat/QuotaStatusRail.vue'
import RateLimitNote from '@/components/chat/RateLimitNote.vue'
import { useComposerKeys } from '@/composables/useComposerKeys'
import { t } from '@/locales'
import type { QuotaView } from '@/types/quota'
import { clearDraft, readDraft, saveDraft } from '@/utils/draft'
import {
  COMPOSER_STOP_BUTTON_ID,
  COUNTER_VISIBLE_RATIO,
  QUOTA_RAIL_ID,
} from '@/utils/uiConstants'

interface Props {
  /** 租户配置的输入引导语（site_config_versions） */
  placeholder: string
  /** 租户 ID：草稿只在原租户下恢复 */
  tenantId: string
  maxChars: number
  minChars: number
  generating: boolean
  /** 结构性禁用（无可用 Agent / 会话只读） */
  disabled: boolean
  /** 禁用原因（必须给出文字解释，不只禁用控件） */
  disabledReason?: string
  autofocus?: boolean
  /** 限流剩余等待秒数（来自服务端 retryAfterSeconds；>0 时禁用发送但保留草稿） */
  rateLimitRemaining?: number
  /** 每日额度视图（默认 hidden：匿名或未接入时不挂载状态轨） */
  quota?: QuotaView
  /**
   * 日额度已用尽（来自服务端权威快照）。
   * 🔴 只禁用发送按钮与 Enter 发送：输入、选区、复制、草稿与「停止生成」一律不受影响。
   */
  quotaExhausted?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  disabledReason: '',
  autofocus: false,
  rateLimitRemaining: 0,
  quota: () => ({ phase: 'hidden', snapshot: null }),
  quotaExhausted: false,
})
const emit = defineEmits<{ submit: [content: string]; stop: []; quotaRetry: [] }>()

const inputId = 'composer-input'
const noteId = 'composer-note'

const draft = ref('')
const textarea = ref<HTMLTextAreaElement | null>(null)

const keys = useComposerKeys({ onSubmit: () => handleSubmit() })

/** 按 Unicode 码点计数，与后端一致（AC-CHAT-004） */
const charCount = computed(() => [...draft.value.trim()].length)
const overLimit = computed(() => charCount.value > props.maxChars)
const inputDisabled = computed(() => props.disabled || props.generating)
const showCounter = computed(() => charCount.value >= props.maxChars * COUNTER_VISIBLE_RATIO)
/** 🔴 限流期间禁用发送，但输入框可用、草稿保留、停止按钮不受影响 */
const rateLimited = computed(() => props.rateLimitRemaining > 0)
const quotaMounted = computed(() => props.quota.phase !== 'hidden')
/**
 * 禁用原因的可访问关联（design-system §15.5）：
 * 🔴 用持久 inline 说明 + aria-describedby，绝不用 Tooltip / Toast（触屏不可发现、禁用元素 hover 不稳定）。
 */
const describedBy = computed(() =>
  props.quotaExhausted && quotaMounted.value ? `${noteId} ${QUOTA_RAIL_ID}` : noteId,
)
const canSubmit = computed(
  () =>
    !inputDisabled.value &&
    !rateLimited.value &&
    !props.quotaExhausted &&
    charCount.value >= props.minChars &&
    !overLimit.value,
)

function handleInput(): void {
  autosize()
  saveDraft(props.tenantId, draft.value)
}

function handleSubmit(): void {
  if (!canSubmit.value) {
    return
  }
  const content = draft.value.trim()
  emit('submit', content)
}

/** 发送成功后由父组件调用，清空输入与草稿 */
function clear(): void {
  draft.value = ''
  clearDraft(props.tenantId)
  void nextTick(autosize)
}

function focus(): void {
  textarea.value?.focus()
}

function autosize(): void {
  const el = textarea.value
  if (el === null) {
    return
  }
  // 先归零再按 scrollHeight 设置，保证缩小也能生效；最大高度由 CSS 控制并内部滚动
  el.style.height = 'auto'
  if (el.value.length === 0 && props.placeholder.length > 0) {
    // 空值时 scrollHeight 只反映内容高度（1 行），而租户占位文案在窄屏会换行，
    // 直接用它会把占位文案裁切掉。用占位文案临时探测所需高度后立即还原。
    el.value = props.placeholder
    const placeholderHeight = el.scrollHeight
    el.value = ''
    el.style.height = `${placeholderHeight}px`
    return
  }
  el.style.height = `${el.scrollHeight}px`
}

watch(
  () => props.tenantId,
  (tenantId) => {
    if (tenantId.length > 0 && draft.value.length === 0) {
      draft.value = readDraft(tenantId)
      void nextTick(autosize)
    }
  },
  { immediate: true },
)

/** 占位文案本身会变（切租户）或换行数会变（窗口宽度变化），都需要重新测高 */
watch(
  () => props.placeholder,
  () => {
    void nextTick(autosize)
  },
)

onMounted(() => {
  autosize()
  window.addEventListener('resize', autosize)
  if (props.autofocus && !inputDisabled.value) {
    focus()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', autosize)
})

defineExpose({ clear, focus })
</script>

<style scoped>
.composer {
  width: 100%;
  max-width: var(--layout-composer-max-width);
  margin: 0 auto;
}

.composer-shell {
  display: flex;
  align-items: flex-end;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-sm) var(--spacing-sm) var(--spacing-base);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background-color: var(--color-bg-elevated);
  box-shadow: var(--shadow-sm);
  transition: border-color var(--duration-fast) var(--ease-enter);
}

.composer-shell:focus-within {
  border-color: var(--color-border-strong);
}

.composer-shell--disabled {
  background-color: var(--color-fill-subtle);
  box-shadow: var(--shadow-none);
}

.composer-input {
  flex: 1 1 auto;
  min-width: 0;
  max-height: var(--composer-max-height);
  padding: var(--spacing-md) 0;
  border: none;
  background: transparent;
  color: var(--color-text-primary);
  font-family: var(--font-family-body);
  font-size: var(--font-size-body);
  line-height: var(--line-height-base);
  resize: none;
  overflow-y: auto;
}

.composer-input:focus {
  outline: none;
}

.composer-input::placeholder {
  color: var(--color-text-tertiary);
}

.composer-input:disabled {
  cursor: not-allowed;
}

.composer-actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  padding-bottom: var(--spacing-xs);
}

.composer-note {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  /* 🔴 移动端优先：默认 0 高度、不抢纵长空间；
     只有真正承载内容（错误 / 长度计数 / 禁用原因）时才增长。 */
  min-height: 0;
  margin: var(--spacing-xs) 0 0;
  color: var(--color-text-tertiary);
  font-size: var(--font-size-footnote);
}

.composer-note:empty {
  display: none;
}

.composer-note--danger {
  color: var(--color-danger);
}

.composer-counter {
  font-variant-numeric: tabular-nums;
}
</style>
