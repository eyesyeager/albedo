<template>
  <span class="avatar" :class="`avatar--${shape}`" :aria-hidden="decorative ? 'true' : undefined">
    <img
      v-if="src.length > 0 && !failed"
      class="avatar-img"
      :src="src"
      :alt="decorative ? '' : name"
      loading="lazy"
      decoding="async"
      @error="failed = true"
    />
    <span v-else class="avatar-fallback">{{ initial }}</span>
  </span>
</template>

<script setup lang="ts">
/**
 * 头像 / Logo 占位（PRD §7.2：Logo 加载失败显示标题首字符占位）。
 *
 * `decorative` 为 true 时视为纯装饰（相邻已有可见文本），使用空 alt 并对读屏隐藏，
 * 避免同一信息被读两遍。
 */
import { computed, ref, watch } from 'vue'

interface Props {
  src?: string
  /** 用于生成首字符占位的名称（租户标题 / 用户昵称） */
  name?: string
  shape?: 'square' | 'circle'
  decorative?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  src: '',
  name: '',
  shape: 'square',
  decorative: false,
})

const failed = ref(false)

watch(
  () => props.src,
  () => {
    failed.value = false
  },
)

/** 首字符：按 Unicode 码点取，避免把 emoji / 生僻字截半 */
const initial = computed(() => [...props.name.trim()][0] ?? '')
</script>

<style scoped>
.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: var(--avatar-size);
  height: var(--avatar-size);
  overflow: hidden;
  background-color: var(--color-fill-default);
  color: var(--color-text-primary);
  font-size: var(--font-size-footnote);
  font-weight: var(--font-weight-semibold);
  line-height: 1;
  user-select: none;
}

.avatar--square {
  border-radius: var(--radius-md);
}

.avatar--circle {
  border-radius: var(--radius-full);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
