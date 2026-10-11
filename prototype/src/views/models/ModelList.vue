<script setup lang="ts">
import { ref, computed } from "vue";
import { models, rerankerProviders } from "@/data/mock";
import type {
  HealthCheckResult,
  Model,
  ModelType,
  RerankerProvider,
} from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const list = ref<Model[]>([...models]);
const filter = ref<"ALL" | ModelType>("ALL");
const showCreate = ref(false);
const delTarget = ref<Model | null>(null);
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
/* ---------------- 健康检查 ---------------- */
const healthTarget = ref<Model | null>(null);
const healthRunning = ref(false);
const healthResult = ref<HealthCheckResult | null>(null);
const lastChecks = ref<Record<string, { ok: boolean; latencyMs: number; at: string }>>({});

// 由 id 派生的稳定伪延迟，保证演示/截图可复现
function pseudoLatency(id: string, base: number) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 991;
  return base + (h % 380);
}

function buildRequestBody(m: Model) {
  if (m.type === "LLM") {
    return JSON.stringify(
      {
        model: m.name,
        messages: [{ role: "user", content: "健康检查：请仅回复 OK" }],
        temperature: m.temperature,
        max_tokens: 16,
      },
      null,
      2
    );
  }
  if (m.type === "EMBEDDING") {
    return JSON.stringify({ model: m.name, input: "健康检查：OK" }, null, 2);
  }
  return JSON.stringify(
    { model: m.name, query: "健康检查", documents: ["OK", "健康检查"], top_n: 2 },
    null,
    2
  );
}

function buildRequest(m: Model) {
  const url =
    m.type === "LLM"
      ? `${m.baseUrl}/chat/completions`
      : m.type === "EMBEDDING"
      ? `${m.baseUrl}/embeddings`
      : m.baseUrl;
  return { method: "POST", url, body: buildRequestBody(m) };
}

function buildCheck(m: Model): HealthCheckResult {
  const latency = pseudoLatency(m.id, m.type === "LLM" ? 520 : 260);
  const request = buildRequest(m);
  const notConfigured = m.apiKeyMasked === "—" || m.updatedAt === "未接入";

  let ok = true;
  let status = "200 OK";
  let summary = "";
  let respBody = "";

  if (m.type === "LLM") {
    summary = "模型可正常对话，返回 finish_reason=stop";
    respBody = JSON.stringify(
      {
        id: `chatcmpl-hc-${m.id}`,
        object: "chat.completion",
        model: m.name,
        choices: [
          { index: 0, message: { role: "assistant", content: "OK" }, finish_reason: "stop" },
        ],
        usage: { prompt_tokens: 18, completion_tokens: 2, total_tokens: 20 },
      },
      null,
      2
    );
  } else if (m.type === "EMBEDDING") {
    summary = "向量化成功，返回 1024 维向量";
    respBody = JSON.stringify(
      {
        object: "list",
        model: m.name,
        data: [
          {
            object: "embedding",
            index: 0,
            embedding: [0.0121, -0.0456, 0.0783, "… 共 1024 维"],
          },
        ],
        usage: { prompt_tokens: 6, total_tokens: 6 },
      },
      null,
      2
    );
  } else {
    summary = "重排服务可用，返回 2 条相关性评分";
    respBody = JSON.stringify(
      {
        results: [
          { index: 0, document: { text: "OK" }, relevance_score: 0.9215 },
          { index: 1, document: { text: "健康检查" }, relevance_score: 0.6107 },
        ],
      },
      null,
      2
    );
  }

  if (m.status === "offline") {
    ok = false;
    status = notConfigured ? "N/A" : "401 Unauthorized";
    summary = notConfigured ? "未配置 API Key，模型尚未接入" : "密钥校验失败，请重新填写 API Key";
    respBody = JSON.stringify(
      {
        error: {
          message: notConfigured ? "No API key provided" : "Incorrect API key provided",
          type: "authentication_error",
        },
      },
      null,
      2
    );
  }

  return {
    modelId: m.id,
    modelName: m.name,
    type: m.type,
    ok,
    latencyMs: ok ? latency : pseudoLatency(m.id, 80),
    request,
    response: { status, latencyMs: ok ? latency : pseudoLatency(m.id, 80), body: respBody },
    summary,
  };
}

const healthRequest = computed(() => (healthTarget.value ? buildRequest(healthTarget.value) : null));

function isChecking(m: Model) {
  return healthRunning.value && healthTarget.value?.id === m.id;
}
function checkHealth(m: Model) {
  if (healthRunning.value) return;
  healthTarget.value = m;
  healthRunning.value = true;
  healthResult.value = null;
  setTimeout(() => {
    const r = buildCheck(m);
    healthResult.value = r;
    lastChecks.value = {
      ...lastChecks.value,
      [m.id]: {
        ok: r.ok,
        latencyMs: r.response.latencyMs,
        at: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
      },
    };
    healthRunning.value = false;
  }, pseudoLatency(m.id, 780));
}
function recheck() {
  if (healthTarget.value) checkHealth(healthTarget.value);
}
function closeHealth() {
  if (healthRunning.value) return;
  healthTarget.value = null;
  healthResult.value = null;
}

