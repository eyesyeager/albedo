<template>
  <RouterView />
</template>

<script setup lang="ts">
/**
 * 应用根组件。
 *
 * 只做三件与页面无关的事：
 *   ① 主题跟随系统偏好（Light / Dark 同构）
 *   ② 应用租户品牌（标题 / favicon / 通过 AA 校验的主题色）
 *   ③ 平台配置降级时给出温和提示（🔴 不白屏、不阻塞使用）
 */
import { ElMessage } from 'element-plus'
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'

import { useSiteBranding } from '@/composables/useSiteBranding'
import { useSystemTheme } from '@/composables/useSystemTheme'
import { t } from '@/locales'
import { useConfigStore } from '@/stores/config'

const configStore = useConfigStore()

useSystemTheme()
useSiteBranding()

onMounted(() => {
  if (configStore.degraded) {
    ElMessage.warning(t('site.configDegraded'))
  }
})
</script>
