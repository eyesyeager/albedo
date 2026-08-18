/**
 * Agent Store（M1 只读）。
 *
 * 规则：
 *   1. 只消费已发布且启用的 Agent（后端已过滤并按 isDefault DESC, sortOrder ASC 排序）
 *   2. 空列表 → 禁用输入并展示租户配置的 `agentUnavailableText`（EX-010 / 30030）
 *   3. 选择结果只影响**新建会话**；已有会话绑定的版本永不静默切换（RISK-005）
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { agentApi } from '@/api/agent'
import { t } from '@/locales'
import type { Agent } from '@/types/agent'
import { ERROR_CODE } from '@/types/api'
import { ApiError } from '@/utils/request'

export const useAgentStore = defineStore('agent', () => {
  const agents = ref<Agent[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const errorMessage = ref<string | null>(null)
  const selectedAgentId = ref<string | null>(null)

  /** 是否有可用 Agent（决定输入区是否可用） */
  const available = computed(() => agents.value.length > 0)

  /** 默认 Agent：后端已把 isDefault 置顶，取首项即默认项 */
  const defaultAgent = computed<Agent | null>(() => agents.value[0] ?? null)

  const selected = computed<Agent | null>(() => {
    const id = selectedAgentId.value
    if (id !== null) {
      const hit = agents.value.find((item) => item.agentId === id)
      if (hit !== undefined) {
        return hit
      }
    }
    return defaultAgent.value
  })

  async function load(): Promise<void> {
    loading.value = true
    errorMessage.value = null
    try {
      const page = await agentApi.list()
      agents.value = page.list
    } catch (e: unknown) {
      agents.value = []
      // 30030 表示"当前租户无可用 Agent"，属于确定业务语义而非加载失败
      errorMessage.value =
        e instanceof ApiError && e.code === ERROR_CODE.AGENT_UNAVAILABLE
          ? null
          : describeError(e)
      console.error('[agent] 列表加载失败', e)
    } finally {
      loading.value = false
      loaded.value = true
    }
  }

  function select(agentId: string): void {
    selectedAgentId.value = agentId
  }

  return {
    agents,
    loading,
    loaded,
    errorMessage,
    selectedAgentId,
    available,
    defaultAgent,
    selected,
    load,
    select,
  }
})

function describeError(e: unknown): string {
  return e instanceof ApiError ? e.message : t('agent.loadFailed')
}
