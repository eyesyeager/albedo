<template>
  <SiteStatusPanel
    :title="t('site.suspended')"
    :hint="t('site.suspendedHint')"
    :icon="PauseCircle"
    tone="warning"
  />
</template>

<script setup lang="ts">
/**
 * 对应业务码 30011 TENANT_SUSPENDED（站点级 HTTP 403）。
 * 🔴 暂停态不展示登录入口、Agent 或任何租户业务数据（AC-TEN-006）。
 */
import { PauseCircle } from 'lucide-vue-next'

import SiteStatusPanel from '@/components/SiteStatusPanel.vue'
import { t } from '@/locales'
</script>
