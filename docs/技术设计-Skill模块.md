# Albedo · Skill 模块技术设计（实现级）

> 版本：v1.0
> 日期：2026-10-11
> 技术栈：Java 17 + Spring Boot 3.x + Spring AI（`ToolCallback` 统一工具抽象）；协议调用复用「MCP 网关」
> 关联需求：PRD v0.3 §6.6 Skill 管理（SKL-01～SKL-06）、§6.2 应用管理（工具装配）、§7 非功能需求、§8 技术架构、§9 核心数据模型
> 上下游模块：向下依赖「MCP 网关」完成真实的工具发现与调用；向上为「智能体模块」提供 `ToolCallback`（见智能体模块 §6.3）；导入/删除写「操作日志」

---

## 1. 模块概述

Skill 是与 MCP **完全隔离**的独立资源：它是一份**指令与资料包**（一个文件夹：入口 Markdown 指令 + 参考文档 + 可选脚本/资源），**不绑定、不依赖、不产出 MCP 工具**。

- MCP 提供的是**可调用的工具**（Tool）；
- Skill 提供的是**注入上下文的指令与资料**（Prompt/Context）。

因此本模块是一个**轻量元数据 + 文件管理模块**：负责技能包的登记（预置模板 + 自定义导入）、文件树浏览与在线编辑、占用校验与删除，以及运行时把 Skill 内容注入智能体上下文。

> 说明：PRD §9 曾记录「v0.2 的 `Skill` 独立表已并入 MCP 封装」。本节在 **SKL-03（导入自定义 Skill）/ SKL-04（删除）** 之后重新引入 `skill` 表；**Skill 与 MCP 相互隔离**，表中不再有 `mcp_id`，Skill 不产出任何工具，与「Skill 不是能力执行实体」的既定决策一致。

### 1.1 定位

- **预置模板**：官方打包的指令与资料包（只读目录，`SKL-01`）。
- **自定义导入**：用户上传技能包，平台解压登记（`SKL-02` / `SKL-03`）。
- **文件夹浏览与在线编辑**：按解压后的文件树浏览，直接在文件内修改并保存（`SKL-07` / `SKL-08`）。
- **装配消费**：应用在「Skill 装配」中以 `kind = Skill` 引用，运行时注入上下文（`SKL-06`）。
- **删除治理**：支持删除，并在被应用占用时拦截（`SKL-04` / `SKL-05`）。
- **审计**：导入、编辑、删除写入操作日志。

### 1.2 职责边界

| 职责 | 说明 | 不包含 |
|------|------|--------|
| Skill 元数据与文件管理 | 预置模板只读（修改即派生为自定义）；自定义 Skill 支持导入、文件内编辑、删除 | 二进制资源的在线编辑（仅可下载） |
| 技能包解压与登记 | 安全解压 + 文件清单 + 入口 frontmatter 解析 | MCP 协议适配与工具调用（**与 MCP 完全隔离**） |
| 占用校验 | 删除前校验是否已被应用引用，返回占用应用列表 | 应用侧的引用/解绑交互（应用管理模块） |
| 运行时上下文注入 | 输出入口指令 + 按需资料，供智能体拼装系统提示 | 智能体调度循环、ReAct 控制（智能体模块） |
| 缓存与一致性 | 目录缓存、占用计数缓存与失效策略 | 工具级禁用（MCP 网关 §6.4） |
| 审计埋点 | 导入/编辑/删除写 `operation_log` | 日志查询与导出（操作日志模块） |

### 1.3 术语约定

沿用 PRD §5 并调整：**Skill（技能）** 是一份**指令与资料包**，以文件夹形式组织，与 MCP **相互隔离**——MCP 提供工具，Skill 提供指令与参考资料。

| 术语 | 说明 |
|------|------|
| `Skill / skill 表` | Skill 的元数据记录，本模块唯一持久化实体（无 `mcp_id`） |
| `source` | `PRESET`（官方预置）/ `CUSTOM`（用户导入或由预置派生） |
| `package_files` | 解压后的扁平文件清单，目录层级由前端构建 |
| `entry_path` | 识别出的入口 Markdown 文件，其 frontmatter 是 Skill 元数据源 |
| `ToolBinding` | 应用 `config_json.tools[]` 中的一条引用记录，`kind = KnowledgeBase \| MCP \| Skill` |
| `refId` | `ToolBinding` 中指向被引用资源 id 的字段（此处为 `skill.id`），是**占用校验的唯一依据** |
| `ToolCallback` | 智能体侧工具统一抽象（Spring AI），本模块与 MCP 工具在此归一 |

---

## 2. 核心功能与需求映射

