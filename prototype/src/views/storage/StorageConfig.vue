<script setup lang="ts">
import { ref, computed } from "vue";
import {
  vectorStoreProviders,
  vectorStores,
  elasticsearchConfigs,
  knowledgeBases,
} from "@/data/mock";
import type {
  ElasticsearchConfig,
  VectorStoreConfig,
  VectorStoreType,
} from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const tab = ref<"vector" | "es">("vector");
const vsList = ref<VectorStoreConfig[]>([...vectorStores]);
const esList = ref<ElasticsearchConfig[]>([...elasticsearchConfigs]);

const vsTypeMeta: Record<VectorStoreType, { label: string; color: string; bg: string }> = {
  MILVUS: { label: "Milvus", color: "#0d9488", bg: "#e2f6f3" },
  QDRANT: { label: "Qdrant", color: "#7c3aed", bg: "#f3ecfe" },
  PGVECTOR: { label: "pgvector", color: "#2563eb", bg: "#e8f0fe" },
  ELASTICSEARCH: { label: "Elasticsearch", color: "#d97706", bg: "#fdf3e3" },
  CUSTOM: { label: "自定义", color: "#8a8da3", bg: "#f1f2f6" },
};

type ConnStatus = VectorStoreConfig["status"];
function statusMeta(s: ConnStatus) {
  return {
    connected: { color: "var(--green)", bg: "var(--green-soft)", text: "已连接" },
    error: { color: "var(--red)", bg: "var(--red-soft)", text: "连接异常" },
    untested: { color: "var(--text-3)", bg: "var(--surface-2)", text: "未测试" },
  }[s];
}

/* ---------- 向量库占用校验（被知识库引用时禁止删除） ---------- */
function vsUsage(name: string) {
  return knowledgeBases.filter((k) => k.vectorStore === name);
}
function vsUsageCount(name: string) {
  return vsUsage(name).length;
}

/* ---------- 连通性测试 ---------- */
const testState = ref<Record<string, { checking: boolean; ok?: boolean; latencyMs?: number; at?: string }>>({});
function pseudoLatency(id: string) {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) % 991;
  return 280 + (h % 420);
}
function testVector(vs: VectorStoreConfig) {
  if (testState.value[vs.id]?.checking) return;
  testState.value[vs.id] = { checking: true };
  setTimeout(() => {
    const ok = !!vs.host && vs.port > 0;
    testState.value[vs.id] = {
      checking: false,
      ok,
      latencyMs: pseudoLatency(vs.id),
      at: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
    };
    vs.status = ok ? "connected" : "error";
  }, pseudoLatency(vs.id));
}
function testEs(es: ElasticsearchConfig) {
  if (testState.value[es.id]?.checking) return;
  testState.value[es.id] = { checking: true };
  setTimeout(() => {
    const ok = !!es.endpoint;
    testState.value[es.id] = {
      checking: false,
      ok,
      latencyMs: pseudoLatency(es.id),
      at: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
    };
    es.status = ok ? "connected" : "error";
  }, pseudoLatency(es.id));
}

/* ---------- 接入向量数据库 ---------- */
const showVector = ref(false);
const vectorForm = ref({
  name: "",
  providerKey: "milvus",
  host: "",
  port: 19530,
  database: "albedo",
  collection: "kb_chunks",
  username: "",
  password: "",
  dim: 1024,
});
const providerOf = computed(() =>
  vectorStoreProviders.find((p) => p.key === vectorForm.value.providerKey)!
);
function pickVectorProvider(key: string) {
  const p = vectorStoreProviders.find((x) => x.key === key)!;
  vectorForm.value.providerKey = key;
  vectorForm.value.host = p.defaultHost;
  vectorForm.value.port = p.defaultPort;
}
function createVector() {
  if (!vectorForm.value.name) return;
  const p = providerOf.value;
  vsList.value.unshift({
    id: "vs-" + Date.now(),
    name: vectorForm.value.name,
    type: p.type,
    host: vectorForm.value.host,
    port: vectorForm.value.port,
    database: vectorForm.value.database,
    collection: vectorForm.value.collection,
    authMasked: vectorForm.value.username
      ? vectorForm.value.username + ":****"
      : "—",
    dim: vectorForm.value.dim,
    status: "untested",
    updatedAt: "刚刚",
  });
  // 同步写入 mock 源数组，供知识库创建绑定下拉同步生效
  vectorStores.unshift(vsList.value[0]);
  showVector.value = false;
  vectorForm.value = {
    name: "",
    providerKey: "milvus",
    host: "",
    port: 19530,
    database: "albedo",
    collection: "kb_chunks",
    username: "",
    password: "",
    dim: 1024,
  };
}

