/**
 * 租户站点配置 Store（占位实现，M1 由 @前端 补全展示逻辑）。
 *
 * 职责：
 *   1. 启动时拉取 GET /api/v1/site/config
 *   2. 命中 30010/30011/30012 时记录站点状态，由 router 守卫路由到对应错误视图
 *      （🔴 站点异常必须有确定视图，禁止白屏）
 *   3. 提供租户品牌文案给页面消费（🔴 前端禁止硬编码任何租户文案）
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { siteApi, type SiteConfig } from '@/api/site'
import { ERROR_CODE } from '@/types/api'
import { ApiError } from '@/utils/request'

/** 站点可用性状态。 */
export type SiteStatus = 'unknown' | 'ready' | 'notFound' | 'suspended' | 'unavailable' | 'error'

export const useSiteStore = defineStore('site', () => {
  const config = ref<SiteConfig | null>(null)
  const status = ref<SiteStatus>('unknown')
  const loaded = ref(false)

  const ready = computed(() => status.value === 'ready' && config.value !== null)

  async function load(): Promise<void> {
    try {
      config.value = await siteApi.config()
      status.value = 'ready'
      if (config.value.siteTitle.length > 0) {
        document.title = config.value.siteTitle
      }
    } catch (e: unknown) {
      config.value = null
      status.value = mapStatus(e)
    } finally {
      loaded.value = true
    }
  }

  function mapStatus(e: unknown): SiteStatus {
    if (!(e instanceof ApiError)) {
      return 'error'
    }
    switch (e.code) {
      case ERROR_CODE.TENANT_NOT_FOUND:
        return 'notFound'
      case ERROR_CODE.TENANT_SUSPENDED:
        return 'suspended'
      case ERROR_CODE.TENANT_CONFIG_UNAVAILABLE:
        return 'unavailable'
      default:
        return 'error'
    }
  }

  return { config, status, loaded, ready, load }
})