| 编号 | 需求 | 优先级 | 本模块实现要点 |
|------|------|--------|----------------|
| SKL-01 | 预置技能模板 | P2 | 官方目录以种子数据落地，`source = PRESET`，只读保护，随版本发布升级 |
| SKL-02 | 技能包导入（压缩包） | P1 | 上传 `.zip` 技能包或单独 `SKILL.md` → 服务端解压、结构校验、frontmatter 解析 → 注册为自定义 Skill 并落 object storage（详见 §5.4） |
| SKL-03 | 导入 Skill | P1 | **仅保留技能包上传**：`POST /v1/skills/packages/parse` 解析 + `/packages` 落库；已移除手动登记接口 `POST /v1/skills` |
| SKL-07 | Skill 解析详情 | P1 | `GET /v1/skills/{id}` 返回解析视图：元数据 + 包校验项 + **包内结构概览（文件数/目录数/总大小）** + 入口文件 + 占用应用；`GET /v1/skills/{id}/files` 返回**扁平文件清单**，前端据此自行构建文件夹树（不假定目录规范） |
| SKL-08 | Skill 修改 | P1 | **不提供元数据表单**：`PUT /v1/skills/{id}/files` 直接在包内文件中编辑并保存；保存入口文件时重解析 frontmatter 同步元数据，修改预置模板则派生为自定义（§5.6） |
| SKL-09 | Skill 下载 | P1 | `GET /v1/skills/{id}/package` 打包返回原始技能包（含 scripts/assets）；`GET /v1/skills/{id}/skill-md` 返回单文件 `SKILL.md` |
| SKL-04 | Skill 删除 | P1 | `DELETE /v1/skills/{id}`，预置与自定义均可删除；写操作日志 |
| SKL-05 | 删除前置校验 | P1 | 删除前查询占用，命中则返回 `409 SKILL_IN_USE` 并列出占用应用，禁止删除 |
| SKL-06 | 应用侧解绑 | P1 | 应用删除 `ToolBinding` 并保存配置后，占用关系释放，Skill 即可删除 |

### 2.1 Skill 元数据模型

Skill 只描述「封装关系」，字段刻意保持精简：

```jsonc
{
  "id": "sk-9a1b",
  "source": "CUSTOM",              // PRESET | CUSTOM
  "owner_uid": 10247,              // PRESET 时为 null（全局可见）
  "name": "工单查询",
  "desc": "按工单号查询处理进度",
  "icon": "🎫",
  "mcp_id": "mc-001",              // 指向 MCPConfig，Skill 的真实能力来源
  "recommended_tools": ["query_ticket", "list_events"],
  "packaged": true,                 // 是否来自技能包导入
  "version": "1.2.0",               // 技能包版本（缺省 0.0.0）
  "package_url": "skill-package/sk-9a1b/",
  "entry_path": "SKILL.md",         // 识别出的入口文件（可能为 README.md 等）
  "package_files": [                // 解压后的**完整文件清单**（扁平，目录由调用方构建）
    { "path": "SKILL.md", "size": 612, "content": "# 差旅报销助手…" },
    { "path": "docs/差旅报销标准.md", "size": 1024, "content": "市内交通…" },
    { "path": "cover.png", "size": 20480, "binary": true }
  ],
  "created_at": "2026-10-11 18:20",
  "updated_at": "2026-10-11 18:20"
}
```

> `recommended_tools` 为**推荐**而非强制：仍然叠加 MCP 网关的「工具级禁用」结果（MCP 网关 §6.4），禁用工具不会被装载。

### 2.2 占用关系模型

应用侧以 `ToolBinding` 表达挂载，其中 `kind = Skill` 且 `refId = skill.id` 即为一次 Skill 占用：

```jsonc
// application.config_json.tools[]
{ "id": "t-skill-1", "kind": "Skill", "refId": "sk-9a1b",
  "name": "工单查询", "sub": "查询工单状态", "enabled": true, "strategy": "smart" }
```

**占用校验以引用关系为准，而非名称**：`refId` 为强引用；缺失 `refId` 的历史数据回退为 `name` 匹配（兼容期，需在数据迁移后移除）。

---

## 3. 技术选型与依赖

Skill 模块不引入新的中间件，全部复用平台既有基座。

### 3.1 技术栈总览

| 层次 | 选型 | 用途 |
|------|------|------|
| Web/安全 | Spring Boot 3.x + 平台统一鉴权 `@Permission(USER)` | 管理侧 RESTful 接口 |
| 领域/持久化 | MyBatis-Plus + MySQL 8.0 | `skill` 表读写 |
| 缓存 | Redis | Skill 目录缓存、占用计数缓存 |
| 工具抽象 | Spring AI `ToolCallback` | 向智能体输出统一工具 |
| 下游调用 | MCP 网关（`McpSyncClient`） | 真实工具 schema 与调用 |
| 审计 | 操作日志服务 | 导入/删除埋点 |

### 3.2 Maven 依赖（示意）

复用平台父工程版本管理，模块自身**无需新增第三方依赖**：

```xml
<!-- 复用现有 starter，无 Skill 专属依赖 -->
<dependency>
  <groupId>com.albedo</groupId>
  <artifactId>albedo-mcp-gateway-api</artifactId>  <!-- listTools / callTool -->
</dependency>
<dependency>
  <groupId>com.albedo</groupId>
  <artifactId>albedo-common-audit</artifactId>     <!-- @Audit(module = "SKILL") -->
</dependency>
```

### 3.3 核心类/接口速查

| 类 / 接口 | 职责 |
|-----------|------|
| `SkillController` | 暴露 `/v1/skills/**` 管理侧接口 |
| `SkillService` | 列表/详情/导入/编辑/删除，内含占用校验与预置只读约束 |
| `SkillUsageChecker` | 解析应用 `ToolBinding`，回答「被哪些应用占用」 |
| `SkillResolver` | 运行时解析：Skill → (MCP + 推荐工具) → `List<ToolCallback>` |
| `SkillRepository` | `skill` 表访问 |
| `SkillCache` | 目录缓存与占用计数缓存的读写与失效 |
| `McpGateway`（下游） | `listTools` / `callTool`，由 MCP 模块提供 |

