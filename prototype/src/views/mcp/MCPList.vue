<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { mcpServers } from "@/data/mock";
import { mcpUsage } from "@/store/usage";
import type { McpServer } from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const router = useRouter();
const list = ref<McpServer[]>([...mcpServers]);
const showCreate = ref(false);
const delTarget = ref<McpServer | null>(null);
const blockTarget = ref<McpServer | null>(null);
const blockUsage = computed(() => (blockTarget.value ? mcpUsage(blockTarget.value) : []));
const testing = ref<string | null>(null);

// 被应用挂载时不允许删除
function usageCount(mcp: McpServer) {
  return mcpUsage(mcp).length;
}
function requestDelete(mcp: McpServer) {
  if (usageCount(mcp) > 0) {
    blockTarget.value = mcp;
    return;
  }
  delTarget.value = mcp;
}
const form = ref({ name: "", endpoint: "", transport: "SSE" as const, auth: "Bearer Token" });

function test(id: string) {
  testing.value = id;
  setTimeout(() => (testing.value = null), 1100);
}
// 同步移除 mock 源数组，使详情页与应用工具装配读取同步生效
function removeFromSource(id: string) {
  const i = mcpServers.findIndex((m) => m.id === id);
  if (i > -1) mcpServers.splice(i, 1);
}
function confirmDelete() {
  if (!delTarget.value) return;
  const id = delTarget.value.id;
  removeFromSource(id);
  list.value = list.value.filter((m) => m.id !== id);
  delTarget.value = null;
}
function create() {
  if (!form.value.name) return;
  list.value.unshift({
    id: "mc-" + Date.now(),
    name: form.value.name,
    endpoint: form.value.endpoint,
    transport: form.value.transport,
    auth: form.value.auth,
    status: "connected",
    updatedAt: "刚刚",
    tools: [{ name: "ping", desc: "连通性测试", enabled: true }],
  });
  showCreate.value = false;
  form.value = { name: "", endpoint: "", transport: "SSE", auth: "Bearer Token" };
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">MCP 管理</h1>
        <p class="sub">以 MCP 协议统一接入外部工具与服务，支持 Server 级接入与工具级禁用</p>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">+ 接入 MCP</button>
    </div>

    <div class="grid">
      <div
        v-for="m in list"
        :key="m.id"
        class="mc-card card"
        @click="router.push('/mcp/' + m.id)"
      >
        <div class="mc-top">
          <div class="mc-ico" :class="m.status === 'error' ? 'err' : ''">🔌</div>
          <StatusTag
            :color="m.status === 'connected' ? 'var(--green)' : 'var(--red)'"
            :bg="m.status === 'connected' ? 'var(--green-soft)' : 'var(--red-soft)'"
            dot
          >{{ m.status === "connected" ? "已连接" : "连接异常" }}</StatusTag>
        </div>
        <div class="mc-name">{{ m.name }}</div>
        <div class="mc-endpoint mono">{{ m.endpoint }}</div>
        <div class="mc-foot">
          <span class="chip">{{ m.transport }}</span>
          <span class="chip">{{ m.tools.length }} 个工具</span>
          <span class="chip">{{ m.tools.filter((t) => !t.enabled).length }} 禁用</span>
          <span v-if="usageCount(m)" class="used-hint">被 {{ usageCount(m) }} 个应用使用</span>
          <button
            class="btn btn-xs btn-ghost danger del"
            @click.stop="requestDelete(m)"
          >删除</button>
        </div>
      </div>
    </div>

    <Modal v-if="showCreate" title="接入自定义 MCP Server" size="lg" @close="showCreate = false">
      <div style="margin-bottom:16px">
        <label class="field-label">服务名称</label>
        <input v-model="form.name" class="field" placeholder="例如：工单系统 MCP" />
      </div>
      <div style="margin-bottom:16px">
        <label class="field-label">Endpoint</label>
        <input v-model="form.endpoint" class="field mono" placeholder="https://.../sse" />
      </div>
      <div class="row2">
        <div>
          <label class="field-label">传输协议</label>
          <select v-model="form.transport" class="field">
            <option>SSE</option>
            <option>Streamable HTTP</option>
          </select>
        </div>
        <div>
          <label class="field-label">鉴权方式</label>
          <select v-model="form.auth" class="field">
            <option>Bearer Token</option>
            <option>API Key</option>
            <option>无</option>
          </select>
        </div>
      </div>
      <div class="field-hint" style="margin-top:14px">
        接入后将自动拉取工具清单，可在详情页对单个高危工具单独禁用。
      </div>
      <template #foot>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" @click="create">接入并拉取工具</button>
      </template>
    </Modal>

    <!-- 删除确认 -->
    <Modal v-if="delTarget" title="删除 MCP Server" @close="delTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除 MCP Server「<b>{{ delTarget.name }}</b>」（{{ delTarget.endpoint }}）？<br />
          将同时移除其下 <b>{{ delTarget.tools.length }}</b> 个工具，并从已挂载的应用中解绑；相关的 Skill 封装模板将不可用。此操作不可恢复。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="delTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDelete">确认删除</button>
      </template>
    </Modal>

    <!-- 被占用，禁止删除 -->
    <Modal v-if="blockTarget" title="无法删除 MCP Server" @close="blockTarget = null">
      <div class="del-body">
        <div class="del-ico">🚫</div>
        <div>
          MCP Server「<b>{{ blockTarget.name }}</b>」正被以下应用挂载，需先在应用「工具装配」中解绑后再删除：
          <div class="used-apps">
            <span v-for="a in blockUsage" :key="a.id" class="used-app">{{ a.name }}</span>
          </div>
        </div>
      </div>
      <template #foot>
        <button class="btn btn-primary" @click="blockTarget = null">我知道了</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.page {
  max-width: 1240px;
  margin: 0 auto;
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;
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
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.mc-card {
  padding: 18px;
  cursor: pointer;
  transition: all 0.18s;
}
.mc-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
  border-color: var(--brand-soft);
}
.mc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mc-ico {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--violet-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.mc-ico.err {
  background: var(--red-soft);
}
.mc-name {
  font-size: 16px;
  font-weight: 800;
  margin-top: 14px;
}
.mc-endpoint {
  font-size: 11.5px;
  color: var(--text-3);
  background: var(--surface-2);
  border-radius: 8px;
  padding: 7px 10px;
  margin-top: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-foot {
  display: flex;
  gap: 7px;
  margin-top: 14px;
  flex-wrap: wrap;
}
.chip {
  font-size: 12px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 10px;
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.del {
  margin-left: auto;
}
.used-hint {
  font-size: 11.5px;
  color: var(--amber);
  background: var(--amber-soft);
  border-radius: 999px;
  padding: 2px 9px;
}
.used-apps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.used-app {
  font-size: 12.5px;
  color: var(--brand-700);
  background: var(--brand-soft);
  border-radius: 8px;
  padding: 3px 9px;
}
.danger:hover {
  color: var(--red);
  border-color: var(--red);
  background: var(--red-soft);
}
.del-body {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  font-size: 13.5px;
  color: var(--text-2);
  line-height: 1.7;
}
.del-ico {
  font-size: 20px;
  flex: none;
}
.danger-btn {
  background: var(--red);
  border-color: var(--red);
  color: #fff;
}
.danger-btn:hover {
  background: #b91c1c;
  border-color: #b91c1c;
}
</style>
