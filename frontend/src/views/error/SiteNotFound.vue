<template>
  <SiteStatusPanel
    :title="t('site.notFound')"
    :hint="t('site.notFoundHint')"
    :icon="SearchX"
    tone="neutral"
  />
</template>

<script setup lang="ts">
/**
 * 对应业务码 30010 TENANT_NOT_FOUND。
 * 站点级 HTTP 状态由 `GET /site/status` 返回 404；本视图是前端兜底，视觉模板一致。
 * 🔴 不确认任何租户是否存在。
 */
import { SearchX } from 'lucide-vue-next'

import SiteStatusPanel from '@/components/SiteStatusPanel.vue'
import { t } from '@/locales'
</script>