---

## 4. 架构设计

### 4.1 模块内部分层

```
┌────────────────────────────── 管理侧（前端 GetSkillUI） ────┐
│ SkillController  /v1/skills/**                              │
│   ├── SkillService  ← SkillUsageChecker（占用校验）          │
│   │                  ← SkillRepository（MySQL: skill）       │
│   │                  ← SkillCache（Redis）                   │
│   └── @Audit → operation_log                                │
└──────────────────────────────┬──────────────────────────────┘
                               │ 运行时（被智能体模块调用）
┌──────────────────────────────▼──────────────────────────────┐
│ SkillResolver                                               │
│   skill → mcp_id + recommended_tools                        │
│        → McpGateway.listTools(appId, mcpId)                 │
│        → 过滤 (全局禁用 ∪ 工具级禁用)                        │
│        → List<ToolCallback> → ToolScheduler（智能体 §6.1）   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 核心组件职责

| 组件 | 输入 | 输出 | 关键约束 |
|------|------|------|----------|
| `SkillService.list` | `uid` | 预置 ∪ 本人自定义，附 `usageCount` | `usageCount` 走缓存，允许短暂不一致 |
| `SkillService.importSkill` | 表单 DTO | `skill` 记录 | 名称同人唯一；`mcp_id` 必须存在且属当前用户 |
| `SkillService.delete` | `skillId` | — | 先查占用，命中则抛 `SkillInUseException` |
| `SkillUsageChecker.appsUsing` | `skillId` | `List<AppRef>` | 强引用优先（`refId`），缺失回退名称 |
| `SkillResolver.resolve` | `appId, skillIds` | `List<ToolCallback>` | 只读，不写库；失败降级为「跳过该 Skill」并告警 |

### 4.3 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Skill 与 MCP 的关系 | **完全隔离**，Skill 不绑定/不依赖/不产出 MCP 工具 | 两者定位不同：MCP 提供可调用工具，Skill 提供注入上下文的指令与资料；耦合会导致「改一个 MCP 影响多个 Skill」的连锁问题 |
| Skill 是否产出工具 | **否**，只产出上下文（指令 + 资料） | 避免第二套执行/沙箱体系；Skill 的价值在于把领域经验固化为指令，而非新增调用能力 |
| 是否恢复 `skill` 表 | **是**，但字段只含元数据与文件清单 | 导入/编辑/删除需要持久化与归属隔离；不引入任何执行能力，不违背 v0.2 的合并结论 |
| 占用校验依据 | `ToolBinding.refId` 强引用 | 名称可重复且可改，强引用才能保证「改名后占用关系不丢失」 |
| 占用存储位置 | 以 `application.config_json.tools` 为准，同步投影到 `tool_app_binding` | `config_json` 是 per-app 策略的真相源；投影表用于 SQL 级快速统计，双写最终一致 |
| 删除被占用时 | **返回 409 并列出占用应用**，不做级联解绑 | 级联可能静默破坏线上应用；必须显式由用户在应用侧确认（SKL-06） |
| 预置模板可删除 | **可以**（同 SKL-04） | 用户可能不需要某些预置能力；但预置模板删除后随版本升级**不再**重新灌入（记录 `preset_tombstone`） |

---

## 5. 关键实现

### 5.1 预置模板的只读保护

```java
void assertEditable(Skill s) {
    if (s.source() == Source.PRESET) {
        throw new BizException(ErrorCode.SKILL_PRESET_READONLY);
    }
}
```

- 预置模板由发布时的种子数据写入，`owner_uid` 为 `null`，对所有用户可见；
- 预置模板**不允许就地修改**：用户点击「修改」时按 §5.6 派生为自定义 Skill（`source=CUSTOM`），保留 `template_key` 溯源，`SKILL_PRESET_READONLY` 仅用于拦截「直接写预置记录」的非法路径；
- 预置模板被删除后写入 `preset_tombstone(template_key)`，避免版本升级时复现。

### 5.2 导入 Skill（SKL-03，仅技能包）

导入入口**只接受技能包**，解析与落库分离（先 `parse` 预览、再 `packages` 确认），便于重复预览与失败重试：

```java
@Audit(module = "SKILL", action = "IMPORT")
public SkillPackageReport parse(Long uid, MultipartFile file) {
    assertPackageSafe(file);                       // 大小/magic/条目数/解压比/路径穿越
    Map<String, byte[]> entries = unzip(file);     // 内存中安全解压
    String skillMd = require(entries, "SKILL.md"); // 缺 SKILL.md → SKILL_PACKAGE_INVALID
    Manifest mf = parseFrontmatter(skillMd);       // name/description 必填
    return new SkillPackageReport(fileInfo, checks, mf, listEntries(entries), preview(skillMd));
}

