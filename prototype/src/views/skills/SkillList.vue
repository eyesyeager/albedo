<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { skills } from "@/data/mock";
import { skillUsage } from "@/store/usage";
import type { Skill, SkillPackageFile, SkillPackageReport } from "@/types";
import Modal from "@/components/Modal.vue";

const list = ref<Skill[]>([...skills]);
const showImport = ref(false);
const delTarget = ref<Skill | null>(null);
const blockTarget = ref<Skill | null>(null);
const blockUsage = computed(() => (blockTarget.value ? skillUsage(blockTarget.value) : []));
const router = useRouter();

// 被应用装配时不允许删除
function usageCount(s: Skill) {
  return skillUsage(s).length;
}
function requestDelete(s: Skill) {
  if (usageCount(s) > 0) {
    blockTarget.value = s;
    return;
  }
  delTarget.value = s;
}

// 同步移除 mock 源数组，使详情页后续读取也反映删除结果
function removeFromSource(id: string) {
  const i = skills.findIndex((s) => s.id === id);
  if (i > -1) skills.splice(i, 1);
}

/* ---------------- 技能包导入（SKILL.md + references + scripts + assets） ---------------- */
const parsing = ref(false);
const pkgFile = ref<string>("");
const pkgSize = ref(0);
const pkgError = ref("");
const pkgReport = ref<SkillPackageReport | null>(null);

function sizeText(bytes: number) {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1024 / 1024).toFixed(2) + " MB";
}

