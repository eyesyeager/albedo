<script setup lang="ts">
import { useRouter } from "vue-router";
import { overview } from "@/data/mock";
import MiniChart from "@/components/MiniChart.vue";

const router = useRouter();
const o = overview;

const quick = [
  { label: "创建应用", desc: "搭建智能体", icon: "🤖", to: "/apps" },
  { label: "接入模型", desc: "LLM / Embedding / Reranker", icon: "🧠", to: "/models" },
  { label: "新建知识库", desc: "上传文档做 RAG", icon: "📚", to: "/knowledge" },
  { label: "接入 MCP", desc: "连接外部工具", icon: "🔌", to: "/mcp" },
];

const recent = [
  { t: "企业知识助手 · 知微 配置已保存", by: "林深", time: "17:32", tag: "应用" },
  { t: "工单系统 MCP 工具 export_tickets 已禁用", by: "林深", time: "14:22", tag: "MCP" },
  { t: "产品资料库 新增 3 篇文档并重建索引", by: "林深", time: "11:40", tag: "知识库" },
  { t: "glm-4-plus 接入校验失败（密钥无效）", by: "林深", time: "09-28", tag: "模型" },
];

const colorMap: Record<string, string> = {
  call: "#6366f1",
  token: "#0d9488",
  err: "#dc2626",
  sess: "#7c3aed",
};
function r2c(k: string) {
  return colorMap[k] || "#6366f1";
}
function metricSpark(key: string) {
  const scale = key === "err" ? 0.4 : 0.06;
  return o.trend.calls.slice(0, 7).map((v) => Math.round(v * scale));
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">总览</h1>
        <p class="sub">欢迎回来，林深 · 这是你的平台资源与运行状况概览</p>
      </div>
      <div class="head-actions">
        <span class="tag" style="color: var(--green); background: var(--green-soft)">
          <span class="dot" style="background: var(--green)" /> 运行正常
        </span>
        <span class="muted" style="font-size: 13px">数据更新于 2026-10-09 18:00</span>
      </div>
    </div>

    <!-- Resource stats -->
    <div class="res-grid">
      <div v-for="r in o.resources" :key="r.key" class="res-card card">
        <div class="res-ico" :style="{ background: r.color + '1a', color: r.color }">
          {{ r.icon }}
        </div>
        <div class="res-body">
          <div class="res-val">{{ r.value }}</div>
          <div class="res-label">{{ r.label }}</div>
        </div>
      </div>
    </div>

    <!-- Metrics -->
    <div class="metric-grid">
      <div v-for="m in o.metrics" :key="m.key" class="metric card">
        <div class="metric-label">{{ m.label }}</div>
        <div class="metric-row">
          <div class="metric-val">
            {{ m.value }}<span class="metric-unit">{{ m.unit }}</span>
          </div>
          <span
            class="delta"
            :class="m.delta >= 0 ? 'up' : 'down'"
          >
            {{ m.delta >= 0 ? "▲" : "▼" }} {{ Math.abs(m.delta) }}%
          </span>
        </div>
        <div class="metric-chart">
          <MiniChart
            :data="metricSpark(m.key)"
            :color="r2c(m.key)"
            :height="44"
            :fill="false"
          />
        </div>
      </div>
    </div>

    <!-- Trend + quick + recent -->
    <div class="lower">
      <div class="card trend-card">
        <div class="card-head">
          <div class="card-title">调用量与错误率趋势（近 7 天）</div>
          <div class="legend">
            <span><i class="sw" style="background: #6366f1" /> 调用量</span>
            <span><i class="sw" style="background: #dc2626" /> 错误率</span>
          </div>
        </div>
        <div class="trend-big">
          <MiniChart :data="o.trend.calls" color="#6366f1" :height="200" />
          <div class="trend-axis">
            <span v-for="l in o.trend.labels" :key="l">{{ l }}</span>
          </div>
        </div>
        <div class="trend-big" style="margin-top: 10px">
          <MiniChart :data="o.trend.errors" color="#dc2626" :height="70" :fill="false" />
        </div>
      </div>

      <div class="side-col">
        <div class="card">
          <div class="card-head"><div class="card-title">快捷入口</div></div>
          <div class="quick-grid">
            <button
              v-for="q in quick"
              :key="q.label"
              class="quick"
              @click="router.push(q.to)"
            >
              <div class="quick-ico">{{ q.icon }}</div>
              <div class="quick-label">{{ q.label }}</div>
              <div class="quick-desc">{{ q.desc }}</div>
            </button>
          </div>
        </div>

        <div class="card recent-card">
          <div class="card-head"><div class="card-title">最近动态</div></div>
          <div class="recent">
            <div v-for="(r, i) in recent" :key="i" class="recent-item">
              <span class="recent-dot" :class="`rc-${i % 4}`" />
              <div class="recent-body">
                <div class="recent-text">{{ r.t }}</div>
                <div class="recent-meta">{{ r.by }} · {{ r.time }}</div>
              </div>
              <span class="tag recent-tag">{{ r.tag }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1280px;
  margin: 0 auto;
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 22px;
}
.title {
  font-size: 24px;
  font-weight: 800;
  margin: 0 0 4px;
}
.sub {
  color: var(--text-3);
  font-size: 13.5px;
  margin: 0;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.res-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.res-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
}
.res-ico {
  width: 50px;
  height: 50px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex: none;
}
.res-val {
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
}
.res-label {
  color: var(--text-3);
  font-size: 13px;
  margin-top: 5px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.metric {
  padding: 18px 20px;
}
.metric-label {
  color: var(--text-3);
  font-size: 13px;
}
.metric-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: 8px;
}
.metric-val {
  font-size: 26px;
  font-weight: 800;
}
.metric-unit {
  font-size: 13px;
  color: var(--text-3);
  margin-left: 3px;
  font-weight: 500;
}
.delta {
  font-size: 12.5px;
  font-weight: 700;
  padding: 2px 7px;
  border-radius: 999px;
}
.delta.up {
  color: var(--green);
  background: var(--green-soft);
}
.delta.down {
  color: var(--red);
  background: var(--red-soft);
}
.metric-chart {
  margin-top: 12px;
}
.lower {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 16px;
  align-items: start;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
}
.card-title {
  font-size: 15px;
  font-weight: 700;
}
.trend-card {
  padding-bottom: 20px;
}
.legend {
  display: flex;
  gap: 16px;
  font-size: 12.5px;
  color: var(--text-2);
}
.legend i.sw {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 3px;
  margin-right: 6px;
  vertical-align: -1px;
}
.trend-big {
  padding: 18px 20px 0;
}
.trend-axis {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--text-4);
  padding: 6px 2px 0;
}
.side-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 16px;
}
.quick {
  text-align: left;
  border: 1px solid var(--border);
  background: var(--surface-2);
  border-radius: 12px;
  padding: 14px;
  transition: all 0.15s;
}
.quick:hover {
  border-color: var(--brand);
  background: var(--brand-soft-2);
  transform: translateY(-2px);
  box-shadow: var(--sh-sm);
}
.quick-ico {
  font-size: 24px;
}
.quick-label {
  font-weight: 700;
  font-size: 14px;
  margin-top: 8px;
}
.quick-desc {
  font-size: 11.5px;
  color: var(--text-3);
  margin-top: 2px;
}
.recent-card {
  flex: 1;
}
.recent {
  padding: 8px 20px 16px;
}
.recent-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.recent-item:last-child {
  border-bottom: none;
}
.recent-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: none;
}
.rc-0 {
  background: #6366f1;
}
.rc-1 {
  background: #0d9488;
}
.rc-2 {
  background: #d97706;
}
.rc-3 {
  background: #dc2626;
}
.recent-body {
  flex: 1;
  min-width: 0;
}
.recent-text {
  font-size: 13.5px;
  color: var(--text-1);
  font-weight: 500;
}
.recent-meta {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}
.recent-tag {
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
}
</style>