@Transactional
@Audit(module = "SKILL", action = "IMPORT")
public Skill confirmImport(Long uid, SkillPackageReport report) {
    if (repo.existsByName(uid, report.manifest().name()))
        throw new BizException(ErrorCode.SKILL_NAME_DUPLICATED);
    McpConfig mcp = mcpGateway.getOwned(report.manifest().mcpId(), uid);  // MCP 归属与可用性
    String pkgUrl = objectStore.put("skill-package/" + id + "/", entries);
    Skill saved = repo.insert(Skill.fromPackage(uid, report, mcp.id(), pkgUrl));
    skillCache.evictCatalog(uid);
    return saved;
}
```

> 已移除手动登记（`POST /v1/skills`）：无技能包即无 Skill 元数据来源，保留手动登记会与「Skill = 技能包」的语义冲突，且易产生无正文、无参考资料的空壳 Skill。

### 5.3 删除与占用校验（SKL-04 / SKL-05）

```java
@Transactional
@Audit(module = "SKILL", action = "DELETE")
public void deleteSkill(Long uid, String skillId) {
    Skill s = repo.getOwned(skillId, uid);            // 404 + 归属隔离
    List<AppRef> apps = usageChecker.appsUsing(skillId);
    if (!apps.isEmpty()) {
        throw new SkillInUseException(apps);          // → 409 SKILL_IN_USE + 占用应用列表
    }
    repo.softDelete(skillId);                         // 软删，保留审计线索
    if (s.source() == Source.PRESET) repo.markTombstone(s.templateKey());
    skillCache.evictCatalog(uid);
    skillCache.evictUsage(skillId);
}
```

占用查询实现（强引用优先、名称回退）：

```java
public List<AppRef> appsUsing(String skillId) {
    Skill s = repo.getById(skillId);
    return appRepo.findAll().stream()
        .filter(a -> parseTools(a.configJson()).stream().anyMatch(t ->
            t.kind() == Kind.Skill &&
            (t.refId() != null ? skillId.equals(t.refId()) : s.name().equals(t.name()))))
        .map(a -> new AppRef(a.id(), a.name()))
        .toList();
}
```

> 生产态应改为「先在 `tool_app_binding` 投影上做一次 SQL 聚合过滤，再解析少数候选应用的 `config_json`」，避免全表扫描；此处为可读性保留直白写法。

### 5.4 技能包导入管线（SKL-02）

**包结构：推荐约定，不强制**

下列为**推荐**结构，平台**不假定**任何固定目录或文件名，解析结果一律以「解压后的实际文件清单」表达：

```
my-skill.zip
├── SKILL.md              # 推荐入口：YAML frontmatter 元数据 + Markdown 指令正文
├── references/           # 可选：参考资料（如制度说明、术语表）
│   └── 差旅报销标准.md
├── scripts/              # 可选：辅助脚本（仅静态登记，不执行）
│   └── extract_invoice.py
└── assets/               # 可选：封面、示例图片等静态资源
    └── cover.png
```

用户上传的包可以是任意结构（例如 `docs/`、`templates/`、入口叫 `README.md`），平台据此构建**文件夹树**展示，不做目录名校验。

**入口文件识别顺序**：根目录 `SKILL.md` → 根目录其它 `.md` → 递归首个 `.md`；识别不到则标记 `entryPath` 为空并在导入报告中提示。

`SKILL.md` 的 frontmatter 至少包含 `name` 与 `description`，可选 `version` / `mcp` / `icon` / `allowed_tools`：

```md
---
name: 差旅报销助手
description: 依据差旅制度回答报销标准与流程
version: 1.2.0
mcp: finance-mcp
icon: 🧾
---

# 差旅报销助手
（指令正文：适用场景、处理流程、约束…）
```

**导入流程**

```
上传 multipart(file)
  → ① 大小与格式校验（≤10MB、zip/md、magic number）
  → ② 安全解压：限制条目数/单文件大小/总解压比，校验路径穿越（拒绝 ../ 与绝对路径）
  → ③ 文件清单与入口识别：产出扁平文件清单（path/size/binary/content）；按上述顺序识别入口 Markdown
  → ④ 解析 frontmatter → SkillPackageManifest（缺 name/description 直接拒绝）
  → ⑤ 资源落对象存储 `skill-package/{skill_id}/**`，SKILL.md 正文入库
  → ⑥ 生成 skill 记录(source=CUSTOM, version, package_entries)，写 @Audit(IMPORT)
  → 返回 SkillPackageReport（校验项 + 元数据 + 资源清单 + 正文预览）
```

> 前端以该报告驱动「解析结果」面板（校验清单 / 元数据 / 资源清单 / 内容预览），用户确认后才真正落Skill；解析与落库分离，便于重复预览与失败重试。

**关键约束**

| 约束 | 说明 |
|------|------|
| 必填元数据 | `name` / `description` 缺失返回 `SKILL_PACKAGE_INVALID` |
| 目录规范 | **不强制**：任何目录名/文件名均接受，仅入口 Markdown 为软要求（识别不到仅提示，不阻断） |
| MCP 绑定 | frontmatter 的 `mcp` 若在本用户 MCP 列表中不存在 → 拒绝并要求先接入 MCP |
| 脚本执行 | 本期 `scripts/` 仅登记不执行，Skill 的能力一律通过 MCP 工具输出 |
| 同名处理 | 同一用户下重名返回 `SKILL_NAME_DUPLICATED`，引导改名或升级版本 |
| 版本 | `version` 缺省记为 `0.0.0`；后续迭代支持同 `template_key` 的版本升级与灰度 |

### 5.5 应用侧解绑（SKL-06）

应用删除 `ToolBinding` 并保存配置后，`tool_app_binding` 投影同步删除，`usageCount` 归零，Skill 即可删除：

```
DELETE /v1/apps/{appId}/tools/{toolId}   # 删除 ToolBinding
  → config_json.tools 移除该条
  → tool_app_binding 删除 (app_id, tool_id)
  → 失效 skill:usage:{skill_id} 与 app 工具缓存
  → 再次 DELETE /v1/skills/{id} 即成功