/** 解析 SKILL.md 的 YAML frontmatter（name/description/version/mcp/icon） */
function parseFrontmatter(text: string) {
  const meta: Record<string, string> = {};
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!m) return { meta, body: text.trim() };
  for (const line of m[1].split(/\r?\n/)) {
    const kv = /^(\w[\w-]*)\s*:\s*(.*)$/.exec(line.trim());
    if (kv) meta[kv[1]] = kv[2].replace(/^["']|["']$/g, "");
  }
  return { meta, body: m[2].trim() };
}

function buildChecks(files: SkillPackageFile[], meta: Record<string, string>, bytes: number) {
  const hasEntry = files.some((f) => /^[^/]+\.md$/i.test(f.path));
  const hasExec = files.some((f) => /\.(py|sh|js|exe|bat)$/i.test(f.path));
  return [
    {
      label: "包含入口 Markdown 文件",
      ok: hasEntry,
      hint: "根目录需有 .md 入口（优先 SKILL.md，也可是 README.md 等）",
    },
    { label: "frontmatter 元数据完整", ok: !!meta.name && !!meta.description, hint: "需提供 name / description" },
    { label: "未包含可执行文件", ok: !hasExec, hint: ".py/.sh/.js 等仅做静态登记，不会执行" },
    { label: `包大小 ≤ 10MB（当前 ${sizeText(bytes)}）`, ok: bytes <= 10 * 1024 * 1024, hint: "超过需拆分资源" },
  ];
}

async function onPickPackage(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  pkgError.value = "";
  pkgReport.value = null;
  parsing.value = true;
  pkgFile.value = file.name;
  pkgSize.value = file.size;

  try {
    if (/\.md$/i.test(file.name)) {
      // 单文件 Markdown：真实读取并解析 frontmatter
      const text = await file.text();
      const { meta, body } = parseFrontmatter(text);
      const files: SkillPackageFile[] = [{ path: file.name, size: file.size, content: text }];
      pkgReport.value = {
        fileName: file.name,
        size: sizeText(file.size),
        kind: "Markdown 单文件",
        valid: !!meta.name && !!meta.description,
        checks: buildChecks(files, meta, file.size),
        manifest: {
          name: meta.name || file.name.replace(/\.md$/i, ""),
          description: meta.description || "（未在 frontmatter 中提供描述）",
          version: meta.version || "0.0.0",
          icon: meta.icon || "🧩",
        },
        files,
        preview: body.slice(0, 240),
        entryPath: file.name,
      };
    } else {
      // 压缩包：由服务端解压解析（原型以演示数据模拟解压后的文件清单）
      await new Promise((r) => setTimeout(r, 900));
      const demo = demoPackage(file.name);
      pkgReport.value = {
        fileName: file.name,
        size: sizeText(file.size),
        kind: "技能包（zip）",
        valid: true,
        checks: buildChecks(demo.files, demo.manifest, file.size),
        manifest: demo.manifest,
        files: demo.files,
        preview: demo.preview,
        entryPath: demo.entryPath,
      };
    }
  } catch (err) {
    pkgError.value = "解析失败：" + String(err);
  } finally {
    parsing.value = false;
  }
}

/**
 * 演示用压缩包解析结果：解压后的文件清单。
 * 注意：目录结构只是**推荐约定**，平台不强制，此处刻意给出与标准不一致的结构以体现通用性。
 */
function demoPackage(fileName: string) {
  return {
    entryPath: "SKILL.md",
    manifest: {
      name: "差旅报销助手",
      description: "依据差旅制度回答报销标准与流程，自动抽取票据信息并生成报销草稿。",
      version: "1.2.0",
      icon: "🧾",
    },
    files: [
      {
        path: "SKILL.md",
        size: 612,
        content:
          "# 差旅报销助手\n\n## 适用场景\n回答差旅标准、票据要求与报销流程。\n\n## 处理流程\n1. 读取 `docs/差旅报销标准.md` 确认标准\n2. 调用凭证解析工具抽取票面信息\n3. 生成报销草稿并给出待补材料清单",
      },
      {
        path: "docs/差旅报销标准.md",
        size: 1024,
        content: "市内交通：据实报销，单日上限 80 元。住宿：按城市级别 A/B/C 分档 600/450/350 元。",
      },
      {
        path: "docs/票据类型说明.md",
        size: 768,
        content: "可报销票据：增值税发票、火车票、机票行程单、打车行程单。不可用：收据、手写白条。",
      },
      { path: "templates/报销草稿.md", size: 356, content: "报销人：{{name}}\n期间：{{range}}\n合计：{{total}}\n附件：{{attachments}}" },
      { path: "cover.png", size: 20480, binary: true },
    ] as SkillPackageFile[],
    preview:
      "# 差旅报销助手\n\n## 适用场景\n回答差旅标准、票据要求与报销流程。\n\n## 处理流程\n1. 读取 `docs/差旅报销标准.md` 确认标准\n2. 调用凭证解析工具抽取票面信息\n3. 生成报销草稿并给出待补材料清单",
  };
}

function importFromPackage() {
  const report = pkgReport.value;
  if (!report || !report.valid) return;
  list.value.push({
    id: "sk-" + Date.now(),
    name: report.manifest.name,
    desc: report.manifest.description,
    icon: report.manifest.icon || "🧩",
    preset: false,
    packaged: true,
    version: report.manifest.version,
    packageFiles: report.files,
    entryPath: report.entryPath,
  });
  closeImport();
}

function closeImport() {
  showImport.value = false;
  pkgFile.value = "";
  pkgSize.value = 0;
  pkgError.value = "";
  pkgReport.value = null;
  parsing.value = false;
}
function confirmDelete() {
  if (!delTarget.value) return;
  const id = delTarget.value.id;
  removeFromSource(id);
  list.value = list.value.filter((s) => s.id !== id);
  delTarget.value = null;
}
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">Skill 管理</h1>
        <p class="sub">Skill 为官方预打包的 MCP 能力推荐模板（本期不单独建模块，提供预置模板并支持导入自定义技能包）</p>
      </div>
      <button class="btn btn-primary" @click="showImport = true">+ 导入 Skill</button>
    </div>

    <div class="banner card">
      <span class="banner-ico">ℹ️</span>
      <span>
        Skill 是 MCP 的上层封装模板。除预置能力外，可直接上传技能包（<code class="mono">SKILL.md</code> +
        <code class="mono">references/</code> + <code class="mono">scripts/</code> +
        <code class="mono">assets/</code> 的压缩包）导入为自定义 Skill；点击卡片可查看解析详情、修改并下载。
      </span>
    </div>

    <div class="grid">
      <div v-for="s in list" :key="s.id" class="skill-card card" @click="router.push('/skills/' + s.id)">
        <div class="skill-top">
          <div class="skill-ico">{{ s.icon }}</div>
          <span class="tag" :class="s.preset ? 'preset' : 'custom'">
            {{ s.preset ? "预置" : "自定义" }}
          </span>
        </div>
        <div class="skill-name">{{ s.name }}</div>
        <div class="skill-desc">{{ s.desc }}</div>
        <div v-if="usageCount(s)" class="used-row">被 {{ usageCount(s) }} 个应用使用</div>
        <div v-if="s.packaged" class="pkg-badge">
          <span class="pkg-badge-ico">📦</span>
          技能包 v{{ s.version }}
          <span v-if="s.packageFiles?.length" class="muted">
            · {{ s.packageFiles.length }} 个文件
          </span>
        </div>
        <div class="skill-foot">
          <span class="chip">{{ s.packageFiles?.length || 0 }} 个文件</span>
          <div class="acts">
            <button class="btn btn-xs btn-ghost danger" @click.stop="requestDelete(s)">删除</button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="!list.length" class="card empty">暂无 Skill，点击「+ 导入 Skill」登记自定义技能包</div>

    <!-- 导入 Skill：仅支持上传技能包压缩文件 / SKILL.md -->
    <Modal v-if="showImport" title="导入 Skill" size="lg" @close="closeImport">

      <!-- 上传技能包 -->
      <div>
        <label class="field-label">选择技能包文件</label>
        <div class="pkg-drop">
          <div class="pkg-ico">📦</div>
          <div class="pkg-title">拖拽压缩包到此处，或点击下方选择文件</div>
          <div class="pkg-sub">
            支持 <code class="mono">.zip</code> 技能包与单独 <code class="mono">SKILL.md</code>；包含
            references/ 参考资料、scripts/ 脚本、assets/ 静态资源
          </div>
          <label class="btn pkg-pick">
            <input
              type="file"
              accept=".zip,.md"
              class="pkg-input"
              @change="onPickPackage"
            />
            选择文件
          </label>
        </div>

        <div v-if="pkgError" class="pkg-err">{{ pkgError }}</div>

        <div v-if="parsing" class="pkg-parsing">
          <span class="hc-spinner" />
          正在解压并解析技能包…（校验 SKILL.md / frontmatter / 资源清单）
        </div>

        <div v-if="pkgReport" class="pkg-report">
          <div class="pkg-file">
            <span class="pkg-name mono">{{ pkgReport.fileName }}</span>
            <span class="muted">{{ pkgReport.size }} · {{ pkgReport.kind }}</span>
            <span class="tag" :class="pkgReport.valid ? 't-ok' : 't-bad'">
              {{ pkgReport.valid ? "校验通过" : "校验未通过" }}
            </span>
          </div>

          <div class="pkg-sec">包校验项</div>
          <div v-for="c in pkgReport.checks" :key="c.label" class="pkg-check">
            <span :class="c.ok ? 'ck-ok' : 'ck-bad'">{{ c.ok ? "✓" : "✕" }}</span>
            <span class="pkg-check-label">{{ c.label }}</span>
            <span class="muted">{{ c.hint }}</span>
          </div>

          <div class="pkg-sec">解析出的元数据（入口文件 frontmatter）</div>
          <div class="pkg-meta">
            <div><span>名称</span><b>{{ pkgReport.manifest.name }}</b></div>
            <div><span>版本</span><b class="mono">v{{ pkgReport.manifest.version }}</b></div>
            <div><span>图标</span><b>{{ pkgReport.manifest.icon }}</b></div>
            <div class="pkg-meta-wide"><span>描述</span><b>{{ pkgReport.manifest.description }}</b></div>
          </div>

          <div class="pkg-sec">解压后的文件（{{ pkgReport.files.length }}）</div>
          <div class="pkg-entries">
            <span v-for="f in pkgReport.files" :key="f.path" class="chip-entry mono">
              {{ f.path }}
              <i class="file-size">{{ sizeText(f.size) }}</i>
            </span>
          </div>

          <div class="pkg-sec">
            入口文件内容预览
            <span v-if="pkgReport.entryPath" class="muted mono">（{{ pkgReport.entryPath }}）</span>
          </div>
          <pre class="code mono pkg-preview">{{ pkgReport.preview }}</pre>
        </div>
      </div>

      <div class="field-hint" style="margin-top:14px">
        仅支持技能包导入：解析通过后登记为自定义 Skill，可在应用「工具装配」中作为技能模板选用。
      </div>
      <template #foot>
        <button class="btn" @click="closeImport">取消</button>
        <button
          class="btn btn-primary"
          :disabled="!pkgReport?.valid"
          @click="importFromPackage()"
        >
          导入
        </button>
      </template>
    </Modal>

    <!-- 删除确认 -->
    <Modal v-if="delTarget" title="删除 Skill" @close="delTarget = null">
      <div class="del-body">
        <div class="del-ico">⚠️</div>
        <div>
          确认删除 Skill「<b>{{ delTarget.name }}</b>」？
          删除后该技能模板不再可选，正在使用该技能的应用需重新调整工具装配。
        </div>
      </div>
      <template #foot>
        <button class="btn" @click="delTarget = null">取消</button>
        <button class="btn btn-primary danger-btn" @click="confirmDelete">确认删除</button>
      </template>
    </Modal>

    <!-- 被占用，禁止删除 -->
    <Modal v-if="blockTarget" title="无法删除 Skill" @close="blockTarget = null">
      <div class="del-body">
        <div class="del-ico">🚫</div>
        <div>
          Skill「<b>{{ blockTarget.name }}</b>」正被以下应用装配，需先在应用「工具装配」中解绑后再删除：
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
.acts {
  display: flex;
  gap: 6px;
  flex: none;
}
.custom {
  color: var(--teal);
  background: var(--teal-soft);
}
.used-row {
  font-size: 11.5px;
  color: var(--amber);
  margin-top: 8px;
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
.row2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.pkg-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--teal);
  background: var(--teal-soft);
  border-radius: 8px;
  padding: 3px 9px;
  margin-top: 8px;
  align-self: flex-start;
}
.pkg-badge-ico {
  font-size: 12px;
}
.pkg-drop {
  border: 2px dashed var(--border-strong);
  border-radius: 12px;
  padding: 22px 18px;
  text-align: center;
}
.pkg-ico {
  font-size: 28px;
}
.pkg-title {
  font-size: 14px;
  font-weight: 600;
  margin-top: 8px;
}
.pkg-sub {
  font-size: 12px;
  color: var(--text-3);
  margin-top: 6px;
  line-height: 1.6;
}
.pkg-pick {
  margin-top: 14px;
  cursor: pointer;
  display: inline-flex;
}
.pkg-input {
  display: none;
}
.pkg-err {
  color: var(--red);
  font-size: 13px;
  margin-top: 12px;
}
.pkg-parsing {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  font-size: 13px;
  color: var(--brand-700);
  background: var(--brand-soft);
  border-radius: 10px;
  padding: 12px 14px;
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
.pkg-report {
  margin-top: 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
}
.pkg-file {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
}
.pkg-name {
  font-weight: 700;
}
.t-ok {
  color: var(--green);
  background: var(--green-soft);
}
.t-bad {
  color: var(--red);
  background: var(--red-soft);
}
.pkg-sec {
  font-size: 13px;
  font-weight: 700;
  margin: 16px 0 8px;
}
.pkg-check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  padding: 5px 0;
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
.pkg-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.pkg-meta div {
  background: var(--surface-2);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12.5px;
}
.pkg-meta-wide {
  grid-column: 1 / -1;
}
.pkg-meta span {
  display: block;
  font-size: 11px;
  color: var(--text-3);
}
.pkg-meta b {
  font-weight: 600;
}
.pkg-entries {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip-entry {
  font-size: 11.5px;
  color: var(--violet);
  background: var(--violet-soft);
  border-radius: 6px;
  padding: 2px 8px;
}
.pkg-preview {
  max-height: 160px;
  overflow: auto;
  margin: 0;
}
.empty {
  padding: 24px;
  text-align: center;
  color: var(--text-3);
  font-size: 13.5px;
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
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  background: var(--brand-soft-2);
  border: 1px solid var(--brand-soft);
  color: var(--text-2);
  font-size: 13px;
  margin-bottom: 16px;
}
.banner-ico {
  font-size: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.skill-card {
  padding: 18px;
  transition: all 0.18s;
}
.skill-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
}
.skill-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.skill-ico {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: var(--violet-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.preset {
  color: var(--violet);
  background: var(--violet-soft);
}
.skill-name {
  font-size: 16px;
  font-weight: 800;
  margin-top: 14px;
}
.skill-desc {
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.55;
  margin-top: 6px;
  min-height: 42px;
}
.skill-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  gap: 8px;
}
.chip {
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 9px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
