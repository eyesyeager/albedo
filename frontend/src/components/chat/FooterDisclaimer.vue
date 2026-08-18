<template>
  <footer v-if="text.length > 0" class="footer-disclaimer" v-html="html" />
</template>

<script setup lang="ts">
/**
 * 租户页脚声明（design-system.md §6.11）。
 *
 * 🔴 单根元素纪律：`<template>` 根级不得放注释（会变 Fragment 根，父级属性在 dev 下被丢弃）。
 * 原 `eslint-disable vue/no-v-html` 的安全理由见下：v-html 内容经 DOMPurify 白名单净化。
 *
 * 内容来自 `site_config_versions.footerDisclaimer`（🔴 禁止硬编码），
 * 只允许纯文本或**安全** Markdown 链接：渲染经 DOMPurify 白名单，
 * 链接强制下划线 + `rel="noopener noreferrer nofollow"`。
 */
import { computed } from 'vue'

import { renderInlineMarkdown } from '@/utils/markdown'

interface Props {
  text: string
}

const props = defineProps<Props>()

const html = computed(() => renderInlineMarkdown(props.text))
</script>

<style scoped>
.footer-disclaimer {
  width: 100%;
  max-width: var(--layout-content-max-width);
  margin: 0 auto;
  padding: var(--spacing-sm) var(--spacing-base) 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  line-height: var(--line-height-base);
  text-align: center;
}

.footer-disclaimer :deep(a) {
  color: var(--color-text-link);
  text-decoration: underline;
}
</style>
