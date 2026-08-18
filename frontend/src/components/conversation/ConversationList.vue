<template>
  <nav class="conversation-list" :aria-label="t('a11y.sidebarNav')">
    <SkeletonRows v-if="store.loading" :rows="5" />

    <StateBlock
      v-else-if="store.errorMessage !== null"
      :title="store.errorMessage"
      :action-text="t('common.retry')"
      :icon="AlertCircle"
      tone="danger"
      @action="reload"
    />

    <StateBlock
      v-else-if="store.items.length === 0"
      :title="emptyTitle"
      :description="t('chat.conversationEmptyHint')"
      :icon="MessagesSquare"
    />

    <template v-else>
      <ul class="conversation-items">
        <ConversationItem
          v-for="item in store.items"
          :key="item.conversationId"
          :conversation="item"
          :active="item.conversationId === activeId"
          @rename="handleRename"
          @remove="handleRemove"
        />
      </ul>

      <AppButton
        v-if="store.hasMore"
        class="conversation-more-btn"
        variant="ghost"
        block
        :loading="store.loadingMore"
        @click="store.loadMore()"
      >
        {{ t('common.loadMore') }}
      </AppButton>
    </template>
  </nav>
</template>

<script setup lang="ts">
/**
 * 会话历史列表：加载骨架 / 空态 / 失败重试三态齐备（design-system.md §6.10）。
 *
 * 空态引导文案优先使用租户配置 `emptySessionText`（🔴 禁止硬编码），缺失回退 locales。
 * 分页追加不播放整列 stagger（design-system §6.9）。
 */
import { ElMessage, ElMessageBox } from 'element-plus'
import { AlertCircle, MessagesSquare } from 'lucide-vue-next'
import { computed } from 'vue'

import AppButton from '@/components/common/AppButton.vue'
import SkeletonRows from '@/components/common/SkeletonRows.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import ConversationItem from '@/components/conversation/ConversationItem.vue'
import { t } from '@/locales'
import { useConversationStore } from '@/stores/conversation'
import { useSiteStore } from '@/stores/site'
import { ERROR_CODE } from '@/types/api'
import type { Conversation } from '@/types/chat'
import { ApiError } from '@/utils/request'

interface Props {
  /** 当前打开的会话 ID（用于高亮） */
  activeId: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<{ removed: [conversationId: string] }>()

const store = useConversationStore()
const siteStore = useSiteStore()

const emptyTitle = computed(() => {
  const configured = siteStore.config?.emptySessionText ?? ''
  return configured.length > 0 ? configured : t('chat.conversationEmpty')
})

function reload(): void {
  void store.loadFirstPage()
}

async function handleRename(conversation: Conversation, title: string): Promise<void> {
  try {
    await store.rename(conversation, title)
    ElMessage.success(t('chat.conversationRenamed'))
  } catch (e: unknown) {
    // 30020：多标签页并发重命名冲突，必须提示重试，🔴 绝不静默覆盖（EX-023）
    const conflict = e instanceof ApiError && e.code === ERROR_CODE.VERSION_CONFLICT
    ElMessage.error(conflict ? t('chat.versionConflict') : describe(e))
    if (conflict) {
      reload()
    }
  }
}

async function handleRemove(conversation: Conversation): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('chat.conversationDeleteConfirm'),
    t('chat.conversationDeleteTitle'),
    {
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
      type: 'warning',
      confirmButtonClass: 'el-button--danger',
    },
  ).then(
    () => true,
    () => false,
  )
  if (!confirmed) {
    return
  }
  try {
    await store.remove(conversation.conversationId)
    ElMessage.success(t('chat.conversationDeleted'))
    emit('removed', conversation.conversationId)
  } catch (e: unknown) {
    ElMessage.error(describe(e))
  }
}

function describe(e: unknown): string {
  return e instanceof ApiError ? e.message : t('common.submitFailed')
}

/** 供父组件在登录态变化后刷新 */
defineExpose({ reload, activeId: props.activeId })
</script>

<style scoped>
.conversation-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  padding: 0 var(--spacing-sm);
}

.conversation-items {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin: 0;
  padding: 0;
  list-style: none;
}

.conversation-more-btn {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-footnote);
}
</style>