/* ---------- 接入 Elasticsearch ---------- */
const showEs = ref(false);
const esForm = ref({
  name: "",
  endpoint: "",
  username: "",
  password: "",
  indexPrefix: "albedo_kb",
});
function createEs() {
  if (!esForm.value.name) return;
  esList.value.unshift({
    id: "es-" + Date.now(),
    name: esForm.value.name,
    endpoint: esForm.value.endpoint,
    username: esForm.value.username || "—",
    authMasked: esForm.value.password ? "****" : "—",
    indexPrefix: esForm.value.indexPrefix,
    status: "untested",
    updatedAt: "刚刚",
  });
  elasticsearchConfigs.unshift(esList.value[0]);
  showEs.value = false;
  esForm.value = { name: "", endpoint: "", username: "", password: "", indexPrefix: "albedo_kb" };
}

/* ---------- 删除 ---------- */
const vsDelTarget = ref<VectorStoreConfig | null>(null);
const vsBlockTarget = ref<VectorStoreConfig | null>(null);
const esDelTarget = ref<ElasticsearchConfig | null>(null);

function requestDeleteVector(vs: VectorStoreConfig) {
  if (vsUsageCount(vs.name) > 0) {
    vsBlockTarget.value = vs;
    return;
  }
  vsDelTarget.value = vs;
}
function confirmDeleteVector() {
  if (!vsDelTarget.value) return;
  const id = vsDelTarget.value.id;
  const si = vectorStores.findIndex((v) => v.id === id);
  if (si > -1) vectorStores.splice(si, 1);
  vsList.value = vsList.value.filter((v) => v.id !== id);
  vsDelTarget.value = null;
}
function requestDeleteEs(es: ElasticsearchConfig) {
  esDelTarget.value = es;
}
function confirmDeleteEs() {
  if (!esDelTarget.value) return;
  const id = esDelTarget.value.id;
  const si = elasticsearchConfigs.findIndex((v) => v.id === id);
  if (si > -1) elasticsearchConfigs.splice(si, 1);
  esList.value = esList.value.filter((v) => v.id !== id);
  esDelTarget.value = null;
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">检索引擎配置</h1>
        <p class="sub">向量数据库与 Elasticsearch 连接信息维护 · 平台不内置实例，由用户自配置</p>
      </div>
    </div>

    <div class="notice">
      <div class="notice-ico">ℹ️</div>
      <div>
        知识库仅提供检索技术支持，<b>不包含真实的向量数据库与 Elasticsearch 实例</b>。
        请在此接入你的向量数据库（预置主流向量库）与 Elasticsearch 连接信息，
        完成连通性测试后即可在创建知识库时绑定使用。
      </div>
    </div>

    <div class="tabs-wrap card">
      <div class="tabs">
        <button :class="{ on: tab === 'vector' }" @click="tab = 'vector'">向量数据库</button>
        <button :class="{ on: tab === 'es' }" @click="tab = 'es'">Elasticsearch</button>
      </div>

      <!-- 向量数据库 -->
      <div v-show="tab === 'vector'" class="tab-body">
        <div class="tab-head">
          <div class="tab-desc">用于存储切片向量，支持向量检索；预置 Milvus / Qdrant / pgvector / Elasticsearch。</div>
          <button class="btn btn-primary" @click="showVector = true">+ 接入向量数据库</button>
        </div>
        <div class="grid">
          <div v-for="vs in vsList" :key="vs.id" class="card item">
            <div class="it-top">
              <span class="tag" :style="{ color: vsTypeMeta[vs.type].color, background: vsTypeMeta[vs.type].bg }">
                {{ vsTypeMeta[vs.type].label }}
              </span>
              <StatusTag :color="statusMeta(vs.status).color" :bg="statusMeta(vs.status).bg" dot>
                {{ statusMeta(vs.status).text }}
              </StatusTag>
            </div>
            <div class="it-name">{{ vs.name }}</div>
            <div class="it-line mono">{{ vs.host || "（未填写地址）" }}:{{ vs.port || "—" }}</div>
            <div class="it-meta">
              <div><span>库 / 集合</span><b class="mono">{{ vs.database }} / {{ vs.collection }}</b></div>
              <div><span>鉴权</span><b class="mono">{{ vs.authMasked }}</b></div>
              <div><span>向量维度</span><b class="mono">{{ vs.dim }}</b></div>
            </div>
            <div v-if="testState[vs.id]" class="test-row" :class="testState[vs.id].ok ? 'ok' : 'bad'">
              <span class="dot" />
              {{ testState[vs.id].checking ? "检测中…" : testState[vs.id].ok ? "连通性测试通过" : "连通性测试失败" }}
              <template v-if="!testState[vs.id].checking">
                <span class="mono">{{ testState[vs.id].latencyMs }}ms</span>
                <span class="muted">· {{ testState[vs.id].at }}</span>
              </template>
            </div>
            <div class="it-foot">
              <span class="mono muted">{{ vs.updatedAt }}</span>
              <div class="it-actions">
                <button class="btn btn-xs btn-ghost" :disabled="testState[vs.id]?.checking" @click="testVector(vs)">
                  {{ testState[vs.id]?.checking ? "检测中…" : "测试连接" }}
                </button>
                <button class="btn btn-xs btn-ghost danger" @click="requestDeleteVector(vs)">删除</button>
              </div>
            </div>
            <div v-if="vsUsageCount(vs.name)" class="used-hint">被 {{ vsUsageCount(vs.name) }} 个知识库使用</div>
          </div>
        </div>
      </div>

      <!-- Elasticsearch -->
      <div v-show="tab === 'es'" class="tab-body">
        <div class="tab-head">
          <div class="tab-desc">混合检索的关键词（BM25）召回依赖 Elasticsearch；配置后即可在知识库启用混合检索。</div>
          <button class="btn btn-primary" @click="showEs = true">+ 接入 Elasticsearch</button>
        </div>
        <div class="grid">
          <div v-for="es in esList" :key="es.id" class="card item">
            <div class="it-top">
              <span class="tag" style="color:#d97706;background:#fdf3e3">Elasticsearch</span>
              <StatusTag :color="statusMeta(es.status).color" :bg="statusMeta(es.status).bg" dot>
                {{ statusMeta(es.status).text }}
              </StatusTag>
            </div>
            <div class="it-name">{{ es.name }}</div>
            <div class="it-line mono">{{ es.endpoint || "（未填写 Endpoint）" }}</div>
            <div class="it-meta">
              <div><span>用户名</span><b class="mono">{{ es.username }}</b></div>
              <div><span>索引前缀</span><b class="mono">{{ es.indexPrefix }}</b></div>
              <div><span>密码</span><b class="mono">{{ es.authMasked }}</b></div>
            </div>
            <div v-if="testState[es.id]" class="test-row" :class="testState[es.id].ok ? 'ok' : 'bad'">
              <span class="dot" />
              {{ testState[es.id].checking ? "检测中…" : testState[es.id].ok ? "连通性测试通过" : "连通性测试失败" }}
              <template v-if="!testState[es.id].checking">
                <span class="mono">{{ testState[es.id].latencyMs }}ms</span>
                <span class="muted">· {{ testState[es.id].at }}</span>
              </template>
            </div>
            <div class="it-foot">
              <span class="mono muted">{{ es.updatedAt }}</span>
              <div class="it-actions">
                <button class="btn btn-xs btn-ghost" :disabled="testState[es.id]?.checking" @click="testEs(es)">
                  {{ testState[es.id]?.checking ? "检测中…" : "测试连接" }}
                </button>
                <button class="btn btn-xs btn-ghost danger" @click="requestDeleteEs(es)">删除</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 接入向量数据库 -->
    <Modal v-if="showVector" title="接入向量数据库" size="lg" @close="showVector = false">
      <div class="field-label">选择向量库类型（预置）</div>
      <div class="prov-grid">
        <button
          v-for="p in vectorStoreProviders"
          :key="p.key"
          class="prov"
          :class="{ on: vectorForm.providerKey === p.key }"
          @click="pickVectorProvider(p.key)"
        >
          <div class="prov-name">{{ p.name }}</div>
          <div class="prov-model">{{ p.desc }}</div>
        </button>
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">配置名称</label>
          <input v-model="vectorForm.name" class="field" placeholder="例如：生产向量库" />
        </div>
        <div>
          <label class="field-label">向量维度</label>
          <input v-model.number="vectorForm.dim" type="number" class="field" />
        </div>
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">地址 Host</label>
          <input v-model="vectorForm.host" class="field mono" :placeholder="providerOf.defaultHost || '127.0.0.1'" />
        </div>
        <div>
          <label class="field-label">端口 Port</label>
          <input v-model.number="vectorForm.port" type="number" class="field" />
        </div>
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">库名 Database</label>
          <input v-model="vectorForm.database" class="field mono" />
        </div>
        <div>
          <label class="field-label">集合 Collection</label>
          <input v-model="vectorForm.collection" class="field mono" />
        </div>
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">用户名</label>
          <input v-model="vectorForm.username" class="field" />
        </div>
        <div>
          <label class="field-label">密码</label>
          <input v-model="vectorForm.password" type="password" class="field" placeholder="密钥加密存储，不落库明文" />
        </div>
      </div>
      <div class="field-hint" style="margin-top:14px">
        平台不内置向量库实例，连接信息加密保存；保存后可在卡片上执行连通性测试。
      </div>
      <template #foot>
        <button class="btn" @click="showVector = false">取消</button>
        <button class="btn btn-primary" @click="createVector">接入</button>
      </template>
    </Modal>

    <!-- 接入 Elasticsearch -->
    <Modal v-if="showEs" title="接入 Elasticsearch" @close="showEs = false">
      <div>
        <label class="field-label">配置名称</label>
        <input v-model="esForm.name" class="field" placeholder="例如：生产 ES 集群" />
      </div>
      <div style="margin-top:16px">
        <label class="field-label">Endpoint</label>
        <input v-model="esForm.endpoint" class="field mono" placeholder="https://es.example.com:9200" />
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">用户名</label>
          <input v-model="esForm.username" class="field" placeholder="elastic" />
        </div>
        <div>
          <label class="field-label">密码</label>
          <input v-model="esForm.password" type="password" class="field" placeholder="密钥加密存储" />
        </div>
      </div>
      <div style="margin-top:16px">
        <label class="field-label">索引前缀</label>
        <input v-model="esForm.indexPrefix" class="field mono" placeholder="albedo_kb" />
      </div>
      <div class="field-hint" style="margin-top:14px">
        用于混合检索的关键词（BM25）召回；未配置 ES 时知识库仅能使用纯向量检索。
      </div>
      <template #foot>
        <button class="btn" @click="showEs = false">取消</button>
        <button class="btn btn-primary" @click="createEs">接入</button>
      </template>
    </Modal>

    <!-- 向量库删除确认 -->
    <Modal v-if="vsDelTarget" title="删除向量数据库" @close="vsDelTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除向量数据库「<b>{{ vsDelTarget.name }}</b>」（{{ vsTypeMeta[vsDelTarget.type].label }}）？
          删除后，绑定该向量库的知识库将无法执行向量检索。此操作不可恢复。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="vsDelTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDeleteVector">确认删除</button>
      </template>
    </Modal>

    <!-- 向量库被占用，禁止删除 -->
    <Modal v-if="vsBlockTarget" title="无法删除向量数据库" @close="vsBlockTarget = null">
      <div class="del-body">
        <div class="del-ico">🚫</div>
        <div>
          向量数据库「<b>{{ vsBlockTarget.name }}</b>」正被以下知识库使用，需先在知识库中改绑其他向量库后再删除：
          <div class="used-apps">
            <span v-for="k in vsUsage(vsBlockTarget.name)" :key="k.id" class="used-app">{{ k.name }}</span>
          </div>
        </div>
      </div>
      <template #foot>
        <button class="btn btn-primary" @click="vsBlockTarget = null">我知道了</button>
      </template>
    </Modal>

    <!-- ES 删除确认 -->
    <Modal v-if="esDelTarget" title="删除 Elasticsearch" @close="esDelTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除 Elasticsearch「<b>{{ esDelTarget.name }}</b>」？<br />
          删除后，采用「混合检索」的知识库将无法执行 BM25 关键词召回，需重新配置 ES 或切换为纯向量检索。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="esDelTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDeleteEs">确认删除</button>
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
  margin-bottom: 16px;
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
.notice {
  display: flex;
  gap: 11px;
  align-items: flex-start;
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.6;
  background: var(--amber-soft);
  border: 1px solid #f3d9a8;
  border-radius: 12px;
  padding: 13px 16px;
  margin-bottom: 16px;
}
.notice-ico {
  font-size: 17px;
  flex: none;
  line-height: 1.4;
}
.tabs-wrap {
  padding: 0 4px;
}
.tab-body {
  padding: 20px 22px 24px;
}
.tab-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.tab-desc {
  font-size: 12.5px;
  color: var(--text-3);
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.item {
  padding: 18px;
  transition: all 0.18s;
}
.item:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
  border-color: var(--brand-soft);
}
.it-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.it-name {
  font-size: 16px;
  font-weight: 800;
  margin-top: 12px;
}
.it-line {
  font-size: 11.5px;
  color: var(--text-3);
  background: var(--surface-2);
  border-radius: 8px;
  padding: 7px 10px;
  margin-top: 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.it-meta {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.it-meta div {
  flex: 1;
  min-width: 0;
  background: var(--surface-2);
  border-radius: 8px;
  padding: 8px 10px;
}
.it-meta span {
  font-size: 11px;
  color: var(--text-3);
  display: block;
}
.it-meta b {
  font-size: 12px;
  font-weight: 600;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.test-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  border-radius: 8px;
  padding: 5px 10px;
}
.test-row.ok {
  color: var(--green);
  background: var(--green-soft);
}
.test-row.bad {
  color: var(--red);
  background: var(--red-soft);
}
.test-row .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.it-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 12px;
}
.it-actions {
  display: flex;
  gap: 6px;
}
.used-hint {
  margin-top: 10px;
  font-size: 11.5px;
  color: var(--amber);
  background: var(--amber-soft);
  border-radius: 999px;
  padding: 2px 9px;
  width: fit-content;
}
.prov-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
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
