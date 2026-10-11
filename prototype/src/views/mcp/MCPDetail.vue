<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { mcpServers } from "@/data/mock";
import StatusTag from "@/components/StatusTag.vue";

const route = useRoute();
const router = useRouter();
const mcp = mcpServers.find((m) => m.id === route.params.id) || mcpServers[0];
const testing = ref(false);
const testLog = ref<string[]>([]);

function runTest() {
  testing.value = true;
  testLog.value = [];
  const steps = [
    "→ 建立连接：POST " + mcp.endpoint,
    "← 200 OK · 协议握手成功 (MCP v1)",
    "→ 拉取工具清单 (tools/list)",
    `← 返回 ${mcp.tools.length} 个工具`,
    "→ 调用探测工具 ping",
    "← 200 · 耗时 86ms",
    "✓ 连通性测试通过",
  ];
  let i = 0;
  const t = setInterval(() => {
    if (i < steps.length) {
      testLog.value.push(steps[i]);
      i++;
    } else {
      clearInterval(t);
      testing.value = false;
    }
  }, 380);
}
</script>

<template>
  <div class="page">
    <div class="detail-head card">
      <button class="back" @click="router.push('/mcp')">‹ MCP</button>
      <div class="dh-ico" :class="{ err: mcp.status === 'error' }">🔌</div>
      <div class="dh-info">
        <div class="dh-name">{{ mcp.name }}</div>
        <div class="dh-desc mono">{{ mcp.endpoint }}</div>
        <div class="dh-tags">
          <StatusTag
            :color="mcp.status === 'connected' ? 'var(--green)' : 'var(--red)'"
            :bg="mcp.status === 'connected' ? 'var(--green-soft)' : 'var(--red-soft)'"
            dot
          >{{ mcp.status === "connected" ? "已连接" : "连接异常" }}</StatusTag>
          <span class="chip">{{ mcp.transport }}</span>
          <span class="chip">鉴权 {{ mcp.auth }}</span>
        </div>
      </div>
      <button class="btn btn-primary" :disabled="testing" @click="runTest">
        {{ testing ? "测试中…" : "连通性测试" }}
      </button>
    </div>

    <div class="body">
      <section class="card sec">
        <div class="sec-title">连接信息</div>
        <div class="kv-row"><span class="kv-k">服务地址</span><code class="mono">{{ mcp.endpoint }}</code></div>
        <div class="kv-row"><span class="kv-k">传输协议</span><span>{{ mcp.transport }}</span></div>
        <div class="kv-row"><span class="kv-k">鉴权</span><span>{{ mcp.auth }}</span></div>
        <div class="kv-row"><span class="kv-k">更新时间</span><span class="mono">{{ mcp.updatedAt }}</span></div>
      </section>

      <section class="card sec">
        <div class="sec-title">
          工具清单
          <span class="count">{{ mcp.tools.length }}</span>
          <span class="muted" style="font-weight:400;font-size:12.5px;margin-left:6px">可禁用单个高危工具</span>
        </div>
        <div v-for="t in mcp.tools" :key="t.name" class="tool-row">
          <div class="tool-main">
            <div class="tool-name mono">{{ t.name }}</div>
            <div class="tool-sub">{{ t.desc }}</div>
          </div>
          <span class="tool-state" :class="t.enabled ? 'on' : 'off'">
            {{ t.enabled ? "启用" : "已禁用" }}
          </span>
          <span class="switch" :class="{ on: t.enabled }" @click="t.enabled = !t.enabled" />
        </div>
      </section>

      <section class="card sec" v-if="testLog.length || testing">
        <div class="sec-title">调试日志</div>
        <pre class="log mono">{{ testLog.join("\n") }}{{ testing ? "\n…" : "" }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1100px;
  margin: 0 auto;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 22px;
  margin-bottom: 16px;
}
.back {
  border: none;
  background: transparent;
  color: var(--text-3);
  font-size: 14px;
  padding: 4px 8px;
  border-radius: 8px;
}
.back:hover {
  background: var(--surface-2);
  color: var(--text-1);
}
.dh-ico {
  width: 50px;
  height: 50px;
  border-radius: 14px;
  background: var(--violet-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  flex: none;
}
.dh-ico.err {
  background: var(--red-soft);
}
.dh-info {
  flex: 1;
  min-width: 0;
}
.dh-name {
  font-size: 18px;
  font-weight: 800;
}
.dh-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin: 3px 0 9px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.dh-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
.chip {
  font-size: 12px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 10px;
}
.body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.sec {
  padding: 20px;
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.count {
  font-size: 12px;
  background: var(--brand-soft);
  color: var(--brand-700);
  border-radius: 999px;
  padding: 1px 8px;
  font-weight: 700;
}
.kv-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
  font-size: 13.5px;
}
.kv-row:last-child {
  border-bottom: none;
}
.kv-k {
  width: 84px;
  flex: none;
  color: var(--text-3);
  font-size: 13px;
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 13px 0;
  border-bottom: 1px solid var(--border);
}
.tool-row:last-child {
  border-bottom: none;
}
.tool-main {
  flex: 1;
}
.tool-name {
  font-size: 14px;
  font-weight: 600;
}
.tool-sub {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 3px;
}
.tool-state {
  font-size: 12.5px;
  font-weight: 600;
}
.tool-state.on {
  color: var(--green);
}
.tool-state.off {
  color: var(--text-3);
}
.log {
  background: #1b1c33;
  color: #c8cae0;
  border-radius: 10px;
  padding: 14px 16px;
  font-size: 12.5px;
  line-height: 1.8;
  white-space: pre-wrap;
  margin: 0;
}
</style>
