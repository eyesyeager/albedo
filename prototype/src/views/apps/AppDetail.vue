<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { apps, sessionTraces, models } from "@/data/mock";
import type { AppItem, AppConfig, SessionTrace, TraceStep } from "@/types";
import StatusTag from "@/components/StatusTag.vue";
import Modal from "@/components/Modal.vue";
import TraceFlow from "@/components/TraceFlow.vue";

const route = useRoute();
const router = useRouter();
const app = ref<AppItem>(apps.find((a) => a.id === route.params.id) || apps[0]);
const cfg = ref<AppConfig>(JSON.parse(JSON.stringify(app.value.config)));
const tab = ref("config");
const saved = ref(false);

watch(
  () => route.params.id,
  () => {
    app.value = apps.find((a) => a.id === route.params.id) || apps[0];
    cfg.value = JSON.parse(JSON.stringify(app.value.config));
  }
);

function toggleStatus() {
  app.value.status = app.value.status === "enabled" ? "disabled" : "enabled";
}
function save() {
  app.value.config = JSON.parse(JSON.stringify(cfg.value));
  app.value.snapshots.unshift({
    id: "snap-" + Date.now(),
    savedAt: new Date().toISOString().slice(0, 16).replace("T", " "),
    note: "手动保存",
    config: `model=${cfg.value.model}, react=${cfg.value.reactRounds}, memory=${cfg.value.shortMemory}`,
  });
  saved.value = true;
  setTimeout(() => (saved.value = false), 2200);
}
function rollback(snapId: string) {
  const s = app.value.snapshots.find((x) => x.id === snapId);
  if (s) {
    cfg.value = JSON.parse(JSON.stringify(app.value.config));
    saved.value = true;
    setTimeout(() => (saved.value = false), 2200);
  }
}
const showKey = ref(false);

/* ---- debug console ---- */
const debugInput = ref("");
const debugRunning = ref(false);
const demoSteps: TraceStep[] = sessionTraces[0].steps;
const debugSteps = ref<TraceStep[]>([]);
const debugAnswer = ref("");
function sendDebug() {
  if (!debugInput.value.trim() || debugRunning.value) return;
  debugRunning.value = true;
  debugSteps.value = [];
  debugAnswer.value = "";
  const q = debugInput.value;
  let i = 0;
  const timer = setInterval(() => {
    if (i < demoSteps.length) {
      debugSteps.value.push(demoSteps[i]);
      if (demoSteps[i].type === "text") debugAnswer.value = demoSteps[i].detail;
      i++;
    } else {
      clearInterval(timer);
      debugRunning.value = false;
      debugInput.value = "";
    }
  }, 650);
}

/* ---- sessions ---- */
const sessions = computed(() =>
  sessionTraces.filter((s) => s.appId === app.value.id)
);
const selSession = ref<SessionTrace | null>(sessions.value[0] || null);
function pick(s: SessionTrace) {
  selSession.value = s;
}

/* ---- AGUI code sample ---- */
const codeTab = ref("curl");
const base = "https://api.albedo.eyescode.top";
function sample(lang: string) {
  const id = app.value.id;
  if (lang === "curl")
    return `curl -X POST '${base}/agui/apps/${id}/chat' \\\n  -H 'Authorization: Bearer <JWT>' \\\n  -H 'Content-Type: application/json' \\\n  -d '{\n    "session_id": "sess-xyz",\n    "message": "年假怎么算？"\n  }'`;
  if (lang === "py")
    return `import requests\n\nresp = requests.post(\n    "${base}/agui/apps/${id}/chat",\n    headers={"Authorization": f"Bearer {JWT}"},\n    json={"session_id": "sess-xyz", "message": "年假怎么算？"},\n    stream=True,\n)\nfor line in resp.iter_lines():\n    if line:\n        print(line.decode())  # AGUI 事件流`;
  return `fetch("${base}/agui/apps/${id}/chat", {\n  method: "POST",\n  headers: { Authorization: \`Bearer \${JWT}\` },\n  body: JSON.stringify({ session_id: "sess-xyz", message: "年假怎么算？" }),\n})\n  .then((r) => r.body.getReader())\n  .then((reader) => /* 逐块读取 AGUI 事件 */ null);`;
}
const aguiEvents = [
  ["RUN_STARTED", "运行开始"],
  ["STEP_STARTED / STEP_FINISHED", "规划步骤（思考）"],
  ["TOOL_CALL_START / TOOL_CALL_ARGS / TOOL_CALL_END", "工具调用（含入参与返回）"],
  ["TEXT_MESSAGE_START / CONTENT / END", "文本消息 token 级流式"],
  ["STATE_SNAPSHOT / STATE_DELTA", "状态快照同步"],
  ["RUN_FINISHED / RUN_ERROR", "运行结束或异常"],
];
</script>

