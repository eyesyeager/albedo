<script setup lang="ts">
import { ref, computed } from "vue";
import { useRouter } from "vue-router";
import { knowledgeBases, models } from "@/data/mock";
import { kbUsage } from "@/store/usage";
import type { KnowledgeBase } from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const router = useRouter();
const list = ref<KnowledgeBase[]>([...knowledgeBases]);
const showCreate = ref(false);
const delTarget = ref<KnowledgeBase | null>(null);
const blockTarget = ref<KnowledgeBase | null>(null);
const blockUsage = computed(() => (blockTarget.value ? kbUsage(blockTarget.value) : []));

// 被应用挂载时不允许删除
function usageCount(kb: KnowledgeBase) {
  return kbUsage(kb).length;
}
function requestDelete(kb: KnowledgeBase) {
  if (usageCount(kb) > 0) {
    blockTarget.value = kb;
    return;
  }
  delTarget.value = kb;
}
const onlineRerankers = computed(() =>
  models.filter((m) => m.type === "RERANKER" && m.status === "online").map((m) => m.name)
);
const form = ref({
  name: "",
  desc: "",
  embedding: "bge-m3",
  reranker: onlineRerankers.value[0] || "",
  mode: "hybrid" as const,
  topK: 5,
});

// 同步移除 mock 源数组，使详情/应用装配等读取同步生效
function removeFromSource(id: string) {
  const i = knowledgeBases.findIndex((k) => k.id === id);
  if (i > -1) knowledgeBases.splice(i, 1);
}
function confirmDelete() {
  if (!delTarget.value) return;
  const id = delTarget.value.id;
  removeFromSource(id);
  list.value = list.value.filter((k) => k.id !== id);
  delTarget.value = null;
}
function create() {
  if (!form.value.name) return;
  list.value.unshift({
    id: "kb-" + Date.now(),
    name: form.value.name,
    desc: form.value.desc || "（暂无描述）",
    embeddingModel: form.value.embedding,
    reranker: form.value.reranker,
    retrieveMode: form.value.mode,
    topK: form.value.topK,
    docCount: 0,
    charCount: 0,
    status: "ready",
    updatedAt: "刚刚",
  });
  showCreate.value = false;
  form.value = {
    name: "",
    desc: "",
    embedding: "bge-m3",
    reranker: onlineRerankers.value[0] || "",
    mode: "hybrid",
    topK: 5,
  };
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">知识库管理</h1>
        <p class="sub">企业级文档理解与检索，多库联合检索为内置能力，支持来源溯源</p>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">+ 新建知识库</button>
    </div>

    <div class="grid">
      <div
        v-for="kb in list"
        :key="kb.id"
        class="kb-card card"
        @click="router.push('/knowledge/' + kb.id)"
      >
        <div class="kb-top">
          <div class="kb-ico">📚</div>
          <StatusTag
            :color="kb.status === 'ready' ? 'var(--green)' : 'var(--amber)'"
            :bg="kb.status === 'ready' ? 'var(--green-soft)' : 'var(--amber-soft)'"
            dot
          >{{ kb.status === "ready" ? "索引就绪" : "索引中" }}</StatusTag>
        </div>
        <div class="kb-name">{{ kb.name }}</div>
        <div class="kb-desc">{{ kb.desc }}</div>
        <div class="kb-stats">
          <div><b>{{ kb.docCount }}</b><span>文档</span></div>
          <div><b>{{ (kb.charCount / 10000).toFixed(1) }}w</b><span>字符</span></div>
          <div><b>{{ kb.topK }}</b><span>召回</span></div>
        </div>
        <div class="kb-foot">
          <span class="tag" :class="kb.retrieveMode === 'hybrid' ? 't-hy' : 't-vec'">
            {{ kb.retrieveMode === "hybrid" ? "混合检索" : "向量检索" }}
          </span>
          <span class="mono muted">{{ kb.embeddingModel }}</span>
        </div>
        <div class="kb-actions">
          <span v-if="usageCount(kb)" class="used-hint">被 {{ usageCount(kb) }} 个应用使用</span>
          <button class="btn btn-xs btn-ghost danger" @click.stop="requestDelete(kb)">删除</button>
        </div>
      </div>
    </div>

    <Modal v-if="showCreate" title="新建知识库" @close="showCreate = false">
      <div style="margin-bottom:16px">
        <label class="field-label">知识库名称</label>
        <input v-model="form.name" class="field" placeholder="例如：员工手册知识库" />
      </div>
      <div style="margin-bottom:16px">
        <label class="field-label">描述</label>
        <textarea v-model="form.desc" class="field" rows="2" />
      </div>
      <div class="row2">
        <div>
          <label class="field-label">Embedding 模型</label>
          <select v-model="form.embedding" class="field">
            <option>bge-m3</option>
            <option>text-embedding-3-large</option>
          </select>
        </div>
        <div>
          <label class="field-label">Reranker 重排</label>
          <select v-model="form.reranker" class="field">
            <option v-for="r in onlineRerankers" :key="r" :value="r">{{ r }}</option>
            <option value="">不使用重排（仅向量检索）</option>
          </select>
        </div>
      </div>
      <div class="row2" style="margin-top:16px">
        <div>
          <label class="field-label">检索方式</label>
          <select v-model="form.mode" class="field">
            <option value="hybrid">混合检索</option>
            <option value="vector">向量检索</option>
          </select>
        </div>
        <div>
          <label class="field-label">召回数量 Top-K</label>
          <input v-model.number="form.topK" type="number" class="field" />
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" @click="create">创建</button>
      </template>
    </Modal>

    <!-- 删除确认 -->
    <Modal v-if="delTarget" title="删除知识库" @close="delTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除知识库「<b>{{ delTarget.name }}</b>」？<br />
          将级联删除其下 <b>{{ delTarget.docCount }}</b> 篇文档、切片与向量索引，并从已挂载的应用中解绑。此操作不可恢复。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="delTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDelete">确认删除</button>
      </template>
    </Modal>

    <!-- 被占用，禁止删除 -->
    <Modal v-if="blockTarget" title="无法删除知识库" @close="blockTarget = null">
      <div class="del-body">
        <div class="del-ico">🚫</div>
        <div>
          知识库「<b>{{ blockTarget.name }}</b>」正被以下应用挂载，需先在应用「工具装配」中解绑后再删除：
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
.kb-card {
  padding: 18px;
  cursor: pointer;
  transition: all 0.18s;
}
.kb-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
  border-color: var(--brand-soft);
}
.kb-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kb-ico {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--amber-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.kb-name {
  font-size: 16px;
  font-weight: 800;
  margin-top: 14px;
}
.kb-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 4px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kb-stats {
  display: flex;
  gap: 8px;
  margin: 16px 0;
}
.kb-stats div {
  flex: 1;
  background: var(--surface-2);
  border-radius: 9px;
  padding: 9px;
  text-align: center;
}
.kb-stats b {
  font-size: 17px;
  font-weight: 800;
  display: block;
}
.kb-stats span {
  font-size: 11px;
  color: var(--text-3);
}
.kb-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}
.t-hy {
  color: var(--teal);
  background: var(--teal-soft);
}
.t-vec {
  color: var(--blue);
  background: var(--blue-soft);
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.kb-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 12px;
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
