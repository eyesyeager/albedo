<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    data: number[];
    color?: string;
    height?: number;
    fill?: boolean;
    labels?: string[];
  }>(),
  { color: "#6366f1", height: 120, fill: true }
);

const W = 600;
const H = computed(() => props.height);

const geom = computed(() => {
  const d = props.data;
  const max = Math.max(...d);
  const min = Math.min(...d);
  const range = max - min || 1;
  const pad = 8;
  const stepX = (W - pad * 2) / (d.length - 1 || 1);
  const pts = d.map((v, i) => {
    const x = pad + i * stepX;
    const y = pad + (H.value - pad * 2) * (1 - (v - min) / range);
    return [x, y];
  });
  const line = pts.map((p, i) => `${i ? "L" : "M"}${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(" ");
  const area = `${line} L${pts[pts.length - 1][0].toFixed(1)} ${H.value - pad} L${pts[0][0].toFixed(1)} ${H.value - pad} Z`;
  return { line, area, pts };
});
</script>

<template>
  <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="chart">
    <defs>
      <linearGradient :id="`g-${color.slice(1)}`" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" :stop-color="color" stop-opacity="0.28" />
        <stop offset="1" :stop-color="color" stop-opacity="0" />
      </linearGradient>
    </defs>
    <path v-if="fill" :d="geom.area" :fill="`url(#g-${color.slice(1)})`" />
    <path :d="geom.line" fill="none" :stroke="color" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" />
    <circle
      v-for="(p, i) in geom.pts"
      :key="i"
      :cx="p[0]"
      :cy="p[1]"
      r="2.6"
      :fill="color"
    />
  </svg>
</template>

<style scoped>
.chart {
  width: 100%;
  display: block;
}
</style>
