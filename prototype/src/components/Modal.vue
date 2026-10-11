<script setup lang="ts">
defineProps<{ title: string; size?: "md" | "lg" }>();
const emit = defineEmits(["close"]);
</script>

<template>
  <div class="modal-mask" @click.self="emit('close')">
    <div class="modal" :class="{ 'modal-lg': size === 'lg' }">
      <div class="modal-head">
        <div class="modal-title">{{ title }}</div>
        <button class="x" @click="emit('close')">
          <svg viewBox="0 0 24 24" width="18" height="18">
            <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
          </svg>
        </button>
      </div>
      <div class="modal-body">
        <slot />
      </div>
      <div v-if="$slots.foot" class="modal-foot">
        <slot name="foot" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.x {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.x:hover {
  background: var(--surface-2);
  color: var(--text-1);
}
</style>