```

### 5.6 文件内编辑保存与下载（SKL-08 / SKL-09）

**修改方式**：不提供独立的「元数据表单」，用户**直接在包内文件树中编辑文件内容并保存**（所见即所得，避免表单与文件两处真相源）。保存入口文件时，服务端重新解析其 frontmatter 并同步 Skill 元数据：

```java
@Transactional
@Audit(module = "SKILL", action = "UPDATE_FILE")
public Skill saveFile(Long uid, String skillId, String path, String content) {
    Skill s = repo.getOwned(skillId, uid);
    PackageFile f = requireFile(s, path);
    assertText(f);                                   // 二进制资源不可编辑
    f.write(content);                                // 落对象存储 + 更新 size
    if (path.equals(s.entryPath())) {                // 入口文件：重解析 frontmatter
        Manifest mf = parseFrontmatter(content);
        s.applyManifest(mf);                         // name/description/version/icon
        if (s.source() == Source.PRESET) {
            s = repo.deriveAsCustom(s);              // 预置被修改 → 派生为自定义，保留 template_key
        }
    }
    skillCache.evictCatalog(uid);
    skillCache.evictContextBySkill(s.id());          // 引用该 Skill 的应用需重新注入上下文
    return s;
}
```

**下载**：由服务端按原始资源重新打包，避免前端拼接导致内容漂移：

```
GET /v1/skills/{id}/package   → application/zip   # 完整技能包（按原始文件清单重新打包，含二进制资源）
GET /v1/skills/{id}/export    → text/markdown     # 单文件导出：元数据头 + 各文件分节（<!-- ===== path ===== -->）
```

导出与打包均**按 `package_files` 的实际条目生成**，不依赖任何固定目录名；二进制资源在 Markdown 导出中标注占位并提示改用 `/package`。

- 下载需鉴权且**仅限本人资源**；`/package` 打包前做病毒扫描结果校验；
- 版本号写入响应头 `X-Skill-Version`，便于业务方做版本比对。

### 5.7 运行时装载：Skill → 上下文注入（非工具）

Skill **不产出 `ToolCallback`**。运行时把 Skill 的内容拼装进智能体的系统提示/上下文：

```java
SkillContext resolve(Long appId, List<String> skillIds) {
    List<Skill> skills = skillIds.stream().map(repo::getById).filter(Objects::nonNull).toList();

    // 1) 入口 Markdown 的正文（去掉 frontmatter）作为指令段
    String instructions = skills.stream()
        .map(s -> s.manifest().name() + "（v" + s.version() + "）\n" + bodyOf(s.entryPath()))
        .collect(joining("\n\n---\n\n"));

    // 2) 参考资料按需注入：默认只给清单，模型显式请求时再加载全文
    List<ResourceRef> refs = skills.stream()
        .flatMap(s -> s.packageFiles().stream()
            .filter(f -> !f.binary() && !f.path().equals(s.entryPath()))
            .map(f -> new ResourceRef(f.path(), f.size())))
        .toList();

    return new SkillContext(instructions, refs, totalChars(skills));
}
```

- **与 MCP 无交集**：不调用 MCP 网关、不受工具级禁用影响，MCP 故障不影响 Skill 注入；
- **按需加载**：参考资料先给「路径 + 摘要」，模型需要时通过内部接口取全文，避免上下文膨胀（见 §11）；
- 入口文件缺失时注入为空并记 `WARN`，不阻断应用运行。

---

## 6. 关键流程

### 6.1 导入 Skill

```
用户填写 名称/描述/图标/绑定 MCP  → POST /v1/skills
  → 校验：名称唯一、MCP 归属与可用性、推荐工具存在性
  → INSERT skill(source=CUSTOM, mcp_id, recommended_tools)
  → 失效 skill:catalog:{uid}
  → @Audit 写 operation_log(module=SKILL, action=IMPORT, result=success)
  → 返回 Skill（列表页标识为「自定义」）
```

### 6.2 删除（含占用拦截）

```
用户点击「删除」
  → DELETE /v1/skills/{id}
  → SkillUsageChecker.appsUsing(id)
      ├─ 命中 → 409 SKILL_IN_USE { apps:[{id,name}] }
      │        前端展示「无法删除」弹窗并列出占用应用（引导去应用侧解绑）
      └─ 未命中 → 软删 + 失效缓存 + @Audit(DELETE)
                 （预置模板写 preset_tombstone，避免升级复原）
```

### 6.3 运行时调用链路

```
应用对话请求 → 智能体 ToolScheduler
  → SkillResolver.resolve(appId, skillIds)
      → McpGateway.listTools(appId, mcpId)     # Skill → MCP
      → 推荐工具过滤 + 工具级禁用过滤
      → List<ToolCallback>（TracingToolCallback 装饰）
  → 模型发起 tool_call → McpGateway.callTool → trace 落库
