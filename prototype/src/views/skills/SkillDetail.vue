<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { skills } from "@/data/mock";
import { skillUsage } from "@/store/usage";
import type { Skill, SkillPackageFile } from "@/types";

const route = useRoute();
const router = useRouter();
const skill = ref<Skill>(skills.find((s) => s.id === route.params.id) || skills[0]);

const tab = ref<"parse" | "files">("parse");
const saved = ref(false);
const draft = ref("");
const selectedPath = ref<string>("");
const expanded = ref<Record<string, boolean>>({});

const files = computed<SkillPackageFile[]>(() => skill.value.packageFiles ?? []);
const usage = computed(() => skillUsage(skill.value));

function sizeText(bytes: number) {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1024 / 1024).toFixed(2) + " MB";
}

/* ---------------- 文件夹树（目录结构由包本身决定，不假定固定规范） ---------------- */
interface TreeNode {
  name: string;
  path: string;
  type: "dir" | "file";
  children?: TreeNode[];
  file?: SkillPackageFile;
}

const tree = computed<TreeNode[]>(() => {
  const root: TreeNode = { name: "", path: "", type: "dir", children: [] };
  for (const f of files.value) {
    const parts = f.path.split("/").filter(Boolean);
    let cur = root;
    let acc = "";
    parts.forEach((p, i) => {
      acc = acc ? `${acc}/${p}` : p;
      const isFile = i === parts.length - 1;
      let node = cur.children!.find(
        (c) => c.name === p && (isFile ? c.type === "file" : c.type === "dir")
      );
      if (!node) {
        node = isFile
          ? { name: p, path: acc, type: "file", file: f }
          : { name: p, path: acc, type: "dir", children: [] };
        cur.children!.push(node);
      }
      cur = node;
    });
  }
  return root.children ?? [];
});

// 默认展开全部目录
const allDirs = computed(() => {
  const out: string[] = [];
  const walk = (nodes: TreeNode[]) => {
    for (const n of nodes) {
      if (n.type === "dir") {
        out.push(n.path);
        walk(n.children ?? []);
      }
    }
  };
  walk(tree.value);
  return out;
});
const dirSet = computed(() => {
  const map: Record<string, boolean> = {};
  for (const d of allDirs.value) map[d] = expanded.value[d] ?? true;
  return map;
});

interface Row {
  node: TreeNode;
  depth: number;
}
const rows = computed<Row[]>(() => {
  const out: Row[] = [];
  const cmp = (a: TreeNode, b: TreeNode) => {
    if (a.type !== b.type) return a.type === "dir" ? -1 : 1;
    return a.name.localeCompare(b.name, "zh-CN");
  };
  const walk = (nodes: TreeNode[], depth: number) => {
    for (const n of [...nodes].sort(cmp)) {
      out.push({ node: n, depth });
      if (n.type === "dir" && dirSet.value[n.path]) walk(n.children ?? [], depth + 1);
    }
  };
  walk(tree.value, 0);
  return out;
});

const totalSize = computed(() => files.value.reduce((s, f) => s + f.size, 0));
const dirCount = computed(() => allDirs.value.length);

function fileIcon(f: SkillPackageFile) {
  if (f.binary) return "🖼";
  if (/\.(py|sh|js)$/i.test(f.path)) return "📜";
  if (/\.(json|ya?ml)$/i.test(f.path)) return "⚙️";
  return "📄";
}

const currentPath = computed(
  () => selectedPath.value || skill.value.entryPath || files.value[0]?.path || ""
);
const currentFile = computed(() => files.value.find((f) => f.path === currentPath.value) ?? null);

function toggleDir(node: TreeNode) {
  expanded.value = { ...expanded.value, [node.path]: !(expanded.value[node.path] ?? true) };
}
function selectFile(node: TreeNode) {
  if (node.type === "file") selectedPath.value = node.path;
  else toggleDir(node);
}

