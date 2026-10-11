<script setup lang="ts">
defineProps<{
  color: string;
  bg: string;
  text?: string;
  dot?: boolean;
}>();
</script>

<template>
  <span class="tag" :style="{ color, background: bg }">
    <span v-if="dot" class="dot" :style="{ background: color }" />
    <slot />
  </span>
</template>