```

---

## 7. 接口定义

### 7.1 管理侧接口（RESTful，`@Permission(USER)`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/v1/skills` | Skill 列表（预置 ∪ 本人自定义），**每项附 `usageCount`** |
| GET | `/v1/skills/{id}` | 详情（含绑定 MCP 与推荐工具） |
| POST | `/v1/skills/packages/parse` | **multipart 上传技能包并仅解析**：返回校验项、frontmatter 元数据、资源清单与正文预览，不落库（SKL-02） |
| POST | `/v1/skills/packages` | 技能包确认导入：按解析结果生成 `skill` 记录并落资源（SKL-02） |
| PUT | `/v1/skills/{id}/files` | **保存包内文件内容**（body 传 `path` + `content`）；保存入口文件时同步刷新 Skill 元数据，修改预置模板即派生为自定义（SKL-08） |
| GET | `/v1/skills/{id}/files` | 技能包**扁平文件清单**（`path`/`size`/`binary`/`content`），前端构建文件夹树（SKL-07） |
| GET | `/v1/skills/{id}/package` | 打包下载完整技能包 zip（含二进制资源，SKL-09） |
| GET | `/v1/skills/{id}/export` | 按实际文件导出单文件 Markdown（元数据头 + 各文件分节，二进制资源标注占位，SKL-09） |
| PUT | `/v1/skills/{id}` | 编辑（仅 `CUSTOM`，`PRESET` 返回只读错误） |
| DELETE | `/v1/skills/{id}` | 删除；被占用返回 409（SKL-04 / SKL-05） |
| GET | `/v1/skills/{id}/usage` | 占用该 Skill 的应用列表（供删除前置提示与二次确认） |
| GET | `/v1/skills/templates` | 官方预置模板目录（SKL-01） |

### 7.2 错误码约定

| HTTP | code | 触发场景 | 响应要点 |
|------|------|----------|----------|
| 409 | `SKILL_IN_USE` | 删除时仍有应用装配 | `{ apps: [{ id, name }] }`，供前端列出占用应用 |
| 404 | `SKILL_NOT_FOUND` | 不存在或不属于当前用户 | 归属隔离，不暴露他人资源 |
| 400 | `SKILL_NAME_DUPLICATED` | 同用户内名称重复 | `{ field: "name" }` |
| 400 | `SKILL_PRESET_READONLY` | 修改预置模板 | 提示「预置模板只读，请先导入为自定义」 |
| 400 | `SKILL_MCP_INVALID` | 绑定 MCP 不存在/不可用/推荐工具不存在 | `{ mcpId }` |
| 400 | `SKILL_PACKAGE_INVALID` | 技能包缺 `SKILL.md`、frontmatter 缺字段、格式/大小/路径非法 | `{ checks: [...] }` 携带逐项校验结果 |

### 7.3 内部接口（供智能体运行时）

| 方法 | 说明 |
|------|------|
| `resolve(appId, skillIds)` | 返回 Skill 解析后的 `List<ToolCallback>`（已叠加禁用过滤） |
| `countUsage(skillId)` | 返回占用应用数（读缓存，允许秒级延迟） |
| `appsUsing(skillId)` | 返回占用应用列表（强一致读库，用于删除校验） |

---

## 8. 数据结构

### 8.1 数据库表

**`skill`**（Skill 元数据，MySQL 8.0）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | varchar(32) | 主键 |
| `template_key` | varchar(64) | 预置模板唯一标识；自定义为空 |
| `source` | varchar(16) | `PRESET` / `CUSTOM` |
| `owner_uid` | bigint | 归属用户；`PRESET` 为 `NULL`（全局可见） |
| `name` | varchar(64) | Skill 名称 |
| `description` | varchar(512) | 描述 |
| `icon` | varchar(16) | 展示图标（emoji） |
| ~~`mcp_id`~~ | — | **已移除**：Skill 与 MCP 隔离，不再绑定任何 MCP 服务 |
| ~~`recommended_tools`~~ | — | **已移除**：Skill 不产出工具，无推荐工具概念 |
| `version` | varchar(32) | 技能包版本（frontmatter `version`，缺省 `0.0.0`） |
| `packaged` | tinyint | 是否由技能包导入（1=技能包） |
| `package_url` | varchar(255) | 技能包资源在对象存储的路径 `skill-package/{skill_id}/**` |
| `entry_path` | varchar(255) | 识别出的入口文件路径（不假定 `SKILL.md`） |
| `package_files` | json | 解压后的**扁平文件清单** `[{ path, size, binary, content? }]`，目录层级由前端按需构建 |
| `manifest` | json | 入口文件 frontmatter 解析结果（name/description/version/mcp/icon） |
| `deleted` | tinyint | 软删标记（保留审计线索） |
| `created_at` / `updated_at` | datetime | 时间戳 |

> 索引：`idx_owner (owner_uid, deleted)`、`uk_template_key (template_key)`、`idx_mcp (mcp_id)`。
> 名称唯一建议：对 `(owner_uid, name, deleted=0)` 建唯一索引，由数据库兜住并发重名。

**`tool_app_binding`**（复用 PRD §9 既有表，作为占用的 SQL 投影）

| 字段 | 说明 |
|------|------|
| `app_id` + `tool_id` | 联合唯一，per-app 挂载记录 |
| `ref_id` | 与 `ToolBinding.refId` 一致，`kind = Skill` 时为 `skill.id` |
| `enabled` | per-app 禁用标记 |

### 8.2 缓存

