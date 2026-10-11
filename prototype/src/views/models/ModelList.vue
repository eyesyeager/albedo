<script setup lang="ts">
import { ref, computed } from "vue";
import { models, rerankerProviders } from "@/data/mock";
import type { Model, ModelType, RerankerProvider } from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const list = ref<Model[]>([...models]);
const filter = ref<"ALL" | ModelType>("ALL");
const showCreate = ref(false);
const form = ref({
  name: "",
  type: "LLM" as ModelType,
  provider: "",
  baseUrl: "",
  apiKey: "",
  temperature: 0.7,
  maxTokens: 4096,
  contextLen: 32768,
});
const isReranker = computed(() => form.value.type === "RERANKER");
function pickRerankerProvider(p: RerankerProvider) {
  form.value.provider = p.name;
  form.value.baseUrl = p.baseUrl;
  if (p.model) form.value.name = p.model;
}

const typeMeta: Record<ModelType, { label: string; color: string; bg: string }> = {
  LLM: { label: "LLM", color: "#6366f1", bg: "#eef0fe" },
  EMBEDDING: { label: "Embedding", color: "#0d9488", bg: "#e2f6f3" },
  RERANKER: { label: "Reranker", color: "#d97706", bg: "#fdf3e3" },
};

const filtered = () =>
  filter.value === "ALL" ? list.value : list.value.filter((m) => m.type === filter.value);

