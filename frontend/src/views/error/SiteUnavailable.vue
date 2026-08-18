<template>
  <SiteStatusPanel
    :title="t('site.unavailable')"
    :hint="t('site.unavailableHint')"
    :icon="ServerCrash"
    tone="danger"
    :retry-text="t('common.retry')"
    @retry="handleRetry"
  />
</template>

<script setup lang="ts">
/**
 * 对应业务码 30012 TENANT_CONFIG_UNAVAILABLE（站点级 HTTP 503）。
 *
 * 重试链路对接后端唯一非 200 端点 `GET /site/status`：
 * 恢复（200）才整页重载，否则给出仍不可用的语义，🔴 不泄露内部配置细节。
 */
import { ElMessage } from 'element-plus'
import { ServerCrash } from 'lucide-vue-next'

import { siteApi } from '@/api/site'
import SiteStatusPanel from '@/components/SiteStatusPanel.vue'
import { t } from '@/locales'

async function handleRetry(): Promise<void> {
  const status = await siteApi.probeStatus()
  if (status === 'ok') {
    location.reload()
    return
  }
  ElMessage.warning(t('site.stillUnavailable'))
}
</script>