<template>
  <div class="page">
    <!-- header -->
    <div class="detail-head card">
      <button class="back" @click="router.push('/apps')">‹ 应用</button>
      <div class="dh-icon" :style="{ background: app.iconColor + '1f', color: app.iconColor }">
        {{ app.icon }}
      </div>
      <div class="dh-info">
        <div class="dh-name">{{ app.name }}</div>
        <div class="dh-desc">{{ app.desc }}</div>
        <div class="dh-tags">
          <StatusTag
            :color="app.status === 'enabled' ? 'var(--green)' : 'var(--text-3)'"
            :bg="app.status === 'enabled' ? 'var(--green-soft)' : 'var(--surface-2)'"
            dot
          >{{ app.status === "enabled" ? "已启用" : "已停用" }}</StatusTag>
          <span class="chip">ID {{ app.id }}</span>
          <span class="chip">QPM {{ app.qpm }}</span>
          <span class="chip">JWT {{ app.jwtKeyMasked }}</span>
        </div>
      </div>
      <div class="dh-actions">
        <span
          class="switch"
          :class="{ on: app.status === 'enabled' }"
          @click="toggleStatus"
          :title="app.status === 'enabled' ? '点击停用' : '点击启用'"
        />
        <span class="dh-stat">{{ app.status === "enabled" ? "对外可调用" : "已停止对外服务" }}</span>
      </div>
    </div>

    <!-- tabs -->
    <div class="tabs-wrap card">
      <div class="tabs">
        <button :class="{ on: tab === 'config' }" @click="tab = 'config'">智能体配置</button>
        <button :class="{ on: tab === 'debug' }" @click="tab = 'debug'">调试台</button>
        <button :class="{ on: tab === 'api' }" @click="tab = 'api'">AGUI API</button>
        <button :class="{ on: tab === 'sessions' }" @click="tab = 'sessions'">会话回放</button>
        <button :class="{ on: tab === 'snap' }" @click="tab = 'snap'">快照回滚</button>
      </div>

      <!-- ============ CONFIG ============ -->
      <div v-show="tab === 'config'" class="tab-body config">
        <div class="cfg-col">
          <section class="sec card">
            <div class="sec-title">基础信息</div>
            <label class="field-label">应用名称</label>
            <input v-model="app.name" class="field" />
            <label class="field-label" style="margin-top: 14px">应用描述</label>
            <textarea v-model="app.desc" class="field" rows="2" />
          </section>

          <section class="sec card">
            <div class="sec-title">模型与提示词</div>
            <div class="row2">
              <div>
                <label class="field-label">绑定模型</label>
                <select v-model="cfg.model" class="field">
                  <option v-for="m in models.filter((x) => x.type === 'LLM' && x.status !== 'offline')" :key="m.id" :value="m.name">{{ m.name }}</option>
                </select>
              </div>
              <div>
                <label class="field-label">调用模式</label>
                <div class="seg">
                  <button :class="{ on: cfg.modelMode === 'fixed' }" @click="cfg.modelMode = 'fixed'">指定模型</button>
                  <button :class="{ on: cfg.modelMode === 'auto' }" @click="cfg.modelMode = 'auto'">智能模式</button>
                </div>
                <div class="field-hint" v-if="cfg.modelMode === 'auto'">
                  AUTO / 性能 / 均衡 / 经济 四档路由
                </div>
              </div>
            </div>
            <label class="field-label" style="margin-top: 16px">系统提示词（Prompt）</label>
            <textarea v-model="cfg.prompt" class="field mono-area" rows="7" />
          </section>

          <section class="sec card">
            <div class="sec-title">高级参数</div>
            <div class="row2">
              <div>
                <label class="field-label">ReAct 轮次上限 (1–50)</label>
                <input v-model.number="cfg.reactRounds" type="number" min="1" max="50" class="field" />
              </div>
              <div>
                <label class="field-label">短期记忆轮数 (0–30)</label>
                <input v-model.number="cfg.shortMemory" type="number" min="0" max="30" class="field" />
              </div>
            </div>
            <label class="field-label" style="margin-top: 16px">知识检索策略</label>
            <div class="seg">
              <button :class="{ on: cfg.knowledgeStrategy === 'smart' }" @click="cfg.knowledgeStrategy = 'smart'">智能调用</button>
              <button :class="{ on: cfg.knowledgeStrategy === 'always' }" @click="cfg.knowledgeStrategy = 'always'">必定调用</button>
            </div>
          </section>
        </div>

        <div class="cfg-col">
          <section class="sec card">
            <div class="sec-title">
              工具装配
              <span class="count">{{ cfg.tools.length }}</span>
            </div>
            <div class="field-hint" style="margin: -4px 0 12px">
              知识库（多库联合）与 MCP 统一抽象为工具，由模型自主规划调用。
            </div>
            <div v-for="t in cfg.tools" :key="t.id" class="tool-row">
              <span class="tool-kind" :class="t.kind === 'KnowledgeBase' ? 'kb' : 'mcp'">
                {{ t.kind === "KnowledgeBase" ? "知识库" : "MCP" }}
              </span>
              <div class="tool-main">
                <div class="tool-name">{{ t.name }}</div>
                <div class="tool-sub">{{ t.sub }}</div>
              </div>
              <div class="seg sm" v-if="t.kind !== 'KnowledgeBase'">
                <button :class="{ on: t.strategy === 'smart' }" @click="t.strategy = 'smart'">智能</button>
                <button :class="{ on: t.strategy === 'always' }" @click="t.strategy = 'always'">必调</button>
              </div>
              <span class="switch" :class="{ on: t.enabled }" @click="t.enabled = !t.enabled" />
            </div>
            <button class="add-tool">+ 挂载知识库 / MCP Server</button>
          </section>

          <section class="sec card">
            <div class="sec-title">环境变量 / 鉴权</div>
            <div class="field-hint" style="margin: -4px 0 12px">
              为 MCP 配置密钥与变量，避免硬编码。
            </div>
            <div class="kv" v-for="i in 2" :key="i">
              <input class="field" :placeholder="i === 1 ? 'KEY（如 MCP_TOKEN）' : 'KEY'" />
              <input class="field" :placeholder="i === 1 ? 'value' : 'value'" type="password" />
            </div>
            <button class="add-tool">+ 新增变量</button>
          </section>
        </div>
      </div>

      <!-- ============ DEBUG ============ -->
      <div v-show="tab === 'debug'" class="tab-body debug">
        <div class="chat">
          <div class="chat-msg user">
            <div class="bubble">年假怎么算？我入职满 2 年了。</div>
          </div>
          <div class="chat-msg bot">
            <div class="bubble">
              根据《员工考勤管理制度》[1]，年假按累计工龄计算，入职满 1 年即可享受，满 2 年对应年休假为
              <b>5 天</b>。如需分段休假或折算未休天数，可参考制度第 4.3 条[1]。
              <div class="cite">[1] 员工考勤管理制度.pdf · 第 3.2 条</div>
            </div>
          </div>
          <div v-if="debugSteps.length" class="chat-msg bot">
            <div class="bubble">
              <TraceFlow :steps="debugSteps" compact />
            </div>
          </div>
        </div>
        <div class="chat-input">
          <input
            v-model="debugInput"
            class="field"
            placeholder="输入测试问题，回车或点击发送进行多轮调试…"
            @keyup.enter="sendDebug"
          />
          <button class="btn btn-primary" :disabled="debugRunning" @click="sendDebug">
            {{ debugRunning ? "运行中…" : "发送" }}
          </button>
        </div>
        <div class="debug-side">
          <div class="card side-card">
            <div class="side-title">运行过程可视化</div>
            <div class="field-hint">卡片流展示「思考 / 工具调用」步骤与入参返回。</div>
            <div class="mini-stat">
              <span>工具调用</span><b>1 次</b>
            </div>
            <div class="mini-stat"><span>首字延迟</span><b>680 ms</b></div>
            <div class="mini-stat"><span>总耗时</span><b>3.82 s</b></div>
            <div class="mini-stat"><span>Token</span><b>412 / 96</b></div>
          </div>
          <div class="card side-card">
            <div class="side-title">上下文</div>
            <button class="btn btn-xs btn-ghost" style="width:100%">查看完整 Token 明细</button>
            <button class="btn btn-xs btn-ghost" style="width:100%;margin-top:8px">查看检索结果</button>
          </div>
        </div>
      </div>

      <!-- ============ AGUI API ============ -->
      <div v-show="tab === 'api'" class="tab-body api">
        <div class="api-grid">
          <section class="card sec">
            <div class="sec-title">对外接入信息</div>
            <div class="kv-row">
              <span class="kv-k">协议</span>
              <span>AGUI（Agent-User Interaction Protocol）· SSE 事件流</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">状态</span>
              <StatusTag :color="app.status === 'enabled' ? 'var(--green)' : 'var(--text-3)'" :bg="app.status === 'enabled' ? 'var(--green-soft)' : 'var(--surface-2)'" dot>{{ app.status === 'enabled' ? '对外可调用' : '已停用' }}</StatusTag>
            </div>
            <div class="kv-row">
              <span class="kv-k">对话端点</span>
              <code class="mono">{{ base }}/agui/apps/{{ app.id }}/chat</code>
            </div>
            <div class="kv-row">
              <span class="kv-k">QPM 限流</span>
              <span>{{ app.qpm }} 次/分钟 · 按应用限流，不做计费配额</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">应用 Jwt Key</span>
              <code class="mono">{{ showKey ? "eyJhbGciOiJIUzI1NiJ9.ak_" + app.jwtKeyMasked.slice(3) : app.jwtKeyMasked }}</code>
              <button class="btn btn-xs btn-ghost" @click="showKey = !showKey">{{ showKey ? "隐藏" : "显示" }}</button>
              <button class="btn btn-xs btn-ghost">轮换</button>
            </div>
            <div class="field-hint">
              业务方使用应用 Jwt Key 以 HS256 自签 JWT，平台仅验证签名；会话完全匿名（按 app_id + session_id 隔离）。
            </div>
          </section>

          <section class="card sec">
            <div class="sec-title">调用示例</div>
            <div class="seg">
              <button :class="{ on: codeTab === 'curl' }" @click="codeTab = 'curl'">cURL</button>
              <button :class="{ on: codeTab === 'py' }" @click="codeTab = 'py'">Python</button>
              <button :class="{ on: codeTab === 'node' }" @click="codeTab = 'node'">Node.js</button>
            </div>
            <pre class="code mono">{{ sample(codeTab) }}</pre>
            <button class="btn btn-sm btn-ghost copy">复制代码</button>
          </section>
        </div>

        <section class="card sec" style="margin-top:16px">
          <div class="sec-title">AGUI 事件清单</div>
          <table class="tbl">
            <thead><tr><th>事件类型</th><th>说明</th></tr></thead>
            <tbody>
              <tr v-for="e in aguiEvents" :key="e[0]">
                <td><code class="mono ev">{{ e[0] }}</code></td>
                <td>{{ e[1] }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <!-- ============ SESSIONS ============ -->
      <div v-show="tab === 'sessions'" class="tab-body sessions">
        <div class="sess-list card">
          <div class="sec-title" style="padding:16px 16px 8px">线上会话</div>
          <div
            v-for="s in sessions"
            :key="s.id"
            class="sess-item"
            :class="{ on: selSession?.id === s.id }"
            @click="pick(s)"
          >
            <div class="sess-q">{{ s.userQuery }}</div>
            <div class="sess-meta">
              <StatusTag
                :color="s.status === 'success' ? 'var(--green)' : s.status === 'error' ? 'var(--red)' : 'var(--blue)'"
                :bg="s.status === 'success' ? 'var(--green-soft)' : s.status === 'error' ? 'var(--red-soft)' : 'var(--blue-soft)'"
              >{{ s.status === 'success' ? '成功' : s.status === 'error' ? '失败' : '运行中' }}</StatusTag>
              <span class="mono">{{ s.startedAt }}</span>
            </div>
          </div>
          <div v-if="!sessions.length" class="empty">暂无会话记录</div>
        </div>
        <div class="sess-detail">
          <div class="card sec" v-if="selSession">
            <div class="sec-title">会话回放 · {{ selSession.sessionId }}</div>
            <div class="field-hint" style="margin: -4px 0 14px">
              trace_id 关联消息与工具调用，贯穿全链路。
            </div>
            <div class="chat-msg user"><div class="bubble">{{ selSession.userQuery }}</div></div>
            <div class="chat-msg bot"><div class="bubble"><TraceFlow :steps="selSession.steps" /></div></div>
          </div>
        </div>
      </div>

      <!-- ============ SNAPSHOTS ============ -->
      <div v-show="tab === 'snap'" class="tab-body snap">
        <div class="card sec">
          <div class="sec-title">历史快照与回滚</div>
          <div class="field-hint" style="margin: -4px 0 14px">
            每次保存自动生成配置快照，支持一键回滚（回滚即保存生效）。
          </div>
          <div class="snap-list">
            <div v-for="(s, i) in app.snapshots" :key="s.id" class="snap-item">
              <span class="snap-dot" :class="{ latest: i === 0 }" />
              <div class="snap-body">
                <div class="snap-note">{{ s.note }}</div>
                <div class="snap-meta mono">{{ s.savedAt }} · {{ s.config }}</div>
              </div>
              <button class="btn btn-xs" :disabled="i === 0" @click="rollback(s.id)">回滚</button>
            </div>
            <div v-if="!app.snapshots.length" class="empty">暂无快照</div>
          </div>
        </div>
      </div>

      <div v-if="tab === 'config'" class="save-bar">
        <span v-if="saved" class="saved">✓ 已保存，配置即时线上生效</span>
        <button class="btn btn-primary" @click="save">保存配置</button>
      </div>
    </div>

    <div v-if="saved && tab !== 'config'" class="toast">✓ 已保存，配置即时线上生效</div>
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
.dh-icon {
  width: 50px;
  height: 50px;
  border-radius: 14px;
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
.dh-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dh-stat {
  font-size: 13px;
  color: var(--text-2);
}
.tabs-wrap {
  padding: 0 4px;
}
.tab-body {
  padding: 22px;
}
.config {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
.cfg-col {
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
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.mono-area {
  font-family: var(--mono);
  font-size: 12.5px;
  line-height: 1.6;
}
.tool-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.tool-row:last-of-type {
  border-bottom: none;
}
.tool-kind {
  font-size: 11.5px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 6px;
  flex: none;
}
.tool-kind.kb {
  color: var(--amber);
  background: var(--amber-soft);
}
.tool-kind.mcp {
  color: var(--violet);
  background: var(--violet-soft);
}
.tool-main {
  flex: 1;
  min-width: 0;
}
.tool-name {
  font-size: 14px;
  font-weight: 600;
}
.tool-sub {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 2px;
}
.seg.sm button {
  height: 26px;
  padding: 0 10px;
  font-size: 12px;
}
.add-tool {
  width: 100%;
  margin-top: 12px;
  height: 38px;
  border: 1px dashed var(--border-strong);
  background: var(--surface-2);
  border-radius: 10px;
  color: var(--text-2);
  font-size: 13.5px;
  font-weight: 500;
  transition: all 0.15s;
}
.add-tool:hover {
  border-color: var(--brand);
  color: var(--brand-700);
  background: var(--brand-soft-2);
}
.kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 10px;
}
.save-bar {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
  padding: 14px 22px;
  border-top: 1px solid var(--border);
  background: var(--surface);
  margin: 0 -4px;
}
.saved {
  color: var(--green);
  font-weight: 600;
  font-size: 13.5px;
}
.toast {
  position: fixed;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  background: #1b1c33;
  color: #fff;
  padding: 11px 20px;
  border-radius: 10px;
  font-size: 13.5px;
  box-shadow: var(--sh-lg);
  z-index: 500;
}

/* debug */
.debug {
  display: grid;
  grid-template-columns: 1fr 280px;
  gap: 16px;
  align-items: start;
}
.chat {
  background: var(--surface-2);
  border-radius: 12px;
  padding: 18px;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.chat-msg {
  display: flex;
}
.chat-msg.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 80%;
  padding: 11px 15px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.6;
}
.chat-msg.user .bubble {
  background: var(--brand);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.chat-msg.bot .bubble {
  background: var(--surface);
  border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
}
.cite {
  margin-top: 8px;
  font-size: 12px;
  color: var(--brand-700);
  background: var(--brand-soft);
  border-radius: 7px;
  padding: 5px 9px;
}
.chat-input {
  display: flex;
  gap: 10px;
  margin-top: 12px;
}
.chat-input .field {
  flex: 1;
}
.debug-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.side-card {
  padding: 16px;
}
.side-title {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 8px;
}
.mini-stat {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-2);
  padding: 9px 0;
  border-bottom: 1px solid var(--border);
}
.mini-stat:last-child {
  border-bottom: none;
}
.mini-stat b {
  color: var(--text-1);
  font-family: var(--mono);
}

/* api */
.api-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
.kv-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border);
  font-size: 13.5px;
}
.kv-row:last-of-type {
  border-bottom: none;
}
.kv-k {
  width: 92px;
  flex: none;
  color: var(--text-3);
  font-size: 13px;
}
code {
  font-size: 12.5px;
}
.ev {
  color: var(--brand-700);
}
.code {
  background: #1b1c33;
  color: #d6d9f0;
  border-radius: 10px;
  padding: 14px 16px;
  font-size: 12.5px;
  line-height: 1.6;
  overflow-x: auto;
  white-space: pre;
  margin: 12px 0;
}
.copy {
  width: 100%;
}

/* sessions */
.sessions {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}
.sess-item {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
  transition: background 0.12s;
}
.sess-item:hover {
  background: var(--brand-soft-2);
}
.sess-item.on {
  background: var(--brand-soft-2);
  box-shadow: inset 3px 0 0 var(--brand);
}
.sess-q {
  font-size: 13.5px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sess-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}
.sess-detail {
  min-width: 0;
}
.chat-msg.user .bubble,
.chat-msg.bot .bubble {
  max-width: 100%;
}

/* snap */
.snap-list {
  display: flex;
  flex-direction: column;
}
.snap-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border);
}
.snap-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--surface);
  border: 2px solid var(--border-strong);
  flex: none;
}
.snap-dot.latest {
  border-color: var(--brand);
  background: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}
.snap-body {
  flex: 1;
}
.snap-note {
  font-size: 14px;
  font-weight: 600;
}
.snap-meta {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 3px;
}
</style>
