<script setup lang="ts">
import type { TraceStep } from "@/types";

defineProps<{ steps: TraceStep[]; compact?: boolean }>();
</script>

<template>
  <div class="flow">
    <div
      v-for="s in steps"
      :key="s.id"
      class="step"
      :class="`step-${s.type}`"
    >
      <div class="step-rail">
        <span class="step-badge" :class="`b-${s.type}`">
          <template v-if="s.type === 'think'">💡</template>
          <template v-else-if="s.type === 'tool'">🔧</template>
          <template v-else-if="s.type === 'step'">▦</template>
          <template v-else>✎</template>
        </span>
      </div>
      <div class="step-body">
        <div class="step-head">
          <span class="step-title">{{ s.title }}</span>
          <span
            v-if="s.status"
            class="step-status"
            :class="s.status"
          >
            <span class="dot" :class="s.status" />
            {{ s.status === "success" ? "成功" : s.status === "error" ? "拒绝/失败" : "运行中" }}
          </span>
          <span v-if="s.latency" class="step-lat">{{ s.latency }} ms</span>
        </div>
        <div v-if="s.detail" class="step-detail">{{ s.detail }}</div>

        <div v-if="s.input" class="io">
          <div class="io-label">入参</div>
          <pre class="io-code">{{ s.input }}</pre>
        </div>
        <div v-if="s.output" class="io">
          <div class="io-label">返回</div>
          <pre class="io-code" :class="{ err: s.status === 'error' }">{{ s.output }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.flow {
  display: flex;
  flex-direction: column;
}
.step {
  display: flex;
  gap: 14px;
}
.step-rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: none;
  width: 30px;
}
.step-badge {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  border: 1px solid var(--border-strong);
  background: var(--surface);
  z-index: 1;
}
.b-think {
  background: var(--amber-soft);
  border-color: #f3d9a8;
}
.b-tool {
  background: var(--brand-soft);
  border-color: #c7cafb;
}
.b-text {
  background: var(--green-soft);
  border-color: #bfe6cd;
}
.b-step {
  background: var(--violet-soft);
  border-color: #ddc9fb;
}
.step-rail::after {
  content: "";
  flex: 1;
  width: 2px;
  background: var(--border);
  margin-top: 2px;
}
.step:last-child .step-rail::after {
  display: none;
}
.step-body {
  flex: 1;
  padding-bottom: 18px;
  min-width: 0;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.step-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-1);
}
.step-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 999px;
}
.step-status.success {
  color: var(--green);
  background: var(--green-soft);
}
.step-status.error {
  color: var(--red);
  background: var(--red-soft);
}
.step-status.running {
  color: var(--blue);
  background: var(--blue-soft);
}
.dot.success {
  background: var(--green);
}
.dot.error {
  background: var(--red);
}
.dot.running {
  background: var(--blue);
}
.step-lat {
  font-size: 12px;
  color: var(--text-3);
  font-family: var(--mono);
}
.step-detail {
  font-size: 13.5px;
  color: var(--text-2);
  line-height: 1.65;
  margin-top: 7px;
  background: var(--surface-2);
  border-radius: 10px;
  padding: 10px 13px;
}
.io {
  margin-top: 10px;
}
.io-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  margin-bottom: 5px;
}
.io-code {
  margin: 0;
  background: #1b1c33;
  color: #d6d9f0;
  border-radius: 10px;
  padding: 11px 14px;
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.io-code.err {
  color: #ffb4b4;
}
</style>