const checks = computed(() => {
  const s = skill.value;
  const hasEntry = files.value.some((f) => /^[^/]+\.md$/i.test(f.path));
  const hasExec = files.value.some((f) => /\.(py|sh|js|exe|bat)$/i.test(f.path));
  return [
    {
      label: "包含入口 Markdown 文件",
      ok: hasEntry,
      hint: "根目录需有 .md 入口（优先 SKILL.md，也可是 README.md 等）",
    },
    { label: "frontmatter 元数据完整", ok: !!s.name && !!s.desc, hint: "需提供 name / description" },
    { label: "未包含可执行文件", ok: !hasExec, hint: "脚本类文件仅静态登记，不会执行" },
    { label: `包大小 ≤ 10MB（当前 ${sizeText(totalSize.value)}）`, ok: totalSize.value <= 10 * 1024 * 1024, hint: "超过需拆分资源" },
  ];
});

/* ---------------- 直接在文件夹中编辑并保存 ---------------- */
function parseFrontmatter(text: string) {
  const meta: Record<string, string> = {};
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!m) return meta;
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^(\w[\w-]*)\s*:\s*(.*)$/.exec(line.trim());
    if (kv) meta[kv[1]] = kv[2].replace(/^["']|["']$/g, "");
  }
  return meta;
}

// 切换文件时载入可编辑内容
watch(
  currentPath,
  () => {
    draft.value = currentFile.value?.content ?? "";
  },
  { immediate: true }
);

const dirty = computed(
  () => !!currentFile.value && draft.value !== (currentFile.value.content ?? "")
);

function saveFile() {
  const f = currentFile.value;
  if (!f || !dirty.value) return;
  f.content = draft.value;
  f.size = new Blob([draft.value]).size;
  // 保存入口文件时，重新解析 frontmatter 以同步 Skill 元数据
  if (f.path === skill.value.entryPath) {
    const meta = parseFrontmatter(draft.value);
    const s = skill.value;
    if (meta.name) s.name = meta.name;
    if (meta.description) s.desc = meta.description;
    if (meta.version) s.version = meta.version;
    if (meta.icon) s.icon = meta.icon;
    // 修改预置模板即派生为自定义 Skill，不再受官方模板升级影响
    if (s.preset) s.preset = false;
  }
  saved.value = true;
  setTimeout(() => (saved.value = false), 2200);
}

