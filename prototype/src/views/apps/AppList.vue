<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { apps } from "@/data/mock";
import type { AppItem } from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";

const router = useRouter();
const list = ref<AppItem[]>([...apps]);
const showCreate = ref(false);
const form = ref({
  name: "",
  desc: "",
  model: "qwen-max",
});

function toggle(a: AppItem) {
  a.status = a.status === "enabled" ? "disabled" : "enabled";
}
function create() {
  if (!form.value.name) return;
  list.value.unshift({
    id: "app-" + Date.now(),
    name: form.value.name,
    desc: form.value.desc || "（暂无描述）",
    icon: "🤖",
    iconColor: "#6366f1",
    status: "enabled",
    qpm: 60,
    jwtKeyMasked: "jk-****" + Math.random().toString(16).slice(2, 6),
    sessionCount: 0,
    callToday: 0,
    errorRate: 0,
    updatedAt: "刚刚",
    config: {
      model: form.value.model,
      modelMode: "fixed",
      prompt: "你是企业智能体，请基于已挂载的工具与知识库作答。",
      reactRounds: 8,
      shortMemory: 10,
      knowledgeStrategy: "smart",
      tools: [],
    },
    snapshots: [],
  });
  showCreate.value = false;
  form.value = { name: "", desc: "", model: "qwen-max" };
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">应用管理</h1>
        <p class="sub">创建、配置并运营你的智能体应用，发布后通过 AGUI 协议对外提供服务</p>
      </div>
      <button class="btn btn-primary" @click="showCreate = true">
        <svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
        创建应用
      </button>
    </div>

    <div class="grid">
      <div
        v-for="a in list"
        :key="a.id"
        class="app-card card"
        @click="router.push('/apps/' + a.id)"
      >
        <div class="app-top">
          <div class="app-icon" :style="{ background: a.iconColor + '1f', color: a.iconColor }">
            {{ a.icon }}
          </div>
          <div class="app-info">
            <div class="app-name">{{ a.name }}</div>
            <div class="app-desc">{{ a.desc }}</div>
          </div>
          <span
            class="switch"
            :class="{ on: a.status === 'enabled' }"
            @click.stop="toggle(a)"
            :title="a.status === 'enabled' ? '点击停用' : '点击启用'"
          />
        </div>
        <div class="app-meta">
          <StatusTag
            :color="a.status === 'enabled' ? 'var(--green)' : 'var(--text-3)'"
            :bg="a.status === 'enabled' ? 'var(--green-soft)' : 'var(--surface-2)'"
            dot
          >
            {{ a.status === "enabled" ? "已启用" : "已停用" }}
          </StatusTag>
          <span class="chip">模型 {{ a.config.model }}</span>
          <span class="chip">QPM {{ a.qpm }}</span>
        </div>
        <div class="app-stats">
          <div class="st">
            <div class="st-v">{{ a.callToday.toLocaleString() }}</div>
            <div class="st-l">今日调用</div>
          </div>
          <div class="st">
            <div class="st-v">{{ a.sessionCount.toLocaleString() }}</div>
            <div class="st-l">会话数</div>
          </div>
          <div class="st">
            <div class="st-v" :class="a.errorRate > 1.5 ? 'warn' : ''">{{ a.errorRate }}%</div>
            <div class="st-l">错误率</div>
          </div>
        </div>
      </div>
    </div>

    <Modal v-if="showCreate" title="创建应用" @close="showCreate = false">
      <div style="margin-bottom: 18px">
        <label class="field-label">应用名称</label>
        <input v-model="form.name" class="field" placeholder="例如：企业知识助手" />
      </div>
      <div style="margin-bottom: 18px">
        <label class="field-label">应用描述</label>
        <textarea v-model="form.desc" class="field" rows="3" placeholder="简要描述该应用的用途" />
      </div>
      <div>
        <label class="field-label">默认模型</label>
        <select v-model="form.model" class="field">
          <option>qwen-max</option>
          <option>deepseek-v3</option>
          <option>glm-4-plus</option>
        </select>
        <div class="field-hint">创建后可在「智能体配置」中调整模型与提示词。</div>
      </div>
      <template #foot>
        <button class="btn" @click="showCreate = false">取消</button>
        <button class="btn btn-primary" @click="create">创建并配置</button>
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
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.app-card {
  padding: 18px;
  cursor: pointer;
  transition: all 0.18s;
}
.app-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
  border-color: var(--brand-soft);
}
.app-top {
  display: flex;
  align-items: flex-start;
  gap: 13px;
}
.app-icon {
  width: 46px;
  height: 46px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  flex: none;
}
.app-info {
  flex: 1;
  min-width: 0;
}
.app-name {
  font-size: 15.5px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.app-desc {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 4px;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.app-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0;
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
.app-stats {
  display: flex;
  border-top: 1px solid var(--border);
  padding-top: 14px;
  gap: 8px;
}
.st {
  flex: 1;
}
.st-v {
  font-size: 18px;
  font-weight: 800;
}
.st-v.warn {
  color: var(--red);
}
.st-l {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}
</style>