function statusTag(m: Model) {
  if (m.status === "preset")
    return { color: "var(--text-3)", bg: "var(--surface-2)", text: "平台预置" };
  if (m.status === "online")
    return { color: "var(--green)", bg: "var(--green-soft)", text: "在线" };
  return {
    color: "var(--red)",
    bg: "var(--red-soft)",
    text: m.updatedAt === "未接入" ? "未接入" : "离线",
  };
}
function create() {
  if (!form.value.name) return;
  list.value.unshift({
    id: "m-" + Date.now(),
    name: form.value.name,
    type: form.value.type,
    provider: form.value.provider || "自定义",
    baseUrl: form.value.baseUrl,
    apiKeyMasked: form.value.apiKey ? "sk-****" + Math.random().toString(16).slice(2, 6) : "—",
    status: "online",
    temperature: form.value.temperature,
    topP: 0.9,
    maxTokens: form.value.maxTokens,
    contextLen: form.value.contextLen,
    usage: { inputTokens: 0, outputTokens: 0 },
    updatedAt: "刚刚",
  });
  showCreate.value = false;
  form.value = {
    name: "",
    type: "LLM",
    provider: "",
    baseUrl: "",
    apiKey: "",
    temperature: 0.7,
    maxTokens: 4096,
    contextLen: 32768,
  };
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">模型管理</h1>
        <p class="sub">统一接入与管理 LLM / Embedding / Reranker 三类模型，覆盖 OpenAI 及兼容服务</p>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">+ 接入模型</button>
    </div>

    <div class="seg" style="margin-bottom:16px">
      <button :class="{ on: filter === 'ALL' }" @click="filter = 'ALL'">全部</button>
      <button :class="{ on: filter === 'LLM' }" @click="filter = 'LLM'">LLM</button>
      <button :class="{ on: filter === 'EMBEDDING' }" @click="filter = 'EMBEDDING'">Embedding</button>
      <button :class="{ on: filter === 'RERANKER' }" @click="filter = 'RERANKER'">Reranker</button>
    </div>

    <div class="grid">
      <div v-for="m in filtered()" :key="m.id" class="model-card card">
        <div class="mc-top">
          <span class="tag" :style="{ color: typeMeta[m.type].color, background: typeMeta[m.type].bg }">
            {{ typeMeta[m.type].label }}
          </span>
          <StatusTag :color="statusTag(m).color" :bg="statusTag(m).bg" dot>{{ statusTag(m).text }}</StatusTag>
        </div>
        <div class="mc-name">{{ m.name }}</div>
        <div class="mc-provider">{{ m.provider }}</div>
        <div class="mc-url mono">{{ m.baseUrl }}</div>
        <div v-if="m.type !== 'RERANKER'" class="mc-params">
          <div><span>温度</span><b>{{ m.temperature }}</b></div>
          <div><span>Max Tokens</span><b>{{ m.maxTokens }}</b></div>
          <div><span>上下文</span><b>{{ m.contextLen.toLocaleString() }}</b></div>
        </div>
        <div v-else class="mc-note">
          重排服务 · 无需生成参数，填入 API Key 即可在知识库检索中启用重排
        </div>
        <div class="mc-foot">
          <span class="mono muted">{{ m.updatedAt }}</span>
          <div class="mc-actions">
            <button class="btn btn-xs btn-ghost">编辑</button>
            <button class="btn btn-xs btn-ghost">健康检查</button>
          </div>
        </div>
      </div>
    </div>

    <Modal v-if="showCreate" title="接入模型" size="lg" @close="showCreate = false">
      <div class="row2">
        <div>
          <label class="field-label">模型名称</label>
          <input v-model="form.name" class="field" placeholder="例如：qwen-max" />
        </div>
        <div>
          <label class="field-label">类型</label>
          <select v-model="form.type" class="field">
            <option value="LLM">LLM</option>
            <option value="EMBEDDING">Embedding</option>
            <option value="RERANKER">Reranker</option>
          </select>
        </div>
      </div>
      <div v-if="!isReranker">
        <div class="row2" style="margin-top:16px">
          <div>
            <label class="field-label">供应商</label>
            <input v-model="form.provider" class="field" placeholder="例如：阿里云百炼" />
          </div>
          <div>
            <label class="field-label">Base URL</label>
            <input v-model="form.baseUrl" class="field" placeholder="https://.../v1" />
          </div>
        </div>
        <div style="margin-top:16px">
          <label class="field-label">API Key</label>
          <input v-model="form.apiKey" type="password" class="field" placeholder="密钥加密存储，不落库明文" />
        </div>
        <div class="row3" style="margin-top:16px">
          <div>
            <label class="field-label">温度</label>
            <input v-model.number="form.temperature" type="number" step="0.1" class="field" />
          </div>
          <div>
            <label class="field-label">Max Tokens</label>
            <input v-model.number="form.maxTokens" type="number" class="field" />
          </div>
          <div>
            <label class="field-label">上下文长度</label>
            <input v-model.number="form.contextLen" type="number" class="field" />
          </div>
        </div>
        <div class="field-hint" style="margin-top:14px">
          Embedding 走 OpenAI 兼容 <code class="mono">/embeddings</code> 接口。
        </div>
      </div>

      <div v-else>
        <div class="field-label" style="margin-top:16px">选择主流 Reranker 提供商</div>
        <div class="prov-grid">
          <button
            v-for="p in rerankerProviders"
            :key="p.key"
            class="prov"
            :class="{ on: form.provider === p.name }"
            @click="pickRerankerProvider(p)"
          >
            <div class="prov-name">{{ p.name }}</div>
            <div class="prov-model mono">{{ p.model || "自定义模型" }}</div>
          </button>
        </div>
        <div class="row2" style="margin-top:16px">
          <div>
            <label class="field-label">模型名称</label>
            <input v-model="form.name" class="field" placeholder="如 BAAI/bge-reranker-v2-m3" />
          </div>
          <div>
            <label class="field-label">Base URL</label>
            <input v-model="form.baseUrl" class="field mono" placeholder="https://.../v1/rerank" />
          </div>
        </div>
        <div style="margin-top:16px">
          <label class="field-label">
            API Key
            <span class="muted" style="font-weight:400">· {{ rerankerProviders.find((p) => p.name === form.provider)?.keyHint || "填入对应服务密钥" }}</span>
          </label>
          <input v-model="form.apiKey" type="password" class="field" placeholder="填入 Key 后保存即可使用" />
        </div>
        <div class="field-hint" style="margin-top:14px">
          平台不内置 Reranker，接入后知识库检索即可启用重排。支持兼容 <code class="mono">/rerank</code> 接口的任意服务。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" @click="create">接入</button>
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
.model-card {
  padding: 18px;
  transition: all 0.18s;
}
.model-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
}
.mc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mc-name {
  font-size: 17px;
  font-weight: 800;
  margin-top: 14px;
}
.mc-provider {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 2px;
}
.mc-url {
  font-size: 11.5px;
  color: var(--text-3);
  background: var(--surface-2);
  border-radius: 8px;
  padding: 7px 10px;
  margin-top: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-params {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.mc-params div {
  flex: 1;
  background: var(--surface-2);
  border-radius: 8px;
  padding: 8px 10px;
}
.mc-params span {
  font-size: 11px;
  color: var(--text-3);
  display: block;
}
.mc-params b {
  font-size: 14px;
  font-weight: 700;
}
.mc-note {
  margin-top: 14px;
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--amber-soft);
  border: 1px solid #f3d9a8;
  border-radius: 8px;
  padding: 10px 12px;
  line-height: 1.5;
}
.prov-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.prov {
  text-align: left;
  border: 1px solid var(--border);
  background: var(--surface-2);
  border-radius: 10px;
  padding: 11px 13px;
  transition: all 0.15s;
}
.prov:hover {
  border-color: var(--brand);
  background: var(--brand-soft-2);
}
.prov.on {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: 0 0 0 2px var(--brand-soft);
}
.prov-name {
  font-size: 13.5px;
  font-weight: 700;
}
.prov-model {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  font-size: 12px;
}
.mc-actions {
  display: flex;
  gap: 6px;
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.row3 {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 14px;
}
</style>