| Key | 内容 | 失效时机 |
|------|------|----------|
| `skill:catalog:{uid}` | 用户可见 Skill 列表（含 `usageCount`） | 导入/编辑/删除 Skill、应用解绑后失效 |
| `skill:usage:{skill_id}` | 占用应用数 | 应用保存配置（`ToolBinding` 变更）时失效 |
| `skill:resolve:{app_id}` | 该应用 Skill 解析后的工具清单 | 应用保存配置、MCP 工具变更、禁用变更时失效 |

### 8.3 领域对象（示意）

```java
public record Skill(
    String id, String templateKey, Source source, Long ownerUid,
    String name, String desc, String icon,
    String mcpId, List<String> recommendedTools, boolean deleted
) {
    public boolean editable() { return source == Source.CUSTOM && !deleted; }
}

public record SkillUsage(String skillId, int count, List<AppRef> apps) {}
public record AppRef(String id, String name) {}
```

### 8.4 建议包结构

```
com.albedo.skill
├── controller/     SkillController
├── service/        SkillService、SkillUsageChecker
├── resolver/       SkillResolver（运行时装配）
├── domain/         Skill、Source、ImportSkillCmd
├── repo/           SkillRepository、SkillMapper
├── cache/          SkillCache
└── exception/      SkillInUseException、ErrorCode
```

---

## 9. 依赖关系

### 9.1 依赖的模块

| 模块 | 依赖点 | 说明 |
|------|--------|------|
| 对象存储 | 技能包资源的读写（`skill-package/{skill_id}/**`） | 文件清单与内容持久化 |
| 应用管理 | 读取 `config_json.tools`、`tool_app_binding` | 占用校验的数据来源；同时也是引用/解绑入口 |
| 统一鉴权 | `@Permission(USER)` | 资源私有、按用户隔离（PRD：取消工作空间） |
| 操作日志 | `@Audit` 埋点 | 导入/删除写 `operation_log` |

### 9.2 被依赖方

| 模块 | 消费点 |
|------|--------|
| 智能体模块 | `SkillResolver.resolve()` 输出指令与资料上下文，拼入系统提示（智能体模块 §6.3） |
| 前端管理台 | `/v1/skills/**` 列表、导入、删除与占用提示 |
| 应用管理 | 工具装配可选 Skill；保存配置后释放占用关系 |

### 9.3 与 MCP 模块的关系：隔离，不联动

- **无外键、无调用**：`skill` 表不含 `mcp_id`，运行时不请求 MCP 网关；
- **互不阻断**：MCP Server 删除/故障不影响 Skill 的注入，Skill 删除/编辑也不影响任何 MCP 工具；
- **并列装配**：应用可在「工具装配」中同时引用 MCP（工具）与 Skill（指令资料），二者独立解析、互不干扰；
- 前端展示上两者分属不同模块（MCP 管理 / Skill 管理），**Skill 页面不再提供「装配到应用」入口**，装配动作统一在应用侧完成。

---

## 10. 非功能性要求

### 10.1 性能

- Skill 列表为低频读，走 `skill:catalog:{uid}` 缓存（60s），`usageCount` 走 `skill:usage` 缓存，允许秒级延迟；
- 删除校验使用**强一致**读库路径，避免「刚解绑」或「刚装配」的边界误判；
- `SkillResolver` 结果按 `app_id` 缓存，避免每次对话都触发 `tools/list`；工具 schema 变更时由 MCP 网关事件失效缓存。

### 10.2 安全

- 全部接口 `@Permission(USER)`，`owner_uid` 强校验：**预置除外，用户只能读写自己的自定义 Skill**；
- 删除他人资源一律返回 404（不泄露存在性）；
- `mcp_id` 必须属于当前用户，防止越权绑定他人 MCP 间接拿到其工具能力。

### 10.3 可用性与一致性

- Skill 删除采用**软删**，保留审计线索；
- `config_json` 与 `tool_app_binding` 双写：以 `config_json` 为真理源，通过应用保存事务同步投影；提供对账任务校正漂移；
- 并发删除同一 Skill：依赖 `(owner_uid, name)` 唯一索引与乐观锁，重复删除幂等返回成功。

### 10.4 可观测性

- 埋点：`skill.import` / `skill.delete` 的成功失败、耗时；`skill.resolve` 的解析耗时与解析出的工具数；
- 告警：resolve 失败率上升（多为 MCP 不可用）、占用校验 SQL 慢查询、缓存穿透（大量 `usage` miss）。

---

