<template>
  <div class="markdown-body" @click="handleClick" v-html="html" />
</template>

<script setup lang="ts">
/**
 * 安全 Markdown 展示（AC-CHAT-005）。
 *
 * 🔴 单根元素纪律：`<template>` 根级**不得**放注释。注释也是节点，会让组件变成
 * Fragment 根，父级传入的 `class`（MessageItem 的 `.message-md` 淡入动画）在 dev 下
 * 被静默丢弃，而构建期注释被剥离又恢复为单根 —— dev / prod 行为不一致。
 *
 * 安全边界（原模板 `eslint-disable vue/no-v-html` 的理由，改记于此）：
 *   1. HTML 只来自 `utils/markdown.ts`（markdown-it html:false → DOMPurify 白名单）
 *   2. 复制按钮由渲染层输出为纯 `button[data-action]`，点击由本组件**事件委托**处理，
 *      不注入任何内联事件；代码块因此"可复制、不可执行"
 */
import { computed } from 'vue'

import { useClipboard } from '@/composables/useClipboard'
import { t } from '@/locales'
import { renderMarkdown } from '@/utils/markdown'
import { COPY_FEEDBACK_MS } from '@/utils/uiConstants'

interface Props {
  source: string
}

const props = defineProps<Props>()
const { copy } = useClipboard()

const html = computed(() => renderMarkdown(props.source))

async function handleClick(event: MouseEvent): Promise<void> {
  const target = event.target
  if (!(target instanceof Element)) {
    return
  }
  const button = target.closest('button[data-action="copy-code"]')
  if (!(button instanceof HTMLButtonElement)) {
    return
  }
  const code = button.closest('.md-code')?.querySelector('code')?.textContent ?? ''
  const label = button.querySelector('.md-code-copy-label')
  const ok = await copy(code)
  if (label === null) {
    return
  }
  label.textContent = ok ? t('common.copied') : t('common.copyFailed')
  button.dataset.copied = ok ? 'true' : 'false'
  setTimeout(() => {
    label.textContent = t('common.copy')
    delete button.dataset.copied
  }, COPY_FEEDBACK_MS)
}
</script>

<style scoped>
.markdown-body {
  color: var(--color-bubble-assistant-text);
  font-size: var(--font-size-body);
  line-height: var(--line-height-relaxed);
  overflow-wrap: break-word;
}

.markdown-body :deep(p),
.markdown-body :deep(ul),
.markdown-body :deep(ol),
.markdown-body :deep(blockquote),
.markdown-body :deep(table) {
  margin: 0 0 var(--spacing-base);
}

.markdown-body :deep(> :last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: var(--spacing-lg) 0 var(--spacing-sm);
  font-family: var(--font-family-display);
  font-weight: var(--font-weight-semibold);
  line-height: var(--line-height-tight);
  letter-spacing: var(--letter-spacing-title);
}

.markdown-body :deep(h1) {
  font-size: var(--font-size-title-3);
}

.markdown-body :deep(h2) {
  font-size: var(--font-size-headline);
}

.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  font-size: var(--font-size-body);
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: var(--spacing-lg);
}

.markdown-body :deep(li + li) {
  margin-top: var(--spacing-xs);
}

.markdown-body :deep(blockquote) {
  padding: var(--spacing-sm) var(--spacing-base);
  border-left: 2px solid var(--color-border-strong);
  color: var(--color-text-secondary);
}

.markdown-body :deep(a) {
  color: var(--color-text-link);
  text-decoration: underline;
}

.markdown-body :deep(hr) {
  margin: var(--spacing-lg) 0;
  border: none;
  border-top: 1px solid var(--color-border);
}

.markdown-body :deep(code) {
  padding: 0 var(--spacing-xs);
  border-radius: var(--radius-xs);
  background-color: var(--color-fill-default);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-footnote);
}

.markdown-body :deep(table) {
  display: block;
  width: 100%;
  border-collapse: collapse;
  overflow-x: auto;
  font-size: var(--font-size-footnote);
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: var(--spacing-sm);
  border: 1px solid var(--color-border);
  text-align: left;
}

/* ===== 代码块（design-system §6.7） ===== */
.markdown-body :deep(.md-code) {
  margin: var(--spacing-base) 0;
  border-radius: var(--radius-md);
  background-color: var(--color-code-bg);
  overflow: hidden;
}

.markdown-body :deep(.md-code-head) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  height: var(--control-compact-size);
  padding: 0 var(--spacing-sm) 0 var(--spacing-md);
  border-bottom: 1px solid var(--color-border);
}

.markdown-body :deep(.md-code-lang) {
  color: var(--color-code-text);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-caption);
  opacity: 0.72;
}

.markdown-body :deep(.md-code-copy) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: var(--control-touch-size);
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-sm);
  border: none;
  background: transparent;
  color: var(--color-code-text);
  font-family: var(--font-family-body);
  font-size: var(--font-size-caption);
  cursor: pointer;
  transition: opacity var(--duration-fast) var(--ease-enter);
  opacity: 0.72;
}

.markdown-body :deep(.md-code-copy:hover),
.markdown-body :deep(.md-code-copy[data-copied='true']) {
  opacity: 1;
}

.markdown-body :deep(.md-code-body) {
  margin: 0;
  padding: var(--spacing-md) var(--spacing-base);
  overflow-x: auto;
}

.markdown-body :deep(.md-code-body code) {
  padding: 0;
  background: transparent;
  color: var(--color-code-text);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-footnote);
  line-height: var(--line-height-base);
  white-space: pre;
}
</style>
