/**
 * 会话列表 Store（分页 / 重命名 / 删除）。
 *
 * 规则：
 *   1. 只返回当前租户 + 当前用户 + 未删除会话（后端强制，前端不做补偿过滤）
 *   2. 分页排序稳定（updated_at DESC, id DESC），追加加载不重复不遗漏（AC-CON-003）
 *   3. 重命名走乐观锁；冲突（30020）必须提示重试，🔴 绝不静默覆盖（EX-023）
 *   4. 未登录时不发起任何请求（避免误触发 SSO 跳转）
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { conversationApi } from '@/api/conversation'
import { t } from '@/locales'
import type { Conversation } from '@/types/chat'
import { ApiError, isLoggedIn } from '@/utils/request'

import { useConfigStore } from './config'

export const useConversationStore = defineStore('conversation', () => {
  const configStore = useConfigStore()

  const items = ref<Conversation[]>([])
  const total = ref(0)
  const page = ref(1)
  const loading = ref(false)
  const loadingMore = ref(false)
  const loaded = ref(false)
  const errorMessage = ref<string | null>(null)

  const pageSize = computed(() => configStore.num('business', 'pageSizeDefault', 20))
  const hasMore = computed(() => items.value.length < total.value)

  async function loadFirstPage(): Promise<void> {
    if (!isLoggedIn()) {
      reset()
      return
    }
    loading.value = true
    errorMessage.value = null
    try {
      const data = await conversationApi.list({ page: 1, pageSize: pageSize.value })
      items.value = data.list
      total.value = data.total
      page.value = data.page
    } catch (e: unknown) {
      items.value = []
      total.value = 0
      errorMessage.value = describeError(e)
      console.error('[conversation] 列表加载失败', e)
    } finally {
      loading.value = false
      loaded.value = true
    }
  }

  async function loadMore(): Promise<void> {
    if (loadingMore.value || !hasMore.value || !isLoggedIn()) {
      return
    }
    loadingMore.value = true
    try {
      const next = page.value + 1
      const data = await conversationApi.list({ page: next, pageSize: pageSize.value })
      const existing = new Set(items.value.map((item) => item.conversationId))
      items.value = [...items.value, ...data.list.filter((item) => !existing.has(item.conversationId))]
      total.value = data.total
      page.value = data.page
    } catch (e: unknown) {
      errorMessage.value = describeError(e)
      console.error('[conversation] 追加加载失败', e)
    } finally {
      loadingMore.value = false
    }
  }

  /** 把最新的会话对象合并进列表（新建 / 标题更新后调用），并保持"最近更新在前"。 */
  function upsert(conversation: Conversation): void {
    const rest = items.value.filter((item) => item.conversationId !== conversation.conversationId)
    const isNew = rest.length === items.value.length
    items.value = [conversation, ...rest]
    if (isNew) {
      total.value += 1
    }
  }

  /** 从服务端同步单个会话（流式结束后拿到自动标题与最新 version）。 */
  async function syncOne(conversationId: string): Promise<Conversation | null> {
    if (!isLoggedIn()) {
      return null
    }
    try {
      const conversation = await conversationApi.detail(conversationId)
      upsert(conversation)
      return conversation
    } catch (e: unknown) {
      console.error('[conversation] 同步会话失败', e)
      return null
    }
  }

  async function rename(conversation: Conversation, title: string): Promise<Conversation> {
    const updated = await conversationApi.rename(
      conversation.conversationId,
      title,
      conversation.version,
    )
    items.value = items.value.map((item) =>
      item.conversationId === updated.conversationId ? updated : item,
    )
    return updated
  }

  async function remove(conversationId: string): Promise<void> {
    await conversationApi.remove(conversationId)
    items.value = items.value.filter((item) => item.conversationId !== conversationId)
    total.value = Math.max(0, total.value - 1)
  }

  function reset(): void {
    items.value = []
    total.value = 0
    page.value = 1
    loaded.value = false
    errorMessage.value = null
  }

  return {
    items,
    total,
    page,
    pageSize,
    loading,
    loadingMore,
    loaded,
    errorMessage,
    hasMore,
    loadFirstPage,
    loadMore,
    upsert,
    syncOne,
    rename,
    remove,
    reset,
  }
})

function describeError(e: unknown): string {
  return e instanceof ApiError ? e.message : t('chat.conversationLoadFailed')
}
