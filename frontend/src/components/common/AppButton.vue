<template>
  <button
    :type="type"
    class="app-button"
    :class="[`app-button--${variant}`, { 'app-button--icon': iconOnly, 'app-button--block': block }]"
    :disabled="disabled || loading"
    :aria-busy="loading ? 'true' : undefined"
    :aria-label="ariaLabel"
  >
    <Loader2 v-if="loading" class="app-button-spinner" :size="iconSize" aria-hidden="true" />
    <slot v-else name="icon" />
    <span v-if="!iconOnly" class="app-button-label"><slot /></span>
  </button>
</template>

<script setup lang="ts">
/**
 * 通用按钮（design-system.md §6.2）。
 *
 * 为什么不用 el-button：对话页对 44px 命中区、Token 一致性与 loading 不跳动的要求更严，
 * 用原生 button + Token 样式更可控；Element Plus 仍是唯一"组件库"（Select/Drawer/Dropdown 等）。
 */
import { Loader2 } from 'lucide-vue-next'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'

interface Props {
  variant?: Variant
  type?: 'button' | 'submit'
  disabled?: boolean
  loading?: boolean
  /** 仅图标按钮：必须提供 ariaLabel（可访问名称不可缺失） */
  iconOnly?: boolean
  block?: boolean
  ariaLabel?: string
  iconSize?: number
}

withDefaults(defineProps<Props>(), {
  variant: 'secondary',
  type: 'button',
  disabled: false,
  loading: false,
  iconOnly: false,
  block: false,
  ariaLabel: undefined,
  iconSize: 18,
})
</script>

<style scoped>
.app-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  min-height: var(--control-touch-size);
  padding: 0 var(--spacing-base);
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-primary);
  font-family: var(--font-family-body);
  font-size: var(--font-size-callout);
  font-weight: var(--font-weight-medium);
  line-height: var(--line-height-base);
  cursor: pointer;
  /* 只过渡颜色与 transform，禁止 transition: all */
  transition:
    background-color var(--duration-fast) var(--ease-enter),
    border-color var(--duration-fast) var(--ease-enter),
    color var(--duration-fast) var(--ease-enter),
    transform var(--duration-instant) var(--ease-enter);
}

.app-button--block {
  width: 100%;
}

.app-button--icon {
  width: var(--control-touch-size);
  min-width: var(--control-touch-size);
  padding: 0;
}

.app-button-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-button--primary {
  background-color: var(--color-brand);
  color: var(--color-text-on-brand);
  font-weight: var(--font-weight-semibold);
}

.app-button--secondary {
  border-color: var(--color-border-strong);
  background-color: var(--color-bg-elevated);
}

.app-button--danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
}

@media (hover: hover) and (pointer: fine) {
  .app-button--primary:hover:not(:disabled) {
    background-color: var(--color-brand-hover);
  }

  .app-button--secondary:hover:not(:disabled),
  .app-button--ghost:hover:not(:disabled) {
    background-color: var(--color-fill-subtle);
  }

  .app-button--danger:hover:not(:disabled) {
    background-color: var(--color-fill-subtle);
  }
}

.app-button:active:not(:disabled) {
  transform: scale(0.97);
}

.app-button--primary:active:not(:disabled) {
  background-color: var(--color-brand-pressed);
}

.app-button--secondary:active:not(:disabled),
.app-button--ghost:active:not(:disabled),
.app-button--danger:active:not(:disabled) {
  background-color: var(--color-fill-default);
}

.app-button:disabled {
  background-color: var(--color-fill-subtle);
  border-color: var(--color-border);
  color: var(--color-text-disabled);
  cursor: not-allowed;
  box-shadow: var(--shadow-none);
}

.app-button--ghost:disabled {
  background-color: transparent;
  border-color: transparent;
}

.app-button-spinner {
  animation: app-button-spin 1s linear infinite;
}

@keyframes app-button-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 键盘触发不播放缩放动画（design-system §6.2） */
@media (prefers-reduced-motion: reduce) {
  .app-button:active:not(:disabled) {
    transform: none;
  }

  .app-button-spinner {
    animation: none;
  }
}
</style>