## 11. 潜在风险与应对措施

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 名称匹配的占用关系被「改名」打破 | 误判未占用 → 删除后应用工具失效 | 强制写入 `refId` 强引用；历史数据迁移补齐后移除名称回退 |
| `config_json` 与投影表不一致 | 占用漏判/误判 | 以 `config_json` 为真相源，删除校验强一致读；定时对账修复投影 |
| 依赖的 MCP Server 被删除或故障 | 应用侧工具突然缺失 | MCP 删除前置校验（§9.3）；resolve 降级跳过 + `WARN` 告警 + 前端标注 |
| 预置模板升级与用户删除冲突 | 删除后又被版本升级灌回 | `preset_tombstone` 墓碑记录，升级时跳过 |
| 预置模板被删除影响官方能力推荐 | 用户找不到预置能力 | 允许删除（SKL-04），同时在导入弹窗提供「从预置模板导入」，可一键恢复为自定义 |
| 占用提示后用户不知如何解绑 | 资源长期无法删除 | 占用弹窗直接给出占用应用入口；应用「工具装配」提供解绑（SKL-06） |
| 压缩包炸弹 / 解压比过高 | 磁盘与 CPU 被打满 | 限制条目数、单文件大小与总解压比；流式解压并在超限后立即中断 |
| 路径穿越（`../`、绝对路径、软链接） | 覆盖宿主文件 | 逐条校验规范化路径必须位于解压根目录内，拒绝符号链接条目 |
| 包内 `scripts/` 被直接执行 | 任意代码执行 | 本期脚本仅登记不执行，能力一律由 MCP 输出；后续若执行需进沙箱并单独授权 |
| `SKILL.md` 指令注入（提示词越权） | 诱导模型绕过约束或泄露数据 | 正文作为**应用侧可选注入**内容，需用户确认；入库前做敏感指令扫描并在导入报告提示 |
| 技能包资源未清理 | 删除 Skill 后 OSS 垃圾堆积 | 删除时同步删除 `skill-package/{skill_id}/**`，并提供对账清理任务 |
| 包结构非标准导致入口识别失败 | 无正文可用、运行时空指令 | 入口识别为**软要求**（识别失败仅提示不阻断）；详情页文件树让用户自行查看/确认入口；缺失入口时运行时不注入指令并告警 |
| 深层嵌套 / 超大文件清单拖慢详情渲染 | 文件树卡顿 | 服务端限制树深度与条目数；前端默认折叠二级以下目录并按需懒加载文件内容 |
| Skill 内容过大导致上下文膨胀 | 挤占模型窗口、成本上升、回答稀释 | 入口指令有字数上限；参考资料**按需加载**（先给清单，模型请求再取全文）；超限时提示用户精简或拆分 Skill |
| 用户直接改文件导致 frontmatter 破损 | 元数据错乱或丢失 | 保存入口文件时做 frontmatter 语法校验，失败则拒绝保存并高亮错误行；保留上一版本供回滚 |
| 二进制资源被误编辑 | 文件损坏 | 服务端 `assertText` 拦截，前端对二进制文件只读并提示下载 |

---

## 12. 与原型实现的对照

本节对应已落地的前端原型（高保真演示，数据源为 `prototype/src/data/mock.ts`）：

| 技术方案（本文） | 原型实现 |
|------------------|----------|
| `/v1/skills` 列表附 `usageCount` | `views/skills/SkillList.vue` 卡片展示「被 N 个应用使用」 |
| 导入自定义 Skill | 列表页「+ 导入 Skill」按钮 + 导入弹窗，**仅保留上传技能包**（已移除手动登记） |
| `GET /v1/skills/{id}` 解析详情 | `views/skills/SkillDetail.vue`（路由 `/skills/:id`）两个 Tab：**解析详情**（元数据/包校验项/包内结构概览/入口文件/占用应用）与**包内文件** |
| `GET /v1/skills/{id}/files` 文件树 | 「包内文件」页由扁平 `package_files` 在前端递归构建**文件夹树**（目录/文件按层级缩进、可折叠、目录优先排序），点击文件在右侧预览内容；二进制资源显示占位提示。演示包刻意使用 `docs/`、`templates/` 等非标准目录以验证通用性 |
| `PUT /v1/skills/{id}/files` 文件内编辑 | 详情页「包内文件」右侧为可编辑文本域，改动后显示「未保存」标记，点击**保存**写入；保存入口文件会重解析 frontmatter 并同步标题/描述/版本/图标（实测改名称后详情页标题与下载文件名同步变化） |
| 无「装配到应用」入口 | Skill 管理列表页卡片仅保留「删除」，装配动作统一在应用侧完成（Skill 与 MCP 隔离，页面不再出现 MCP 相关标签） |
| `GET /v1/skills/{id}/export` 下载 | 详情页「下载」：前端用 Blob 按 `package_files` 实际条目真实生成并下载 `{name}-package.md`（元数据头 + 各文件分节）；完整 zip 包需服务端 `/package` 接口 |
| `POST /v1/skills/packages/parse` 解析报告 | 弹窗内展示：包校验项（SKILL.md/frontmatter/脚本/大小）、解析出的元数据、包内资源清单（`references/`、`scripts/`、`assets/`）、正文预览 |
| 技能包来源标识 | 卡片展示「📦 技能包 v{version} · N 篇参考」 |
| 真实 `SKILL.md` 解析 | 原型在浏览器侧用 `File.text()` 真实读取所选 `.md` 并解析 YAML frontmatter；`.zip` 因无法在前端解压，以固定演示数据模拟服务端解析结果 |
| 删除 + 占用校验 409 | 点击删除 → 若被占用弹出「无法删除」弹窗并列出占用应用；未占用才进入二次确认删除 |
| 占用解析「强引用优先、名称回退」 | `src/store/usage.ts` 的 `skillUsage()`，依赖 `ToolBinding.refId` |
| 应用侧解绑释放占用 | `views/apps/AppDetail.vue` 工具行的「解绑」按钮，保存配置后生效 |
| 预置/自定义标识 | Skill 卡片 `source` 标签：`预置` / `自定义` |

> 原型通过 mutations mock 数组模拟持久化，刷新即重置；真实实现需落地 §8 的 `skill` 表与 §5 的校验链路。

---

