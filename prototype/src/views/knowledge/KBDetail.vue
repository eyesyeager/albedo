<script setup lang="ts">
import { ref, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { knowledgeBases, sampleDocs, models } from "@/data/mock";
import type { DocStatus } from "@/types";
import StatusTag from "@/components/StatusTag.vue";

const route = useRoute();
const router = useRouter();
const kb = knowledgeBases.find((k) => k.id === route.params.id) || knowledgeBases[0];
const docs = ref([...sampleDocs]);
const tab = ref<"docs" | "retrieve" | "config">("docs");
const onlineRerankers = computed(() =>
  models.filter((m) => m.type === "RERANKER" && m.status === "online").map((m) => m.name)
);
const upload = ref(false);
const query = ref("年假如何计算？");
const testing = ref(false);
const results = ref<any[] | null>(null);

const docStatus = (s: DocStatus) => {
  return {
    success: { c: "var(--green)", b: "var(--green-soft)", t: "解析成功" },
    parsing: { c: "var(--amber)", b: "var(--amber-soft)", t: "解析中" },
    failed: { c: "var(--red)", b: "var(--red-soft)", t: "解析失败" },
    pending: { c: "var(--text-3)", b: "var(--surface-2)", t: "排队中" },
  }[s];
};

function runRetrieve() {
  testing.value = true;
  results.value = null;
  setTimeout(() => {
    results.value = [
      { id: 1, score: 0.94, src: "员工考勤管理制度.pdf", text: "年假按累计工龄计算，入职满 1 年即可享受年休假，每满 1 年增加 1 天，上限 15 天。", tag: "考勤" },
      { id: 2, score: 0.88, src: "薪酬福利说明.docx", text: "年休假期间工资照发，未休年假可按 300% 支付补偿。", tag: "薪酬" },
      { id: 3, score: 0.81, src: "员工考勤管理制度.pdf", text: "申请年假需提前 3 个工作日提交，由直属主管审批。", tag: "考勤" },
    ];
    testing.value = false;
  }, 900);
}
</script>

<template>
  <div class="page">
    <div class="detail-head card">
      <button class="back" @click="router.push('/knowledge')">‹ 知识库</button>
      <div class="dh-ico">📚</div>
      <div class="dh-info">
        <div class="dh-name">{{ kb.name }}</div>
        <div class="dh-desc">{{ kb.desc }}</div>
        <div class="dh-tags">
          <span class="tag t-hy">{{ kb.retrieveMode === "hybrid" ? "混合检索" : "向量检索" }}</span>
          <span class="chip">Embedding · {{ kb.embeddingModel }}</span>
          <span class="chip">Reranker · {{ kb.reranker || "未配置" }}</span>
          <span class="chip">TopK {{ kb.topK }}</span>
        </div>
      </div>
      <button class="btn btn-primary" @click="upload = true">+ 上传文档</button>
    </div>

    <div class="tabs-wrap card">
      <div class="tabs">
        <button :class="{ on: tab === 'docs' }" @click="tab = 'docs'">文档管理</button>
        <button :class="{ on: tab === 'retrieve' }" @click="tab = 'retrieve'">检索测试</button>
        <button :class="{ on: tab === 'config' }" @click="tab = 'config'">切片与检索配置</button>
      </div>

      <!-- docs -->
      <div v-show="tab === 'docs'" class="tab-body">
        <table class="tbl">
          <thead>
            <tr><th>文档</th><th>类型</th><th>大小</th><th>状态</th><th>切片</th><th>标签</th><th>更新时间</th></tr>
          </thead>
          <tbody>
            <tr v-for="d in docs" :key="d.id">
              <td><b>{{ d.name }}</b></td>
              <td><span class="chip sm">{{ d.type }}</span></td>
              <td class="mono">{{ d.size }}</td>
              <td>
                <StatusTag :color="docStatus(d.status).c" :bg="docStatus(d.status).b" dot>
                  {{ docStatus(d.status).t }}
                </StatusTag>
              </td>
              <td class="mono">{{ d.chunks || "—" }}</td>
              <td>
                <span v-for="t in d.tags" :key="t" class="tag t-tag">{{ t }}</span>
              </td>
              <td class="mono muted">{{ d.updatedAt }}</td>
            </tr>
          </tbody>
        </table>
        <div class="hint">
          支持 PDF / Word / PPT / Excel / Markdown / TXT / 图片（含 OCR、图文混排、表格、公式识别），单文档 ≥ 200MB。
        </div>
      </div>

      <!-- retrieve -->
      <div v-show="tab === 'retrieve'" class="tab-body">
        <div class="retrieve">
          <div class="ret-left">
            <label class="field-label">检索 Query</label>
            <textarea v-model="query" class="field" rows="3" />
            <div class="row2" style="margin-top:12px">
              <label class="check"><input type="checkbox" checked /> 多轮改写</label>
              <label class="check"><input type="checkbox" checked /> Reranker 重排</label>
            </div>
            <button class="btn btn-primary" style="width:100%;margin-top:14px" :disabled="testing" @click="runRetrieve">
              {{ testing ? "检索中…" : "执行检索测试" }}
            </button>
            <div class="ret-meta">
              <div><span>检索方式</span><b>混合检索</b></div>
              <div><span>召回数量</span><b>{{ kb.topK }}</b></div>
              <div><span>耗时</span><b>240 ms</b></div>
            </div>
          </div>
          <div class="ret-right">
            <div class="ret-title">召回结果（按相关性排序）</div>
            <div v-if="!results && !testing" class="empty">输入 Query 并点击检索，查看可视化召回结果</div>
            <div v-if="testing" class="empty">检索中…</div>
            <div v-for="r in results" :key="r.id" class="ret-card">
              <div class="ret-card-head">
                <span class="ret-score">相似度 {{ r.score }}</span>
                <span class="ret-src">{{ r.src }}</span>
                <span class="tag t-tag">{{ r.tag }}</span>
              </div>
              <div class="ret-text">{{ r.text }}</div>
              <div class="ret-foot">
                <button class="btn btn-xs btn-ghost">查看来源原文</button>
                <button class="btn btn-xs btn-ghost">查看向量</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- config -->
      <div v-show="tab === 'config'" class="tab-body cfg">
        <section class="sec card">
          <div class="sec-title">切片策略</div>
          <div class="row2">
            <div><label class="field-label">分块大小</label><input class="field" value="512" /></div>
            <div><label class="field-label">重叠长度</label><input class="field" value="64" /></div>
          </div>
          <label class="field-label" style="margin-top:16px">切片方式</label>
          <div class="seg">
            <button class="on">按标题</button><button>按段落</button><button>按语义</button><button>固定长度</button>
          </div>
        </section>
        <section class="sec card">
          <div class="sec-title">检索与重排</div>
          <label class="field-label">检索方式</label>
          <div class="seg">
            <button :class="{ on: kb.retrieveMode === 'hybrid' }">混合检索</button>
            <button :class="{ on: kb.retrieveMode === 'vector' }">向量检索</button>
          </div>
          <div class="row2" style="margin-top:16px">
            <div><label class="field-label">召回数量 Top-K</label><input class="field" :value="kb.topK" /></div>
            <div>
              <label class="field-label">Reranker 模型</label>
              <select v-model="kb.reranker" class="field">
                <option v-for="r in onlineRerankers" :key="r" :value="r">{{ r }}</option>
                <option value="">不使用重排（仅向量检索）</option>
              </select>
            </div>
          </div>
          <label class="field-label" style="margin-top:16px">来源展示</label>
          <label class="check"><input type="checkbox" checked /> 回答带角标引用来源与原文</label>
        </section>
      </div>
    </div>

    <Modal v-if="upload" title="上传文档" @close="upload = false">
      <div class="drop">
        <div class="drop-ico">⬆</div>
        <div class="drop-title">拖拽文件到此处，或点击选择</div>
        <div class="drop-sub">支持 PDF / Word / PPT / Excel / Markdown / 图片，单文件最大 200MB</div>
        <button class="btn" style="margin-top:14px">选择文件</button>
      </div>
      <template #foot>
        <button class="btn" @click="upload = false">取消</button>
        <button class="btn btn-primary" @click="upload = false">开始上传并解析</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.page {
  max-width: 1240px;
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
  background: var(--amber-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  flex: none;
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
  font-size: 13px;
  color: var(--text-3);
  margin: 3px 0 9px;
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
.chip.sm {
  padding: 2px 8px;
  font-size: 11px;
}
.t-hy {
  color: var(--teal);
  background: var(--teal-soft);
}
.t-tag {
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  margin-right: 4px;
}
.tabs-wrap {
  padding: 0 4px;
}
.tab-body {
  padding: 22px;
}
.hint {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 14px;
}
.retrieve {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 20px;
  align-items: start;
}
.ret-left {
  background: var(--surface-2);
  border-radius: 12px;
  padding: 16px;
}
.check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  color: var(--text-2);
  margin: 0;
}
.ret-meta {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ret-meta div {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-3);
  padding: 7px 10px;
  background: var(--surface);
  border-radius: 8px;
}
.ret-meta b {
  color: var(--text-1);
  font-family: var(--mono);
}
.ret-title {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 12px;
}
.ret-card {
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px;
  margin-bottom: 12px;
  background: var(--surface);
  transition: all 0.15s;
}
.ret-card:hover {
  border-color: var(--brand);
  box-shadow: var(--sh-sm);
}
.ret-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.ret-score {
  font-size: 12px;
  font-weight: 700;
  color: var(--green);
  background: var(--green-soft);
  border-radius: 999px;
  padding: 2px 9px;
}
.ret-src {
  font-size: 13px;
  font-weight: 600;
}
.ret-text {
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text-2);
}
.ret-foot {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.cfg {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
.sec {
  padding: 20px;
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 16px;
}
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.drop {
  border: 2px dashed var(--border-strong);
  border-radius: 14px;
  padding: 38px 20px;
  text-align: center;
}
.drop-ico {
  font-size: 30px;
  color: var(--brand);
}
.drop-title {
  font-size: 15px;
  font-weight: 600;
  margin-top: 8px;
}
.drop-sub {
  font-size: 12.5px;
  color: var(--text-3);
  margin-top: 6px;
}
</style>
