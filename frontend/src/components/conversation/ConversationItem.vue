<template>
  <li class="conversation-item" :class="{ 'conversation-item--active': active }">
    <template v-if="renaming">
      <input
        ref="renameInput"
        v-model="draftTitle"
        class="conversation-rename"
        :aria-label="t('chat.conversationRenameLabel')"
        :placeholder="t('chat.conversationRenamePlaceholder')"
        :maxlength="titleMaxChars"
        @keydown.enter.prevent="commitRename"
        @keydown.esc.prevent="cancelRename"
        @blur="cancelRename"
      />
    </template>

    <template v-else>
      <RouterLink class="conversation-link" :to="to" :aria-current="active ? 'page' : undefined">
        <span class="conversation-title">{{ title }}</span>
      </RouterLink>

      <ElDropdown trigger="click" placement="bottom-end" @command="handleCommand">
        <AppButton
          class="conversation-more"
          variant="ghost"
          icon-only
          :aria-label="t('a11y.conversationActions')"
        >
          <template #icon><MoreHorizontal :size="16" aria-hidden="true" /></template>
        </AppButton>
        <template #dropdown>
          <ElDropdownMenu>
            <ElDropdownItem command="rename">{{ t('common.rename') }}</ElDropdownItem>
            <ElDropdownItem command="delete" divided>{{ t('common.delete') }}</ElDropdownItem>
          </ElDropdownMenu>
        </template>
      </ElDropdown>
    </template>
  </li>
</template>

<script setup lang="ts">
/**
 * 会话列表项（design-system.md §6.9）。
 *
 * 行高 ≥44px；hover / focus-within 显示更多菜单（触屏始终可达）；
 * 重命名使用**原位输入**（Esc 取消、Enter 保存），删除由父组件做二次确认。
 */
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus'
import { MoreHorizontal } from 'lucide-vue-next'
import { computed, nextTick, ref } from 'vue'
import { RouterLink, type RouteLocationRaw } from 'vue-router'

import AppButton from '@/components/common/AppButton.vue'
import { t } from '@/locales'
import { useConfigStore } from '@/stores/config'
import type { Conversation } from '@/types/chat'

interface Props {
  conversation: Conversation
  active: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  rename: [conversation: Conversation, title: string]
  remove: [conversation: Conversation]
}>()

const configStore = useConfigStore()

const renaming = ref(false)
const draftTitle = ref('')
const renameInput = ref<HTMLInputElement | null>(null)

const titleMaxChars = computed(() => configStore.num('chat', 'titleMaxChars', 60))

const title = computed(() =>
  props.conversation.title.length > 0 ? props.conversation.title : t('chat.conversationTitleFallback'),
)

const to = computed<RouteLocationRaw>(() => ({
  name: 'chat-conversation',
  params: { conversationId: props.conversation.conversationId },
}))

function handleCommand(command: string | number | object): void {
  if (command === 'rename') {
    void startRename()
    return
  }
  if (command === 'delete') {
    emit('remove', props.conversation)
  }
}

async function startRename(): Promise<void> {
  draftTitle.value = props.conversation.title
  renaming.value = true
  await nextTick()
  renameInput.value?.focus()
  renameInput.value?.select()
}

function commitRename(): void {
  const next = draftTitle.value.trim()
  renaming.value = false
  if (next.length === 0 || next === props.conversation.title) {
    return
  }
  emit('rename', props.conversation, next)
}

function cancelRename(): void {
  renaming.value = false
}
</script>

<style scoped>
.conversation-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  min-height: var(--control-touch-size);
  padding-right: var(--spacing-xs);
  border-radius: var(--radius-md);
  transition: background-color var(--duration-fast) var(--ease-enter);
}

.conversation-item--active {
  background-color: var(--color-brand-subtle);
}

/* 当前会话除底色外附加左侧语义标识（不只靠颜色区分） */
.conversation-item--active::before {
  content: '';
  position: absolute;
  top: var(--spacing-sm);
  bottom: var(--spacing-sm);
  left: 0;
  width: 2px;
  border-radius: var(--radius-full);
  background-color: var(--color-brand);
}

@media (hover: hover) and (pointer: fine) {
  .conversation-item:hover {
    background-color: var(--color-fill-subtle);
  }

  .conversation-more {
    opacity: 0;
  }

  .conversation-item:hover .conversation-more,
  .conversation-item:focus-within .conversation-more {
    opacity: 1;
  }
}

.conversation-link {
  display: flex;
  align-items: center;
  flex: 1 1 auto;
  min-width: 0;
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-sm) 0 var(--spacing-md);
  color: var(--color-text-primary);
  text-decoration: none;
}

.conversation-title {
  min-width: 0;
  overflow: hidden;
  font-size: var(--font-size-callout);
  line-height: var(--line-height-base);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-rename {
  width: 100%;
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-md);
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-elevated);
  color: var(--color-text-primary);
  font-family: var(--font-family-body);
  font-size: var(--font-size-callout);
}
</style>