/* ---------------- 下载：按包内实际文件导出，不依赖固定结构 ---------------- */
function buildExport() {
  const s = skill.value;
  const header = [
    `# ${s.name}`,
    "",
    `> description: ${s.desc}`,
    `> version: ${s.version || "0.0.0"}`,
    `> icon: ${s.icon}`,
    `> exported_at: ${new Date().toLocaleString("zh-CN", { hour12: false })}`,
    "",
  ].join("\n");
  const body = (s.packageFiles ?? [])
    .map((f) => {
      const head = `<!-- ===== ${f.path} ===== -->\n\n`;
      const content = f.binary
        ? `[二进制资源 ${f.path}，${sizeText(f.size)}，请从完整技能包下载]`
        : (f.content ?? "");
      return head + content;
    })
    .join("\n\n");
  return header + body;
}
function download() {
  const blob = new Blob([buildExport()], { type: "text/markdown;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${skill.value.name}-package.md`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
</script>

<template>
  <div class="page">
    <div class="detail-head card">
      <button class="back" @click="router.push('/skills')">‹ Skill</button>
      <div class="dh-ico">{{ skill.icon }}</div>
      <div class="dh-info">
        <div class="dh-name">{{ skill.name }}</div>
        <div class="dh-desc">{{ skill.desc }}</div>
        <div class="dh-tags">
          <span class="tag" :class="skill.preset ? 'preset' : 'custom'">
            {{ skill.preset ? "预置" : "自定义" }}
          </span>
          <span v-if="skill.packaged" class="chip">📦 技能包 v{{ skill.version }}</span>
          <span class="chip">ID {{ skill.id }}</span>
          <span v-if="usage.length" class="chip warn">被 {{ usage.length }} 个应用使用</span>
        </div>
      </div>
      <div class="dh-actions">
        <button class="btn btn-primary" @click="download">下载</button>
      </div>
    </div>

    <div class="tabs-wrap card">
      <div class="tabs">
        <button :class="{ on: tab === 'parse' }" @click="tab = 'parse'">解析详情</button>
        <button :class="{ on: tab === 'files' }" @click="tab = 'files'">
          包内文件（{{ files.length }}）
        </button>
      </div>

      <!-- 解析详情 -->
      <div v-show="tab === 'parse'" class="tab-body">
        <div class="parse-grid">
          <section class="sec card">
            <div class="sec-title">解析出的元数据</div>
            <div class="kv-row"><span class="kv-k">名称</span><span>{{ skill.name }}</span></div>
            <div class="kv-row">
              <span class="kv-k">版本</span><span class="mono">v{{ skill.version || "0.0.0" }}</span>
            </div>
            <div class="kv-row"><span class="kv-k">图标</span><span>{{ skill.icon }}</span></div>
            <div class="kv-row">
              <span class="kv-k">来源</span>
              <span>{{ skill.preset ? "官方预置模板" : "自定义（技能包导入）" }}</span>
            </div>
            <div class="kv-row">
              <span class="kv-k">入口文件</span>
              <span class="mono">{{ skill.entryPath || "（未识别）" }}</span>
            </div>
          </section>

          <section class="sec card">
            <div class="sec-title">包校验项</div>
            <div v-for="c in checks" :key="c.label" class="pkg-check">
              <span :class="c.ok ? 'ck-ok' : 'ck-bad'">{{ c.ok ? "✓" : "✕" }}</span>
              <span class="pkg-check-label">{{ c.label }}</span>
              <span class="muted">{{ c.hint }}</span>
            </div>
          </section>
        </div>

        <section class="sec card" style="margin-top:16px">
          <div class="sec-title">包内结构概览</div>
          <div class="stat-row">
            <div class="stat"><b>{{ files.length }}</b><span>文件</span></div>
            <div class="stat"><b>{{ dirCount }}</b><span>目录</span></div>
            <div class="stat"><b>{{ sizeText(totalSize) }}</b><span>解压后总大小</span></div>
          </div>
          <div class="field-hint" style="margin-top:10px">
            目录结构由上传的技能包自身决定，平台不强制命名规范；完整结构见「包内文件」页。
          </div>
        </section>

        <section class="sec card" style="margin-top:16px">
          <div class="sec-title">占用情况</div>
          <div v-if="usage.length" class="occupied">
            该 Skill 正被以下应用装配，<b>不允许删除</b>；需先在对应应用的「工具装配」中解绑。
            <div class="used-apps">
              <span v-for="a in usage" :key="a.id" class="used-app">{{ a.name }}</span>
            </div>
          </div>
          <div v-else class="field-hint">当前无应用装配该 Skill，可在 Skill 管理列表页直接删除。</div>
        </section>
      </div>

      <!-- 包内文件：文件夹树 + 文件内容 -->
      <div v-show="tab === 'files'" class="tab-body">
        <div v-if="!files.length" class="empty">该 Skill 无技能包文件</div>
        <div v-else class="explorer">
          <div class="tree card">
            <div class="tree-head">技能包根目录</div>
            <div
              v-for="r in rows"
              :key="r.node.path"
              class="tree-row"
              :class="{ on: r.node.type === 'file' && r.node.path === currentPath }"
              :style="{ paddingLeft: 12 + r.depth * 16 + 'px' }"
              @click="selectFile(r.node)"
            >
              <span class="tree-ico">
                {{ r.node.type === "dir" ? (dirSet[r.node.path] ? "📂" : "📁") : fileIcon(r.node.file!) }}
              </span>
              <span class="tree-name mono">{{ r.node.name }}</span>
              <span v-if="r.node.type === 'file'" class="tree-size muted">
                {{ sizeText(r.node.file!.size) }}
              </span>
            </div>
          </div>

          <div class="viewer card">
            <div v-if="!currentFile" class="empty">请选择左侧文件</div>
            <template v-else>
              <div class="viewer-head">
                <span class="viewer-ico">{{ fileIcon(currentFile) }}</span>
                <span class="mono viewer-path">{{ currentFile.path }}</span>
                <span class="muted">{{ sizeText(currentFile.size) }}</span>
                <span v-if="currentFile.path === skill.entryPath" class="tag entry">入口文件</span>
                <span v-if="dirty" class="tag dirty">未保存</span>
                <button
                  class="btn btn-xs btn-primary save-btn"
                  :disabled="!dirty"
                  @click="saveFile"
                >
                  保存
                </button>
              </div>
              <textarea
                v-if="!currentFile.binary"
                v-model="draft"
                class="field mono editor"
                spellcheck="false"
              />
              <div v-else class="binary-note">
                二进制资源不可在线编辑。可通过右上角「下载」获取完整技能包。
              </div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <div v-if="saved" class="toast">✓ 已保存，元数据已同步</div>
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
.chip.warn {
  color: var(--amber);
  background: var(--amber-soft);
}
.preset {
  color: var(--violet);
  background: var(--violet-soft);
}
.custom {
  color: var(--teal);
  background: var(--teal-soft);
}
.dh-actions {
  display: flex;
  gap: 10px;
  flex: none;
}
.tabs-wrap {
  padding: 0 4px;
}
.tab-body {
  padding: 22px;
}
.sec-title {
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 16px;
}
.parse-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
.sec {
  padding: 20px;
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
  width: 84px;
  flex: none;
  color: var(--text-3);
}
.pkg-check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  padding: 7px 0;
  flex-wrap: wrap;
}
.pkg-check-label {
  font-weight: 500;
}
.ck-ok {
  color: var(--green);
  font-weight: 700;
}
.ck-bad {
  color: var(--red);
  font-weight: 700;
}
.stat-row {
  display: flex;
  gap: 10px;
}
.stat {
  flex: 1;
  background: var(--surface-2);
  border-radius: 9px;
  padding: 10px;
  text-align: center;
}
.stat b {
  display: block;
  font-size: 17px;
  font-weight: 800;
}
.stat span {
  font-size: 11px;
  color: var(--text-3);
}
.occupied {
  font-size: 13.5px;
  color: var(--text-2);
  line-height: 1.7;
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
.explorer {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
  align-items: start;
}
.tree {
  overflow: hidden;
}
.tree-head {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 12.5px;
  color: var(--text-3);
}
.tree-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.12s;
}
.tree-row:hover {
  background: var(--brand-soft-2);
}
.tree-row.on {
  background: var(--brand-soft-2);
  box-shadow: inset 3px 0 0 var(--brand);
}
.tree-ico {
  flex: none;
  font-size: 13px;
}
.tree-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-size {
  font-size: 11.5px;
  flex: none;
}
.viewer {
  padding: 0;
  overflow: hidden;
}
.viewer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
  flex-wrap: wrap;
}
.viewer-ico {
  font-size: 15px;
}
.viewer-path {
  font-weight: 600;
}
.tag.entry {
  color: var(--brand-700);
  background: var(--brand-soft);
}
.save-btn {
  margin-left: auto;
}
.tag.dirty {
  color: var(--amber);
  background: var(--amber-soft);
}
.editor {
  border: none;
  border-radius: 0;
  height: 440px;
  font-size: 12.5px;
  line-height: 1.7;
  resize: vertical;
}
.editor:focus {
  box-shadow: none;
  background: var(--surface-2);
}
.binary-note {
  padding: 28px 20px;
  text-align: center;
  color: var(--text-3);
  font-size: 13px;
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
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.pkg-err {
  color: var(--red);
  font-size: 13px;
}
.empty {
  padding: 24px;
  text-align: center;
  color: var(--text-3);
}
</style>
