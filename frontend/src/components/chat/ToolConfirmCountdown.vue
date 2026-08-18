<template>
  <div class="confirm-countdown">
    <!-- 2px 静态轨道 + 单色剩余进度：🔴 只用 transform:scaleX，绝不动画 width -->
    <span class="confirm-track" aria-hidden="true">
      <span class="confirm-progress" :style="progressStyle" />
    </span>
    <!-- 每秒更新文本，tabular-nums 防抖动；🔴 不进入 aria-live -->
    <span class="confirm-remaining">{{
      t('chat.toolConfirm.remaining', { remaining: remainingSeconds })
    }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * 确认倒计时视觉（design-system.md §14.3.2）。
 *
 * 🔴 禁止圆环、闪烁、红色脉冲与逐秒跳动动画（避免制造焦虑）；
 * 🔴 总时长与剩余值由父级从 `sys_config` / 单调截止时间计算后传入，本组件不含任何秒数默认值。
 *
 * 类名沿用 `confirm-*` 前缀：本组件是 `ToolConfirmCard` 的倒计时分片，
 * 保持同一套语义类名便于规范走查与既有断言（D-010 修复：卡片不再重复实现同一段视觉）。
 */
import { computed } from 'vue'

import { t } from '@/locales'

interface Props {
  remainingSeconds: number
  /** 剩余比例 1 → 0 */
  ratio: number
}

const props = defineProps<Props>()

const progressStyle = computed(() => ({ transform: `scaleX(${props.ratio})` }))
</script>

<style scoped>
.confirm-countdown {
  margin-bottom: var(--spacing-md);
}

.confirm-track {
  display: block;
  overflow: hidden;
  width: 100%;
  height: var(--countdown-track-height);
  border-radius: var(--radius-full);
  background-color: var(--color-tool-countdown-track);
}

.confirm-progress {
  display: block;
  width: 100%;
  height: 100%;
  background-color: var(--color-tool-countdown-progress);
  transform-origin: left center;
  /* 每秒更新一次比例，用一秒的线性插值抹平台阶感 */
  transition: transform var(--duration-second) linear;
}

.confirm-remaining {
  display: block;
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-footnote);
  font-variant-numeric: tabular-nums;
}

/* 🔴 Reduced Motion：进度不做插值动画，只保留每秒文本更新（design-system §14.7） */
@media (prefers-reduced-motion: reduce) {
  .confirm-progress {
    transition: none;
  }
}
</style>