// 同步移除 mock 源数组，使其它页面（如应用/知识库绑定下拉）同步生效
function removeFromSource(id: string) {
  const i = models.findIndex((m) => m.id === id);
  if (i > -1) models.splice(i, 1);
}
function confirmDelete() {
  if (!delTarget.value) return;
  const id = delTarget.value.id;
  removeFromSource(id);
  list.value = list.value.filter((m) => m.id !== id);
  delTarget.value = null;
}
function inUseBy(m: Model) {
  return m.type === "LLM"
    ? "应用配置与「智能模式」路由"
    : m.type === "EMBEDDING"
    ? "知识库向量化"
    : "知识库检索重排";
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
            <button class="btn btn-xs btn-ghost" :disabled="isChecking(m)" @click="checkHealth(m)">
              {{ isChecking(m) ? "检查中…" : "健康检查" }}
            </button>
            <button class="btn btn-xs btn-ghost danger" @click.stop="delTarget = m">删除</button>
          </div>
        </div>
        <div
          v-if="lastChecks[m.id]"
          class="hc-row"
          :class="lastChecks[m.id].ok ? 'ok' : 'bad'"
        >
          <span class="hc-dot" />
          {{ lastChecks[m.id].ok ? "健康检查通过" : "健康检查失败" }}
          <span class="mono">{{ lastChecks[m.id].latencyMs }}ms</span>
          <span class="muted">· {{ lastChecks[m.id].at }}</span>
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

    <!-- 健康检查：发送请求并展示模型响应 -->
    <Modal v-if="healthTarget" :title="`健康检查 · ${healthTarget.name}`" size="lg" @close="closeHealth">
      <div class="hc-status" :class="healthRunning ? 'run' : healthResult?.ok ? 'ok' : 'bad'">
        <span class="hc-dot" />
        <span>
          {{
            healthRunning
              ? "正在向模型发送健康检查请求…"
              : healthResult?.ok
              ? "健康检查通过"
              : "健康检查失败"
          }}
        </span>
      </div>

      <div class="hc-meta">
        <span class="tag" :style="{ color: typeMeta[healthTarget.type].color, background: typeMeta[healthTarget.type].bg }">
          {{ typeMeta[healthTarget.type].label }}
        </span>
        <span class="chip">{{ healthTarget.provider }}</span>
        <span class="mono muted">延迟 {{ healthResult ? healthResult.response.latencyMs + "ms" : "—" }}</span>
      </div>

      <div class="hc-sec">请求</div>
      <div class="hc-line">
        <span class="hc-k">Endpoint</span>
        <code class="mono hc-url">{{ healthRequest?.method }} {{ healthRequest?.url }}</code>
      </div>
      <pre class="code mono">{{ healthRequest?.body }}</pre>

      <div class="hc-sec">模型响应</div>
      <div v-if="!healthRunning && healthResult" class="hc-line">
        <span class="hc-k">HTTP 状态</span>
        <span class="hc-st" :class="healthResult.ok ? 'ok' : 'bad'">{{ healthResult.response.status }}</span>
        <span class="muted" style="font-size:12.5px">{{ healthResult.summary }}</span>
      </div>
      <pre v-if="!healthRunning && healthResult" class="code mono">{{ healthResult.response.body }}</pre>

      <div v-if="healthRunning" class="hc-loading">
        <span class="hc-spinner" />
        等待模型返回（模拟请求，最长 3s 超时）…
      </div>

      <template #foot>
        <button class="btn" :disabled="healthRunning" @click="closeHealth">
          {{ healthRunning ? "检查中…" : "关闭" }}
        </button>
        <button class="btn btn-primary" :disabled="healthRunning" @click="recheck">重新检查</button>
      </template>
    </Modal>

    <!-- 删除确认 -->
    <Modal v-if="delTarget" title="删除模型" @close="delTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除模型「<b>{{ delTarget.name }}</b>」（{{ typeMeta[delTarget.type].label }} ·
          {{ delTarget.provider }}）？<br />
          该模型用于{{ inUseBy(delTarget) }}，删除后相关配置需重新指定模型。此操作不可恢复。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="delTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDelete">确认删除</button>
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
.danger:hover {
  color: var(--red);
  border-color: var(--red);
  background: var(--red-soft);
}

/* 健康检查 */
.hc-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  border-radius: 8px;
  padding: 5px 10px;
}
.hc-row.ok {
  color: var(--green);
  background: var(--green-soft);
}
.hc-row.bad {
  color: var(--red);
  background: var(--red-soft);
}
.hc-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex: none;
  background: currentColor;
}
.hc-status {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  font-weight: 700;
  padding: 11px 14px;
  border-radius: 10px;
  margin-bottom: 14px;
}
.hc-status.ok {
  color: var(--green);
  background: var(--green-soft);
}
.hc-status.bad {
  color: var(--red);
  background: var(--red-soft);
}
.hc-status.run {
  color: var(--brand-700);
  background: var(--brand-soft);
}
.hc-status.run .hc-dot {
  animation: hc-pulse 1s ease-in-out infinite;
}
@keyframes hc-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
.hc-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  margin-bottom: 16px;
}
.hc-sec {
  font-size: 13.5px;
  font-weight: 700;
  margin: 16px 0 8px;
}
.hc-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.hc-k {
  color: var(--text-3);
  flex: none;
}
.hc-url {
  font-size: 12.5px;
  word-break: break-all;
}
.hc-st {
  font-weight: 700;
  font-size: 12.5px;
  border-radius: 6px;
  padding: 2px 8px;
}
.hc-st.ok {
  color: var(--green);
  background: var(--green-soft);
}
.hc-st.bad {
  color: var(--red);
  background: var(--red-soft);
}
.hc-loading {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--surface-2);
  border-radius: 10px;
  padding: 16px;
}
.hc-spinner {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  border: 2px solid var(--brand-soft);
  border-top-color: var(--brand);
  flex: none;
  animation: hc-spin 0.8s linear infinite;
}
@keyframes hc-spin {
  to {
    transform: rotate(360deg);
  }
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
