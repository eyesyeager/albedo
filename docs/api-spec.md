# Albedo 接口契约（API Spec）

**版本**：V1.3.0
**日期**：2026-08-24
**维护人**：@架构设计师（唯一维护人）
**上游基线**：`docs/prd.md` **V1.4**、`docs/requirements.md` V1.2、`docs/architecture.md` **V1.4.8**、《团队协作基础框架 v3.1》§十四

> 🔴 本文是**前后端与测试的唯一接口契约来源**。
> 🔴 任何接口新增/变更/错误码新增，必须**先改本文并广播** @前端 + @后端 + @测试，再实现。
> 🔴 与本文不一致的实现视为缺陷（框架 §八 质量红线第 3 条）。

**本版范围（V1.1.2）**：接口清单**无新增**、错误码**无新增**、`sys_config` 键**无新增**；仅对 @后端 M3 能力层交付后提出的 **12 项契约缺口 G1~G12** 做逐条裁决与定点回写（新增 **1 个 audit action** `mcp.tool_grant_revoked`、1 份**内置本地 Tool 清单**、1 处**变量优先级重写**、4 处**可实现口径固化**）。逐条见 §9 变更记录 V1.1.2。M1 已交付契约保持不变。

**本版范围（V1.1.3，🔴 定点裁决，无全文重排）**：@后端 M3 第三阶段（流编排 + 高风险确认闭环）交付后提出的 **5 项契约缺口（①③④⑤⑥）** 逐条裁决 + **2 项收尾判定（G10 / §7.12）**。
🔴 **接口清单零新增、错误码零新增、DDL 零变更**；新增 **1 个 `sys_config` 键**（`chat.system_prompt_max_chars`，§7.1.2）与 **1 个 audit action**（`tool.confirm_conflict`，§7.14）。
定点变更共 9 处：**§7.5.2 第 3 条重写**（订正"用 `max_output_tokens` 当输入预算"的契约错误，改为字符预算 + fail-closed）、**新增 §7.6.5 模型函数名归一化**、**§7.8.1 增补 `running → denied` 迁移**、**§7.8.2 冲突矩阵按 `decision` 列细分 + 回放不写审计 + 冲突留痕**、**§7.3.1 G10 补齐行为定义**、**§7.11.1 聚合口径补注（公式不变）**、**§7.12 标注 SSE 内 `10005` 为一期不可达的契约预留**、**§7.14 action 表 + §7.1.2 键表**、**§8.2 增补 7 条核对项**。逐条见 §9 变更记录 V1.1.3。

**本版范围（V1.1.4，🔴 M3 契约收尾定点裁决，无全文重排）**：@后端 M3 第四阶段（`mvn -B verify` 单测 240 / 集成 227 全绿）后提出的 **7 项契约缺口 #1~#7** 逐条裁决。
🔴 **接口清单零新增、错误码零新增、DDL 零变更**；新增 **2 个 `sys_config` 键**（`observability.analytics_enabled`、`observability.analytics_sample_rate` —— 二者此前从未在任何登记表登记，属**登记缺口**而非新键，键总数 23 → **25**）。
定点变更共 9 处：**§7.1.2 补登两键 + 键总数改 25 + 采样率区间不变量**、**§7.10.1 埋点开关/采样改 fail-closed**（否决 @后端 的 fail-open）、**§7.3.1 末增补运行时 `30060` 载荷形状裁定**（修正：终端用户路径**不带** `violations`）、**§5.2 `error` 事件补注禁带 `violations`**、**§7.6.3 增补「授权点查必须发生在每次执行前」**（订正实现缺口，覆盖 MCP 与本地 Tool）、**§7.1.2 运行时查询次数口径改 ≤5 次**（订正原 ≤3 次的错误表述）、**§7.8.2 ⑤ / §7.14 `tool.confirm_conflict` 事务边界改独立短事务**、**§3 增补 `@TenantRole` 程序化兜底纪律**、**§8.3 新增 M3 签署核对清单**。逐条见 §9 变更记录 V1.1.4。

**本版范围（V1.1.5，🔴 M3 最后一轮契约订正，快进快出，无全文重排）**：@测试 与 @后端 在 M3 终验并行期提出的 **6 项待裁决（G-0~G-5）**。
🔴 **接口清单零新增、错误码零新增、`sys_config` 键零新增（总数仍为 25）、audit action 零新增（仍为 12 个）、DDL 零变更**。
定点变更共 8 处：**§7.14 / §8.3 F3 审计 action 数量文字订正为 12 项**（G-0，登记表为基线）、**§7.6.3 三张表 `deleted_at` 判据订正**（G-1，0 DDL）、**§7.6.3 增补「执行前点查的复查范围不含能力绑定」+ 绑定为生成期快照**（G-2）、**§5.2 `error` 事件三字段形状追认并由"可选"改为"字段恒存在、值可为 `null`"**（G-3）、**§3 / §7.2.1 / §8.2 `@Permission(ADMIN)` 端点失败码二分口径统一**（G-5）、**§8.3 A4 / C3 / C5 / C7 / D3 / F3 判据订正（🔴 条目编号与总数不变，仍 58 项）**。
🔴 G-4（平台层 `@Permission` 兜底）维持二期技术债，技术侧落点见 `architecture.md` §8.2.1 纪律 8 / AR-018。逐条见 §9 变更记录 V1.1.5。

**本版范围（V1.2.0，🔴 单点契约裁决，无全文重排）**：真实上游触发 **G6 自己预留的裁决点** —— 为 `gift` 租户接入腾讯云 WSA MCP（实测为 MCP 旧版「HTTP+SSE」**2024-11-05** 异步推送形态，且**不存在** Streamable HTTP 端点变体）。
🔴 **接口清单零新增、错误码零新增、DDL 零变更、audit action 零新增（仍 12）、`data.result` 字面量零扩充（仍 9）**；新增 **2 个 `sys_config` 键**（`mcp.sse_legacy_enabled` / `mcp.sse_stream_max_bytes`，键总数 27 → **29**）。
定点变更共 7 处：**§7.6.1 `sse` 行重写 + G6 标记失效 + 新增 G6′ 裁决**（形态自适应，🔴 不新增 `sse_legacy` 传输枚举；`initialize` 兼作探测与握手；deadline 预算制；SSRF 纪律加严两条）、**§7.6.2 新增 G9′**（G9 的限定修订：`sse` 允许 exchange 内一次性握手，`streamable_http` 逐字不变）、**§7.4.2 `protocol_incompatible` 行细化 + G9 框补 V1.2.0 订正**、**§7.4.3 增补 discover 的 deadline 预算与翻页不重置预算**、**§7.1.2 新增 2 键 + 键总数改 29**、**§7.13 新增异步形态 Mock 端点与 6 个场景**、**§8.3 新增 I 组核对项**。逐条见 §9 变更记录 V1.2.0。

**本版范围（V1.2.1，🔴 G6′ 内部冲突消除 + 超时键订正，快进快出，无全文重排）**：@后端 按 ADR-016 交付（`mvn -o test` 320 passed）后提出的 **5 点确认**的契约侧落点。
🔴 **接口清单零新增、错误码零新增、`sys_config` 键零新增（仍 29）、audit action 零新增（仍 12）、`data.result` 字面量零扩充（仍 9）、DDL 零变更、🔴 零业务代码返工**。
定点变更共 5 处：**§7.6.1 G6′ ② 步骤 1 重写**（🔴 GET 非 2xx **二分**：`3xx`/`401`/`403`/`407` 直接失败、其余退化直接 POST —— 消除与 `architecture.md` ADR-016 失败分类表的字面冲突）、**§7.6.1 G6′ ⑤ + §7.4.2 连接测试预算键订正**（🔴「连接测试 = `mcp.connect_timeout_seconds`」**作废** → **`mcp.discover_timeout_seconds`**）、**§7.6.1 G7 表格补注**（`connect_timeout_seconds` 当前无代码消费点，语义收窄为运维不等式参照 + 二期握手预算键）、**§7.4.2 `timeout` 行补注**（流被 `HttpRequest.timeout` 打断 → `timeout`，🔴 不是 `protocol_incompatible`）、**§8.3 I 组新增 I10 + I3 判据细化 + I 组验证形态与门禁说明**。逐条见 §9 变更记录 V1.2.1。

**本版范围（V1.2.2，🔴 MCP 联网搜索第 1 轮验收 2 个 P1 的契约侧落点，快进快出，无全文重排）**：`docs/test-report.md` V4.0 的 **BUG-MCP-001 / BUG-MCP-002** 裁决（技术决策见 `architecture.md` **ADR-017 / ADR-018**）。
🔴 **接口清单零新增、错误码零新增（超时仍复用 `50002` + `finishReason=timeout`）、DDL 零变更、audit action 零新增（仍 12）、SSE 事件名零新增（仍 5 个）**；新增 **3 个 `sys_config` 键**（`chat.generation_deadline_seconds` / `chat.deadline_grace_seconds` / `chat.tool_usage_guideline`，键总数 29 → **32**）与 **1 个 SSE 字段**（`tool.confirmExpiresInSeconds`）。
定点变更共 6 处：**§5.2 `tool` 事件新增 `confirmExpiresInSeconds` + `done.finishReason=timeout` 的触发口径补注**（🔴 明确"生成总预算耗尽"与"上游无响应"同码同 finishReason）、**§7.1.2 新增 3 键 + 键总数改 32 + 两条拒绝启动不变量**、**新增 §7.5.2 ⑥「平台级工具调用纪律段」**（🔴 独立 system 消息、🔴 不计入 `system_prompt_max_chars`）、**§7.6.4 新增「失败回灌的诊断来源二分」裁决**（🔴 上游/校验器诊断可回灌，平台/传输诊断不可回灌）、**§7.8.1 ④ 确认等待上限口径订正**（被生成预算收紧且必须下发实际上限）、**§8.3 新增 J 组核对项 J1~J10**（🔴 A~I 组编号与判据零变化）。逐条见 §9 变更记录 V1.2.2。
⚠️ **V1.2.4 提示**：本段 V1.2.2 所述「**独立 system 消息**」已被 **V1.2.4 作废并订正**（见下）—— 保留原文仅为版本追溯，🔴 **实现与验收一律以 V1.2.4 口径为准**。

**本版范围（V1.2.4，🔴 ADR-018 ② 的契约性订正：上游消息形态适配，快进快出，无全文重排）**：`docs/test-report.md` V4.1 的 **BUG-MCP-004** 裁决（技术决策见 `architecture.md` **ADR-019**，V1.4.4）。
🔴 **接口清单零新增、错误码零新增、`sys_config` 键零新增（仍 32）、🔴 键值零变更（`chat.tool_usage_guideline` 文案一字不改）、audit action 零新增（仍 12）、SSE 事件名与字段零变更、DDL 零变更**。
🔴 **核心订正**：平台纪律段的注入形态由「**独立的第二条 `system` 消息**」→「**合并进唯一的 `system` 消息、恒为末块**」。原形态被真实上游否证（混元 OpenAI 兼容接口 `status=400`「`messages` 中 system 角色必须位于列表的最开始」），导致**所有**下发了 `tools` 的生成在进入工具调用前即 `error(50002)` + `done(failed)`（实测含 `calculator`）。🔴 **原「不计入 `chat.system_prompt_max_chars`」原样保留** —— 物理合并 ≠ 预算合并，`30060` 判定对象恒为**租户段**。
定点变更共 6 处：**§7.1.2 `tool_usage_guideline` 行注入形态订正 + 文案纪律补注**、**§7.5.2 注入位置块重写**（🔴 新增「单一前导 `system` 不变量」+ 三块顺序：租户段 → 摘要块 → 纪律段）、**§7.5.2 ⑥ ②③ 重写**（形态订正 + `30060` 与物理长度解耦）、**§7.5.2 ① 裁决框补注**（判定对象恒为租户段）、**§7.5.4 新增「摘要以 system 内的块承载」+ 同源既有隐患订正**、**§8.3 J9 判据订正 + 新增 J11/J12**（🔴 A~I 组与 J1~J8/J10 编号与判据零变化）。逐条见 §9 变更记录 V1.2.4。

**本版范围（V1.2.5，🔴 新增能力增量：用户维度对话限流与每日限额）**：PRD **V1.4** 的 `REQ-LMT-003` / `REQ-QUOTA-001~005` 契约落地（技术决策见 `architecture.md` **ADR-020**，V1.4.5）。
🔴 **新增 1 个接口**（`GET /api/v1/me/quota`，接口总表 12 → **13**）、**新增 1 个错误码**（`30070 DAILY_QUOTA_EXHAUSTED`，新增子段 `30070~30079`）、**新增 3 个 `sys_config` 键**（键总数 32 → **35**）、**1 处键值变更**（`ratelimit.message_per_minute` 30 → **3**）、**1 个键正式废弃并删行**（`ratelimit.message_per_hour`）、**新增 2 张表**（`tenant_quota_policies` / `user_daily_quota_usages`）。
🔴 **零 SSE 事件名与字段变更**（额度快照**不进** `done` 帧，裁决理由见 ADR-020 备选方案 E）、**零新增 audit action（仍 12）**、**零新增埋点事件名**、**零 `last_check_result` 字面量变更（仍 9）**、**零 `transport` 枚举变更（仍 2 值）**、**deadline 预算制与单一前导 `system` 不变量零改动**。
定点变更共 8 处：**§2.1 新增子段 `30070~30079`**、**§2.2 新增 `30070` 登记行**、**§4.3 增补 §7.15 指针**、**§7.1.1 接口总表新增第 13 行**、**§7.1.2 新增 3 键 + 键总数改 35 + 沿用键清单移除 `message_per_hour` + 两条拒绝启动不变量 + 一条 WARN 不变量**、**§7.12 重写**（阈值来源改为策略解析器、小时窗业务规则废除、日额度优先级）、**新增 §7.15「用户额度与限流（REQ-LMT-003 / REQ-QUOTA-001~005）」**（额度快照 9 字段、准入五步顺序、计数口径、租户时区窗口、配置分层）、**§8.3 新增 K 组核对项 K1~K16**（🔴 A~J 组编号与判据零变化）。逐条见 §9 变更记录 V1.2.5。

**本版范围（V1.2.7，🔴 全局传输层契约缺陷裁决：建流前异常必须绕过内容协商）**：`docs/test-report.md` V5.0 的 **BUG-QUOTA-001** 契约侧落点（技术决策见 `architecture.md` **ADR-021** / **§9.3.1**，V1.4.8）。
🔴 **接口清单零新增、错误码零新增、`sys_config` 键零新增（仍 35）、DDL 零变更、audit action 零新增（仍 12）、SSE 事件名与字段零变更、🔴 前端契约零变化（前端代码零改动）**。
🔴 **缺陷性质**：限流判定与 `GlobalExceptionHandler` 映射**都正常**（日志已确证 `code=10005` 正确生成），但把 JSON `Result` 写回一个带 `Accept: text/event-stream` 的请求时，Spring 内容协商找不到可用 `HttpMessageConverter` → `HttpMediaTypeNotAcceptableException` → 容器 `/error` 二次协商同样失败 → **HTTP 500 + Content-Length: 0**。🔴 **影响面远超限流**：`10005` / `30070` / `10001` / `10004` / `10003` / `20001~20005` / `50003` —— **所有**在 `meta` 帧 flush 之前抛出的业务异常，在真实浏览器下全部退化为 500 空体（含**鉴权失效拿不到 code → 无法跳 SSO**）。这是**既有缺陷**，限流只是第一个把它高频触发的入口。
定点变更共 5 处：**§1.2 新增两条传输层不变量**（🔴 `Accept` 头不得改变响应形态；🔴 HTTP 状态码口径**不二分**，SSE 端点的建流前失败**同样恒 200**）、**§4.6.1 两段式判据重写**（🔴 判别依据由"错误码"订正为"`meta` 是否已 flush"，并明确 `done` 义务边界）、**§5.1 `done` 必发的义务边界补注**（建流前失败**无** `done` 义务，@测试 不得据此判缺陷）、**§7.12 未建流行补"与 `Accept` 无关"+ 登记 BUG-QUOTA-001**、**§8.3 新增 L 组 L1~L7（🔴 全部为签署前置门禁；A~K 组编号与判据零变化）**。逐条见 §9 变更记录 V1.2.7。

章节导航：
§1 通用约定 · §2 错误码登记表 · §3 权限注解 · §4 M1 接口清单 · **§5 SSE 事件契约（含 M3 工具事件与确认交互）** · §6 M2 Deferred 占位 · **§7 M2-min + M3 正式契约** · §8 契约一致性核对清单 · §9 变更记录。

---

## 1. 通用约定

### 1.1 基础

| 项 | 约定 |
|---|---|
| 协议 | HTTPS（生产）/ HTTP（本地） |
| 前缀 | 业务接口统一 `/api/v1/**` |
| 认证 | 请求头 `authorization: <jwt>`（eyesUser 下发，`auth-type=1`） |
| 续期 | 响应头 `authorization: <新 jwt>`，前端**必须**立即回写 `localStorage` |
| 字符集 | UTF-8；请求/响应 `application/json`（流式为 `text/event-stream`） |
| 字段命名 | 对外 JSON 一律 **camelCase**（数据库 snake_case 由后端转换） |
| 时间格式 | ISO-8601 UTC，形如 `2026-08-12T10:00:00.000Z`（存储 UTC，展示时区由前端按租户 `timezone` 渲染） |
| ID 类型 | 🔴 所有 `id` / `uid` / `conversationId` / `messageId` / `agentId` 一律 **string**（BIGINT 精度保护，ADR-004）；`total/page/pageSize/version/tokenUsage` 为 number |
| 租户身份 | 🔴 **禁止**在请求参数/请求头传 `tenantId`；后端只认 Host，传入值一律忽略并记安全日志（EX-003） |

### 1.2 统一响应体（框架 §14.1，唯一形态）

所有 `/api/v1/**` 接口**无论成功或业务失败一律返回 HTTP 200**：

```json
{
  "code": 0,
  "message": "success",
  "data": null,
  "timestamp": 1704067200000
}
```

- `code = 0` 是**唯一成功值**（禁止 `200`）
- `timestamp`（服务端毫秒时间戳）**所有响应必须包含**
- 后端统一构造 `Result.success(...)` / `Result.error(code, message)`（**禁止 `Result.fail`**）
- 🔴 禁止用 HTTP 201/400/401/403/404/409/429/500/503 表达 `/api/v1/**` 的业务语义

#### 1.2.1 传输层不变量（🔴 V1.2.7 新增，ADR-021 —— 本节两条与 §1.2 同等强制）

```
① 🔴 请求的 `Accept` 头**不得**改变 /api/v1/** 的响应形态。
   Accept: */*、application/json、text/event-stream 三者必须得到**逐字节可比**的响应
   （`data` 内的 retryAfterSeconds 等动态值除外）。
   👉 违反即缺陷。BUG-QUOTA-001 正是此不变量被 Spring 内容协商悄悄打破：
      同一次限流，不带 Accept 得 `200 + code=10005`，带 `Accept: text/event-stream`
      得 `500 + 空体`（真实浏览器恒带后者）。

② 🔴 HTTP 状态码口径**不二分** —— §1.2「一律 HTTP 200」**原样适用于 SSE 端点的建流前失败**。
   🔴 明确否决"SSE 端点特殊、可以返 4xx/5xx"这一读法：
   ⓐ §1.5 已把非 200 的例外**穷举**为 GET /site/status 一个端点，不存在第二个；
   ⓑ 前端 `streamRequest.ts` 的 `!response.ok → NetworkError` 是**按本约定实现的
      契约违反探测器**，🔴 禁止为"兼容非 200 带 JSON 体"而放宽它
      （放宽等于把后端契约违反永久掩盖成一次可用的业务提示）。
```

🔴 **实现侧唯一合法写法**（技术细节与四种候选的取舍见 `architecture.md` **ADR-021**）：
异常响应必须**绕过内容协商** —— `GlobalExceptionHandler` 的每个有响应体的处理方法返回
`ResponseEntity<Result<T>>` 并**显式**设置 `Content-Type: application/json`。
🔴 **禁止**给 SSE 端点声明 `produces`（会让 `Accept: application/json` 在 handler mapping 阶段直接 406，同样违反本节）。


### 1.3 分页约定

**请求**（Query）：

| 参数 | 类型 | 必填 | 默认 | 约束 |
|---|---|---|---|---|
| `page` | number | 否 | 1 | ≥1 |
| `pageSize` | number | 否 | `sys_config: business.page_size_default`（20） | ≤ `business.page_size_max`（100），超限 → `10001` |

**响应**：

```json
{
  "code": 0,
  "message": "success",
  "data": { "list": [], "total": 0, "page": 1, "pageSize": 20 },
  "timestamp": 1704067200000
}
```

排序键必须稳定（业务键 + `id` 兜底），保证翻页无重复无遗漏。

### 1.4 幂等约定

| 场景 | 机制 |
|---|---|
| `POST /api/v1/conversations` | 请求头 `Idempotency-Key: <uuid>`（**必填**）；Redis `SETNX` 去重，命中返回原结果 |
| `POST /api/v1/conversations/{id}/messages` | 请求头 `Idempotency-Key: <uuid>`（**必填**）；命中则**不重复创建用户消息**，返回原 assistant 消息的流或最终态（EX-013） |
| `POST /api/v1/messages/{id}/regenerate` | 请求头 `Idempotency-Key: <uuid>`（**必填**） |
| `POST /api/v1/events` | 由 `clientEventId` 唯一约束去重（`uk(tenant_id, client_event_id)`），重复上报静默丢弃且计入 `data.duplicated`（见 §7.10.1） |
| `POST /api/v1/platform/cache/evict` | 天然幂等（重复失效同一作用域结果一致），但**每次调用都必须独立审计**（见 §7.2.1） |
| 其他写接口 | 通过资源自身唯一约束 / 乐观锁 `expectedVersion` 保证 |

`Idempotency-Key` 缺失 → `10001`；TTL 10 分钟（`chat.idempotency_ttl_seconds`）。

### 1.5 站点级非 200 唯一例外（AC-NFR-004）

| 端点 | 说明 |
|---|---|
| `GET /site/status` | **后端唯一允许返回非 200 的端点**。按 Host 返回站点级状态：未知/draft/archived → **404**；suspended → **403**；无可用已发布配置 → **503**；正常 → **200**。返回极简 `text/html`（不含租户数据、不含登录入口、`noindex`） |

除此之外，`/api/v1/**` 任何情况（含未知 Host、租户暂停、参数错误、鉴权失败、系统异常）**必须 HTTP 200 + `code`**。

---

## 2. 错误码登记表（🔴 未登记不得实现）

### 2.1 段位规则（框架 §14.2）

| 段位 | 归属 | 说明 |
|---|---|---|
| `0` | 成功 | 唯一成功码 |
| `10000~19999` | 通用 | 参数 / 权限 / 资源 / 限流 |
| `20000~20999` | **耶瞳 SSO 保留** | ⚠️ 由 eyesUser 下发，**业务严禁占用** |
| `30000~39999` | 业务 | 各模块业务语义 |
| `50000~59999` | 系统 | 数据库 / 第三方 / 未分类 |

**业务段内部分段（M3 起固化，新增码必须落在对应子段）**：

| 子段 | 归属 | 已用 |
|---|---|---|
| `30010~30019` | 租户上下文 | 30010 / 30011 / 30012 / 30013 |
| `30020~30029` | 版本与发布 | 30020 / 30021 |
| `30030~30039` | Agent | 30030 / 30031 |
| `30040~30049` | 会话与消息 | 30040 / 30041 |
| `30050~30059` | **工具编排（Skill / MCP / 本地 Tool）** | 30050 / 30051 / 30052 / **30053** / **30054** / **30055** / **30056** / **30057**（余量 30058~30059） |
| `30060~30069` | **配置校验与缓存一致性** | **30060** / **30061**（余量 30062~30069） |
| **`30070~30079`** | **用量与额度（🔴 V1.2.5 新增子段）** | **30070**（余量 30071~30079） |

> 🔴 §2.1 子段「已用」列与 §2.2 正式登记表必须**逐一对应、无多无缺**；两表不一致时以本次修订同步后的内容为准，任何单表改动视为缺陷。
> ⚠️ 已废弃且禁止出现：`10002`（用 20001/20002 代替）、`40001`（用 10001 代替）。

### 2.2 正式登记表

| code | 语义名（常量） | 含义 | 前端动作 | 里程碑 |
|---|---|---|---|---|
| `0` | `SUCCESS` | 成功 | 正常处理 | M1 |
| `10001` | `VALIDATION_FAILED` | 参数校验失败（含分页越界、缺 `Idempotency-Key`） | 展示 message / 表单错误 | M1 |
| `10003` | `PERMISSION_DENIED` | 已登录但业务权限不足（含成员 `disabled`、租户内角色不足） | 展示无权限态，**不跳登录** | M1 |
| `10004` | `RESOURCE_NOT_FOUND` | 当前租户上下文中资源不存在（含跨租户/跨用户 ID） | 展示不存在态 | M1 |
| `10005` | `RATE_LIMITED` | 请求频率超限；🔴 `data.retryAfterSeconds`（number，≥1，剩余等待秒数）**必填**；SSE 内以 `error.retryAfterSeconds` 承载（见 §7.12） | 展示等待语义，倒计时后可重试 | M3 |
| `20000` | `AUTH_FORBIDDEN` | eyesUser：权限不足 | 清 token + 整页跳 SSO | M1 |
| `20001` | `AUTH_TOKEN_INVALID` | eyesUser：Token 非法/伪造/appId 不匹配 | 清 token + 整页跳 SSO | M1 |
| `20002` | `AUTH_TOKEN_EXPIRED` | eyesUser：Token 已过期 | 清 token + 整页跳 SSO | M1 |
| `20003` | `AUTH_ACCOUNT_FROZEN` | eyesUser：账号被冻结 | 先传达原因，再跳 SSO | M1 |
| `20004` | `AUTH_ACCOUNT_NOT_FOUND` | eyesUser：账户不存在 | 清 token + 整页跳 SSO | M1 |
| `20005` | `AUTH_ROLE_ILLEGAL` | eyesUser：非法角色 | 清 token + 整页跳 SSO | M1 |
| `20008` | `AUTH_PARAM_ILLEGAL` | eyesUser：鉴权参数非法 | 提示，**不跳转** | M1 |
| `30010` | `TENANT_NOT_FOUND` | 已进入业务 API 但无法建立租户上下文（未知/draft/archived Host） | 路由至站点不存在视图 | M1 |
| `30011` | `TENANT_SUSPENDED` | 租户已暂停 | 路由至站点暂停视图 | M1 |
| `30012` | `TENANT_CONFIG_UNAVAILABLE` | 租户无可用已发布配置 | 路由至站点配置异常视图 | M1 |
| `30013` | `TENANT_CONTEXT_MISSING` | 业务链路（含异步任务）缺少租户上下文 | 展示系统错误 + 上报 | M1 |
| `30020` | `VERSION_CONFLICT` | 并发版本冲突（重命名/发布/编辑） | 展示差异并要求重试，**不静默覆盖** | M1（重命名）/ M2 |
| `30021` | `PUBLISH_VALIDATE_FAILED` | 发布校验失败，`data.violations[]` 给出分类 | 展示校验清单 | M2 |
| `30030` | `AGENT_UNAVAILABLE` | 当前租户无可用（已发布且启用）Agent | 禁用输入 + 展示不可用语义 | M1 |
| `30031` | `AGENT_DISABLED` | 目标 Agent 已停用/归档 | 会话转只读语义 | M1 |
| `30040` | `CONVERSATION_READONLY` | 会话只读（Agent 停用/归档） | 禁用输入 | M1 |
| `30041` | `MESSAGE_TOO_LONG` | 消息为空白或超过 `chat.message_max_chars` | 展示长度提示 | M1 |
| `30050` | `TOOL_DENIED` | 工具未授权 / Agent 未绑定 / 用户拒绝确认 / 确认等待超时 / SSRF 安全校验拒绝（保存时与**每次调用前**运行时兜底均适用） | 展示拒绝语义 | M3 |
| `30051` | `TOOL_TIMEOUT` | 工具执行超时（本地 Tool 超 `tool.default_timeout_seconds`；MCP 超 `mcp.call_timeout_seconds`） | 展示超时语义 | M3 |
| `30052` | `MCP_UNAVAILABLE` | MCP 连接 / 传输 / 协议不兼容 / 鉴权失败 / 服务不可用 | 展示不可用语义 | M3 |
| `30053` | `TOOL_ARGS_INVALID` | 工具入参不符合已注册 JSON Schema（本地 Tool 校验失败；MCP `tools/call` 返回 JSON-RPC `-32602`） | 展示参数错误语义，**不重试同参** | M3 |
| `30054` | `TOOL_LOOP_LIMIT_EXCEEDED` | 单次生成的工具调用轮次超过 `sys_config: tool.max_rounds` | 展示"已达调用上限"，可重新提问 | M3 |
| `30056` | `TOOL_RETRY_BLOCKED` | 非幂等工具结果未知，禁止自动重试（EX-019 / AC-TOL-003） | 展示"结果待确认"，仅允许用户显式重新提问 | M3 |
| `30057` | `TOOL_EXECUTION_FAILED` | 工具执行返回业务失败（MCP `result.isError=true`；本地 Tool 抛业务异常），非超时、非鉴权、非参数错误 | 展示工具失败语义 | M3 |
| `30060` | `RUNTIME_CONFIG_INVALID` | Agent/Skill/MCP/Tool 配置或引用链非法（独立校验入口与运行时兜底共用）；`data.violations[]` 给出字段级失败 | 展示配置异常，**禁止白屏/NPE** | M2-min |
| `30061` | `CACHE_INVALIDATION_FAILED` | 缓存失效部分或全部失败；`data.incompleteScopes[]` 必须列出未完成作用域，禁止伪报成功 | 提示重试并展示未完成作用域 | M2-min |
| **`30070`** | **`DAILY_QUOTA_EXHAUSTED`** | 🔴 **V1.2.5 新增**：当前租户当前用户的**今日对话额度已用尽**（判定发生在模型调用之前）。🔴 `data` **必须**是 §7.15.2 的**额度快照**（恰 9 键，`remaining=0` / `status=exhausted`）；🔴 **禁止**携带 `retryAfterSeconds`（它不是秒级可恢复的频率限制，携带即会被前端 `rateLimitStore` 误表现为倒计时） | 🔴 展示"今日额度已用尽 + 明日租户零点重置 + 联系管理员"，**禁用发送但保留输入与草稿**；🔴 **不得**进入 QPM 倒计时、**不得**自动重试 | **M3.1** |
| `50002` | `UPSTREAM_UNAVAILABLE` | 上游不可用（eyesUser Thrift / 模型服务） | 展示稍后重试 | M1 |
| `50003` | `INTERNAL_ERROR` | 未分类系统错误 / 系统繁忙（含审计写入失败导致的整体失败） | 展示系统繁忙 | M1 |

**保留但当前未使用**：`30001`（`BusinessException` 默认码，仅兜底，正式接口不得直接返回）、`50001`（数据库异常，由 `GlobalExceptionHandler` 内部映射，对外语义等同系统错误）。

**登记流程**：新增业务码 → 追加本表（含语义名/含义/前端动作/里程碑）→ 同步 §2.1 子段「已用」列 → 在 `ErrorCode` 常量类新增 → 广播 @前端 + @测试 → 方可实现。

> 📢 **V1.1 新登记（@后端 需同步 `common/ErrorCode.java` 与 `defaultMessage`；@前端 需同步错误码文案映射；@测试 需纳入用例）**：
> `30053`、`30054`、`30055`、`30056`、`30057`、`30060`、`30061`。在常量与本表同步完成前，@后端 **不得**在代码中出现这些数字。

> 📢 **V1.2.5 新登记（🔴 同一纪律）**：`30070`（`DAILY_QUOTA_EXHAUSTED`）。落地要求四处**同时**完成后方可实现：
> ① 本表 + §2.1 子段「已用」列（已完成）② `common/ErrorCode.java` 新增常量 `DAILY_QUOTA_EXHAUSTED = 30070` **并加入 `ErrorCode.REGISTERED`**（漏加会让该码在埋点里被静默置空）
> ③ `ErrorCode.defaultMessage` 新增分支（诊断语义，🔴 面向用户的最终文案在前端 `locales`）④ 广播 @前端 + @测试。
> 🔴 **反向纪律**：`30070` **禁止**复用 `10005`，也禁止把日限额塞进 `10005` 的 `data`（PRD §8.11.3 已明文否决 —— 两者恢复条件相差 5 个数量级）。

---

## 3. 权限注解与 RBAC 对照

| 注解 | 含义 | 失败码 |
|---|---|---|
| `@Permission(PermissionEnum.NO)` | 公开，无需登录（匿名可访问） | — |
| `@Permission(PermissionEnum.USER)` | 需有效 eyesUser 身份；命中即触发**惰性建户** | `20000~20005` |
| `@Permission(PermissionEnum.ADMIN)` | 需 eyesUser `role=ADMIN`（平台管理员） | `20000/20005` |
| `@TenantRole({TENANT_ADMIN})` | 叠加校验租户内角色（在 `@Permission` 之后执行） | `10003` |
| `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})` | 租户管理员或运营 | `10003` |

> 🔴 本项目**不存在**以下接口，出现即为缺陷：`/api/v1/auth/login`、`/register`、`/logout`、`/auth/callback`、任何刷新令牌接口。前端亦**无 `/login` 路由**。

**🔴 V1.1.5 新增（G-5 裁决）`@Permission(ADMIN)` 端点「平台角色不足」的唯一对外失败码 = 按鉴权切面装配状态二分**

🔴 适用范围 = **注解本身为 `@Permission(PermissionEnum.ADMIN)` 的端点**，一期**唯一一个**：§7.2.1 `POST /api/v1/platform/cache/evict`。

| 运行形态 | 判定者 | 对外 `code` | 适用环境 |
|---|---|---|---|
| `eyes-auth.enabled=true`（切面已装配） | `PermissionAspect`（`@Order(10)`，在 Controller 之前） | 🔴 **`20000`**（`AUTH_FORBIDDEN`） | 🔴 `prod` / `dev` —— 即**一切真实运行环境** |
| `eyes-auth.enabled=false`（切面未装配） | Controller 入口的程序化兜底（`requirePlatformAdmin()`） | 🔴 **`10003`**（`PERMISSION_DENIED`） | 仅 `test` profile（集成测试） |

```
🔴 为什么不统一成一个码（这是本条唯一可能被质疑处）：
① 生产下 PermissionAspect 在 Controller **之前**执行，请求根本进不到方法体 ——
   要让生产也返 10003，只能把端点降级为 @Permission(USER) 再在方法体判 role，
   那等于自愿放弃"平台角色由鉴权切面统一裁决"这道前置防线，方向错误；
② 反之若 test 下也返 20000，就要求兜底代码伪造一个 SSO 语义的错误码，
   而兜底判定的输入根本不是 SSO（无 Thrift 校验），属伪报。
🔴 因此二分是**物理顺序的必然结果**，不是口径不统一。

🔴 共同不变量（@测试 的真正断言对象，二者皆须成立）：
① 🔴 平台角色不足时**绝不允许** code=0、绝不允许返回任何平台/租户数据 —— 出现即安全缺陷；
② 两个码均已在 §2.2 登记，且都不属 SSO 保留段的"业务占用"（20000 由 eyesUser 语义下发）；
③ 前端动作差异可接受：20000 触发"清 token + 整页跳 SSO"，10003 展示无权限态。
   🔴 本端点**一期无 UI**（§8.3 H4），仅由运维/DBA 用脚本调用，前端不受影响。

🔴 @测试 断言口径（按 profile 二分，不得跨环境套用）：
   · test profile（`eyes-auth.enabled=false`）→ 断言 `10003`
   · 任何 `eyes-auth.enabled=true` 的环境 → 断言 `20000`
   · 两者共同断言：🔴 `code != 0` 且响应体不含 `data` 业务内容。
```

**🔴 对比：§7.3.1 平台作用域校验（`objectType=localTool`）的失败码恒为 `10003`，不二分**

```
该端点同时服务「租户作用域对象（TENANT_ADMIN/OPERATOR）」与「平台作用域对象（平台管理员）」，
而 @Permission 是**方法级**注解，无法按请求体 objectType 分支 ——
🔴 因此注解只能取较宽的 @Permission(USER)，平台角色判定必须在方法体内做
（`role != ROLE_admin` → 10003 + 安全日志）。
👉 结果：两种 profile 下该端点均返 **10003**，🔴 @测试 单一断言即可。
（V1.1.5 订正 §7.3.1 权限行原"改用 @Permission(ADMIN)"的措辞 —— 该措辞不可实现，@后端 实现正确。）
```

> 🔴 **ADMIN 语义端点的程序化兜底当前已全覆盖**（V1.1.5 事实登记）：§7.2.1 缓存失效与 §7.3.1 的 `localTool` 分支是一期**全部**需要平台管理员的入口，两者均已在方法入口做程序化 fail-closed 判定。因此 `eyes-auth.enabled=false` 下**不存在** ADMIN 语义端点静默放行的路径（`architecture.md` AR-018 ⑤ 已按此订正）。

**🔴 V1.1.4 新增纪律（#6 裁决，安全漏洞级）：`@TenantRole` 必须有程序化兜底，纯注解视为缺陷**

```
事实：TenantRoleAspect 由 EyesAuthConfig 装配，后者带
     @ConditionalOnProperty(prefix="eyes-auth", name="enabled", havingValue="true")。
     👉 eyes-auth.enabled=false 时（test profile 即如此，生产亦可能被误配），
        PermissionAspect 与 TenantRoleAspect **整个不注册**，
        `@Permission` / `@TenantRole` 退化为"看起来有准入、实际全放行"的装饰 ——
        接口会直接以 code=0 返回租户数据，🔴 静默越权且不报任何错。

🔴 纪律（全局，凡 /api/v1/** 上出现 @TenantRole 的端点一律适用）：
① 注解**必须保留**（契约可读性 + 切面生效时的正常路径）；
② 🔴 同时必须在方法入口做一次**程序化 fail-closed 判定**：
   TenantContext.requireEnabled() → 取 uid（无 → 10003）→
   TenantMembershipPort.ensureMembership(uid) → 角色不足 → 10003；
③ 两条路径的失败码**必须相同**（均 `10003`），保证开关状态不改变对外契约；
④ 🔴 「只有注解、没有程序化兜底」= 缺陷（等级：安全），不因"测试环境才会这样"而豁免。

落地基线（@后端）：抽出 `auth/TenantRoleGuard.require(TenantRoleEnum...)` 统一实现，
McpAdminController / ConfigValidateController / UsageMetricsController 三处现有内联实现改为调用它；
守护测试见 §8.3。生产侧另由 `StartupChecker.checkProductionBlockers()` 在 prod profile
断言 `eyes-auth.enabled=true`，否则**启动失败**（architecture §8.2.1 / §13.6 纪律 3）。
```

---

## 4. M1 接口清单

### 4.1 平台配置

#### 4.1.1 获取前端可用平台配置

**GET** `/api/v1/sys-config`
**权限**：`@Permission(PermissionEnum.NO)`
**租户上下文**：不需要（平台级白名单路径）
**幂等**：天然幂等

| 参数（Query） | 类型 | 必填 | 说明 |
|---|---|---|---|
| `group` | string | 否 | 配置分组；缺省返回全部 `is_frontend=1` 项 |

**成功响应**：`data` 为按 `group` 聚合的 KV，`value` 已按 `value_type` 转换为对应 JSON 类型。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "chat": { "messageMaxChars": 20000, "titleMaxChars": 60 },
    "business": { "pageSizeDefault": 20, "pageSizeMax": 100 },
    "display": { "toolStatusLabels": [] }
  },
  "timestamp": 1704067200000
}
```

> 键名转换规则：`config_key` 为 snake_case，对外自动转 camelCase（`message_max_chars` → `messageMaxChars`）。
> **错误码**：`10001`（group 非法）、`50003`

### 4.2 站点

#### 4.2.1 站点级状态探测（唯一非 200 端点）

**GET** `/site/status`

> 🔴 **路径易误用警告**：本端点**不带 `/api/v1` 前缀**。
> 访问 `/api/v1/site/status` 命中的是「未知路由」，会返回 **HTTP 200 + `code=10004`**，
> 而不是本节定义的 404/403/503。M1 回归已实际踩坑，请务必按 `/site/status` 请求。

**权限**：`@Permission(PermissionEnum.NO)`
**响应**：`text/html; charset=utf-8`，含 `<meta name="robots" content="noindex">`

| Host 状态 | HTTP | 页面语义 |
|---|---|---|
| 未知 / `draft` / `archived` | **404** | 站点不存在 |
| `suspended` | **403** | 站点暂停服务 |
| `enabled` 但 `configVersion=0` 或配置不可用 | **503** | 站点配置异常 |
| `enabled` 且配置可用 | 200 | `OK` |

> 用途：Nginx `error_page` 映射、运维探测、@测试 验收 AC-NFR-004。**禁止**返回任何租户业务数据。

#### 4.2.2 获取当前租户已发布站点配置

**GET** `/api/v1/site/config`
**权限**：`@Permission(PermissionEnum.NO)`
**租户上下文**：必需

**成功响应**：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "tenantId": "gift",
    "configVersion": 3,
    "timezone": "Asia/Shanghai",
    "locale": "zh-CN",
    "siteTitle": "……",
    "logoUrl": "https://…",
    "faviconUrl": "https://…",
    "welcomeText": "……",
    "inputPlaceholder": "……",
    "loginText": "……",
    "registerText": "……",
    "newChatText": "……",
    "emptySessionText": "……",
    "agentUnavailableText": "……",
    "footerDisclaimer": "……",
    "themePrimaryColor": "……"
  },
  "timestamp": 1704067200000
}
```

**错误码**：`30010`（未知/draft/archived Host）、`30011`（暂停）、`30012`（无可用配置）、`50003`

> 前端 `siteStore` 在挂载前调用；命中 `30010/30011/30012` 时路由到 `error/*` 视图，**不得白屏**。

### 4.3 当前用户

#### 4.3.1 获取当前用户与租户内身份

**GET** `/api/v1/me`
**权限**：`@Permission(PermissionEnum.USER)`（命中即惰性建户）
**租户上下文**：必需

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "uid": "10086",
    "nickname": "……",
    "avatarUrl": "https://…",
    "tenantRole": "END_USER",
    "platformAdmin": false,
    "memberStatus": "active"
  },
  "timestamp": 1704067200000
}
```

**错误码**：`20001/20002/20003/20004/20005`、`10003`（成员被禁用，EX-009）、`30010/30011`、`50002`（eyesUser 不可用，EX-008）、`50003`

> `nickname/avatarUrl` 取 `tenant_users.profile_snapshot`；头像获取失败**不阻断**身份建立（返回空串）。
> 🔴 禁止返回手机号、邮箱、token。
> 🔴 **V1.2.5 明确（ADR-020）**：本接口**不新增任何额度字段** —— 当前用户的对话额度快照由**独立接口** `GET /api/v1/me/quota` 承载（契约见 **§7.15.1**）。
> 理由：`/me` 是"身份建立"接口（前端启动只调一次、命中即惰性建户），而额度需要在**页面恢复前台 / 生成结束 / 到达 `resetsAt`** 等时机高频校准；两者耦合会导致"为刷额度而反复触发建户与 Thrift 鉴权"，且会出现两处口径。

### 4.4 Agent（M1 只读）

#### 4.4.1 获取当前租户可用 Agent 列表

**GET** `/api/v1/agents`
**权限**：`@Permission(PermissionEnum.NO)`（匿名可看展示信息）
**租户上下文**：必需

**成功响应**：按 `isDefault DESC, sortOrder ASC, id ASC` 排序，仅返回 `status=enabled` 且 `currentVersion>0` 的 Agent。

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "agentId": "12",
        "agentKey": "gift-helper",
        "name": "……",
        "description": "……",
        "avatarUrl": "",
        "agentVersion": 5,
        "isDefault": true,
        "sortOrder": 0
      }
    ],
    "total": 1,
    "page": 1,
    "pageSize": 20
  },
  "timestamp": 1704067200000
}
```

> 🔴 **禁止**返回 `systemPrompt`、`providerKey`、`model`、`temperature`、`maxOutputTokens`、能力绑定等敏感/内部字段。
> 空列表时 `total=0`（前端按 `agentUnavailableText` 展示，并禁用输入）。

**错误码**：`30010`、`30011`、`30012`、`50003`

### 4.5 会话

#### 4.5.1 会话列表

**GET** `/api/v1/conversations`
**权限**：`@Permission(PermissionEnum.USER)`
**排序**：`updated_at DESC, id DESC`（稳定分页）

| 参数（Query） | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `pageSize` | number | 否 | 见 §1.3 |
| `keyword` | string | 否 | 标题模糊匹配，≤60 字符 |

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "conversationId": "1001",
        "title": "……",
        "titleSource": "auto",
        "agentId": "12",
        "agentVersion": 5,
        "status": "active",
        "messageCount": 6,
        "lastMessageAt": "2026-08-12T10:00:00.000Z",
        "updatedAt": "2026-08-12T10:00:00.000Z"
      }
    ],
    "total": 1, "page": 1, "pageSize": 20
  },
  "timestamp": 1704067200000
}
```

> 只返回**当前租户 + 当前 uid + 未删除**的会话（AC-CON-003）。
**错误码**：`10001`、`20001~20005`、`10003`、`30010/30011`、`50003`

#### 4.5.2 创建会话

**POST** `/api/v1/conversations`
**权限**：`@Permission(PermissionEnum.USER)`
**请求头**：`Idempotency-Key: <uuid>`（必填）

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `agentId` | string | 否 | 缺省使用默认 Agent；必须是已发布且启用的 Agent |

**成功响应**：`data` 为会话对象（同 §4.5.1 单项结构）。会话创建时固定绑定 `agentVersion = Agent 当前已发布版本`（PRD 规则 10）。

**错误码**：`10001`、`20001~20005`、`10003`、`30010/30011`、`30030`（无可用 Agent）、`30031`（指定 Agent 已停用）、`50003`

#### 4.5.3 会话详情

**GET** `/api/v1/conversations/{conversationId}`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**错误码**：`10004`（非本人/跨租户/已删除，统一按不存在处理）、`20001~20005`、`30010/30011`、`50003`

#### 4.5.4 重命名会话

**PATCH** `/api/v1/conversations/{conversationId}`
**权限**：`@Permission(PermissionEnum.USER)` + 本人

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `title` | string | 是 | 1~60 个 Unicode 字符，去首尾空白后非空 |
| `expectedVersion` | number | 是 | 乐观锁版本（取自最近一次读取的 `version`） |

**成功响应**：更新后的会话对象；`titleSource` 置为 `manual`（此后自动标题不得覆盖，AC-CON-004）。
**错误码**：`10001`、`10004`、`20001~20005`、`30020`（并发冲突，EX-023）、`30010/30011`、`50003`

> 为支持乐观锁，§4.5.1/4.5.3 的会话对象**额外包含** `"version": number` 字段。

#### 4.5.5 删除会话

**DELETE** `/api/v1/conversations/{conversationId}`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**行为**：软删除（`status=deleted`、`deleted_at=now`）；若该会话正在生成 → **先取消生成再删除**，后续分片丢弃（EX-022）；30 天后物理清除。
**成功响应**：`data = null`
**错误码**：`10004`、`20001~20005`、`30010/30011`、`50003`

#### 4.5.6 会话消息历史

**GET** `/api/v1/conversations/{conversationId}/messages`
**权限**：`@Permission(PermissionEnum.USER)` + 本人

| 参数（Query） | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `pageSize` | number | 否 | 见 §1.3；排序 `created_at ASC, id ASC` |
| `includeSuperseded` | boolean | 否 | 默认 `false`（只返回 `isCurrent=true` 的尝试） |

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "messageId": "5001",
        "role": "user",
        "content": "……",
        "status": "sent",
        "attemptNo": 1,
        "isCurrent": true,
        "createdAt": "2026-08-12T10:00:00.000Z"
      },
      {
        "messageId": "5002",
        "role": "assistant",
        "content": "……",
        "reasoning": "用户问日期，需要调用 datetime_now……",
        "status": "completed",
        "attemptNo": 1,
        "isCurrent": true,
        "model": "hunyuan-turbos-latest",
        "agentVersion": 5,
        "finishReason": "stop",
        "tokenUsage": { "promptTokens": 120, "completionTokens": 300, "totalTokens": 420 },
        "toolCalls": [
          {
            "toolCallId": "9001", "toolType": "local", "toolKey": "datetime_now",
            "riskLevel": "low", "status": "succeeded", "round": 1,
            "summary": "{iso8601:2026-08-16T08:00:00Z,timezone:UTC}",
            "argsSummary": "timezone=UTC",
            "resultSummary": "{iso8601:2026-08-16T08:00:00Z,timezone:UTC}",
            "truncated": false, "errorCode": null, "retryAfterSeconds": null
          }
        ],
        "segments": [
          { "round": 1, "reasoning": "用户问日期，需要调用 datetime_now……", "text": "" },
          { "round": 2, "reasoning": "工具已返回，直接作答。", "text": "……" }
        ],
        "createdAt": "2026-08-12T10:00:03.000Z"
      }
    ],
    "total": 2, "page": 1, "pageSize": 20
  },
  "timestamp": 1704067200000
}
```
> 🔴 `role=system` / `role=tool` 的消息**不得**返回给终端用户（PRD 8.8）。
> 🔴 **V1.1.7 `reasoning`（仅 `assistant`，可空）**：推理型模型的思考过程，取自 `messages.reasoning`。
> 与 `content` **分列存储**（理由见 §5.2）；`user` 消息与无思考过程的消息**省略该字段**，
> 🔴 前端必须容忍「字段缺失 / `null` / 空串」三种形态并归一处理。
>
> 🔴 **V1.1.7 `toolCalls` 元素形状 = §5.2 `tool` 事件的 12 个字段（逐字段同构）**：
> 取自 `tool_calls` 摘要列，顺序为 `created_at ASC, id ASC`（**与 SSE 到达顺序一致**，
> 保证「实时看到的顺序」与「刷新后看到的顺序」相同）。
> 🔴 **为什么刻意与 `tool` 事件同构**：前端用**同一个**归一函数与**同一套**组件渲染实时流与历史回显，
> 形状分叉会产生「只在刷新后复现」的渲染差异 —— 例如兼容字段 `summary`（§5.4.1 第 2 条要求永久保留）
> 若在历史里缺失，状态条摘要会凭空变成空白。
> 🔴 `retryAfterSeconds` 在历史中**恒为 `null`**：它是限流的瞬时信息，不落库，
> 也不应在历史里复活成一个早已过期的倒计时。
> 🔴 `user` 消息**省略 `toolCalls`**；`assistant` 无工具调用时为 `[]`（不是 `null`），前端可无条件迭代。
> 🔴 **实现约束**：整页消息只允许查 `tool_calls` **一次**（批量 `IN`），逐条查即 N+1（§14.2 性能门禁）。
>
> 🔴 **V1.1.8 `segments`（仅 `assistant`，可空）** —— 按轮次的「思考 / 正文」段落，
> 形如 `[{"round":1,"reasoning":"…","text":""},{"round":2,"reasoning":"…","text":"…"}]`。
> **用途**：与 `toolCalls[].round` 配合还原真实时序 ——
> 时间线 = `段落(1) → 工具(1) → 段落(2) → 工具(2) → … → 段落(N)`。
> 🔴 **为什么必须有它**：`content` / `reasoning` 是全量拼接的扁平文本，
> 单靠它们无法得知「哪段思考发生在工具调用之前」，工具节点只能被固定堆到末尾 —— 因果被颠倒。
> 🔴 **它是渲染投影、不是事实来源**：`content` / `reasoning` 仍是唯一权威
> （复制回答、上下文回灌、标题生成只认它们）；
> 🔴 字段缺失 / `null` / 解析失败时前端**必须降级为旧版布局**（全部思考 → 全部工具 → 全部正文），
> **只降级排版、绝不丢内容**。
> 🔴 **轮号不得重排**：剔除空轮时保留原 `round` 值，否则与 `toolCalls[].round` 错位。
> 🔴 **轮次缺口必须容错**：某轮可能只有工具而无任何思考/正文（模型直接决定调用工具），
> 此时该轮在 `segments` 中不存在，但其工具节点必须仍按轮号插到正确位置。
**错误码**：`10001`、`10004`、`20001~20005`、`30010/30011`、`50003`

### 4.6 流式对话

#### 4.6.1 发送消息（SSE）

**POST** `/api/v1/conversations/{conversationId}/messages`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**请求头**：`Idempotency-Key: <uuid>`（必填）、`authorization`
**响应类型**：`text/event-stream; charset=utf-8`（响应头附带 `X-Accel-Buffering: no`、`Cache-Control: no-cache`）

| 路径参数 | 说明 |
|---|---|
| `conversationId` | 已有会话 ID；**特殊值 `new`** 表示"原子创建会话 + 保存首条用户消息 + 发起生成"（AC-CON-002） |

| 字段（Body） | 类型 | 必填 | 约束 |
|---|---|---|---|
| `content` | string | 是 | 1 ~ `chat.message_max_chars`（20000）个 Unicode 字符；纯空白不可发送 |
| `agentId` | string | 条件必填 | 当 `conversationId=new` 时必填（或缺省用默认 Agent）；已有会话忽略该字段（会话绑定版本不变） |

**成功**：HTTP 200 + SSE 事件流（见 §5）。

🔴 **两段式响应形态（V1.2.7 判据重写，ADR-021 / `architecture.md` §9.3.1）**

| 阶段 | 判据（🔴 唯一） | 响应形态 | `done` 义务 |
|---|---|---|---|
| **建流之前** | `meta` 帧**尚未 flush**、响应**未提交**（仍在 Servlet 线程） | 🔴 **HTTP 200 + `content-type: application/json`** 的标准 `Result`（四字段恒在） | 🔴 **无**（没有流，不存在 `done`） |
| **建流之后** | `meta` 已 flush，响应已提交为 `text/event-stream`（异步段） | SSE `error` 事件（恰 3 键）+ 随后 `done` | 🔴 **必发** |

```
🔴 判别依据是「meta 是否已 flush」，**不是**「错误码」——
   用错误码分域是**错的**：50003 / 30060 在两侧都会出现，按码分域必然自相矛盾。

🔴 建流前的响应形态与请求 `Accept` 头**无关**（§1.2.1 不变量 ①）：
   Accept: text/event-stream（真实浏览器恒带）也必须得到 200 + application/json。
   👉 得到 HTTP 500 / 空体 / 406 一律判缺陷（BUG-QUOTA-001 的判据）。

🔴 @测试 口径：建流前被拒的 JSON 响应里**没有 done 帧是正确的**，
   🔴 不得据此判「违反 done 必发」——「done 必发」的适用前提是**流已建立**。
```


**错误码**：`10001`（缺幂等键/参数非法）、`10004`（会话不存在/非本人）、`10005`（M3 限流）、`20001~20005`、`10003`（成员禁用）、`30010/30011`、`30030`（无可用 Agent）、`30031`（Agent 已停用）、`30040`（会话只读）、`30041`（空白或超长）、`50002`（模型不可用）、`50003`

#### 4.6.2 停止生成

**POST** `/api/v1/messages/{messageId}/stop`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**行为**：写 Redis 取消标记 + 关闭本机上游流；前端应在 ≤1s 内停止追加；已生成内容保存为 `stopped`（AC-CHAT-002）。
**成功响应**：`data = { "messageId": "5002", "status": "stopped" }`
**幂等**：重复调用返回同样结果（已终态时不报错）
**错误码**：`10004`、`20001~20005`、`30010/30011`、`50003`

#### 4.6.3 重新生成（SSE）

**POST** `/api/v1/messages/{messageId}/regenerate`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**请求头**：`Idempotency-Key: <uuid>`（必填）
**行为**：以目标 assistant 消息为基准新建 `attemptNo+1` 尝试，旧尝试 `isCurrent=false` 保留；🔴 **不重复保存用户消息**；不重复执行已成功的非幂等工具（EX-019）。
**响应**：同 §4.6.1 的 SSE 流（`meta.messageId` 为新尝试 ID）
**错误码**：同 §4.6.1（另含 `10004`）

---

## 5. SSE 事件契约（M1 基线 + M3 工具事件扩展）

### 5.1 传输格式

标准 SSE 帧，**每帧必须带 `event:` 与 `data:`**，`data` 为单行 JSON：

```
event: meta
data: {"conversationId":"1001","messageId":"5002","agentVersion":5}

event: delta
data: {"text":"你好"}

event: done
data: {"finishReason":"stop"}

```

- 事件顺序：`meta` → (`delta` | `tool`)\* → (`error`)? → `done`
- `meta` 在**首个模型分片之前立即 flush**（规避代理缓冲，保障首字 P95 ≤5s）
- 除物理连接中断外，**`done` 必发**；`error` 之后仍须发 `done`
- 🔴 **`done 必发` 的义务边界（V1.2.7 补注，ADR-021）**：本约束的适用前提是**流已建立**（`meta` 已 flush）。**建流之前**的失败根本没有流，按 §4.6.1 两段式以 **HTTP 200 + `application/json`** 的 `Result` 表达，🔴 **不存在也不需要 `done`** —— @测试 不得因"JSON 拒绝响应里没有 `done`"判违反本条。🔴 反之，`done` 缺失的**唯一**合法情形仍只有真实的物理断连（§5.2 `finishReason=timeout` 框的反向判据不变）。
- 心跳：无分片超过 `sys_config: chat.stream_heartbeat_seconds`（15s）时发送注释帧 `: ping`（前端忽略）
- 🔴 **等待用户确认期间心跳不得停**：确认最长等待 `tool.confirm_wait_seconds`（默认 120s），远超代理空闲阈值，心跳缺失会导致连接被中间层掐断（见 §7.8）

### 5.2 事件负载

| event | 负载字段 | 类型 | 里程碑 | 说明 |
|---|---|---|---|---|
| `meta` | `conversationId` | string | M1 | 真实会话 ID（`new` 场景下为新建 ID） |
| | `messageId` | string | M1 | 本次 assistant 消息（尝试）ID；🔴 也是 §7.8.2 confirm 接口的路径参数 |
| | `agentVersion` | number | M1 | 本次生成使用的 Agent 版本 |
| | `userMessageId` | string | M1 | 本次保存的用户消息 ID（`regenerate` 场景为原用户消息 ID） |
| `delta` | `text` | string | M1 | 文本分片（增量，非全量；前端顺序拼接）。🔴 **只承载正文** —— 思考过程走同帧的 `reasoning` 字段，此处恒为空串 |
| | `reasoning` | string \| null | **V1.1.6** | 推理型模型（如 `hunyuan-a13b`）的**思维链增量**。🔴 与 `text` **互斥承载**：正文帧 `text=正文, reasoning=null`；思考帧 `text="", reasoning=思维链`。🔴 **V1.1.7 起落库**至 `messages.reasoning`（独立列），历史会话经 §4.5.6 回显 |
| `tool` | `toolCallId` | string | M3 | 工具调用 ID（string，全生命周期稳定） |
| | `toolType` | string | M3 | `local` \| `mcp` |
| | `toolKey` | string | M3 | 工具标识（租户内唯一；MCP 工具为 `{mcpKey}:{toolName}`） |
| | `status` | string | M3（V1.0 已声明） | `pending` \| `running` \| `succeeded` \| `failed` \| `timed_out` \| `cancelled` \| `denied` |
| | `round` | number | M3 | 第几轮工具调用，从 1 开始；上限 `sys_config: tool.max_rounds` |
| | `summary` | string | M3（V1.0 已声明） | 🔴 **兼容字段，永久保留**：当前阶段的可展示摘要（`pending`/`running` 时等于 `argsSummary`，终态时等于 `resultSummary`）。V1.0 已声明该字段，只读它的前端实现必须能继续正常工作 |
| | `argsSummary` | string | M3 | 入参**脱敏摘要**（脱敏规则见 §5.4.3），截断阈值 `sys_config: tool.args_summary_max_chars` |
| | `resultSummary` | string | M3 | 结果**脱敏摘要**，截断阈值 `sys_config: tool.result_summary_max_chars`；被截断时以 `…` 结尾且 `truncated=true` |
| | `truncated` | boolean | M3 | 结果是否被截断（原始结果超 `sys_config: tool.result_max_bytes`，EX-017） |
| | `errorCode` | number \| null | M3 | 终态为 `failed`/`timed_out`/`denied` 时的**数字业务码**（`30050`~`30057`、`50003`）；否则 `null` |
| | `retryAfterSeconds` | number \| null | M3 | 仅在因限流导致工具调用被拒时给出剩余等待秒数；否则 `null` |
| `error` | `code` | number | M1 | **数字业务码**（登记表内，如 `50002`/`30052`/`30054`） |
| | `message` | string | M1 | 可展示语义（禁含内部地址/堆栈/密钥） |
| | `retryAfterSeconds` | number \| null | M3 | 🔴 **字段恒存在**（V1.1.5 G-3 追认，由原"可选"改为必带）：`code=10005` 时为剩余等待秒数，其余一律 `null`（AC-LMT-001）。🔴 一期 SSE 内 `10005` 不可达（§7.12），故实测恒 `null` |
| `done` | `finishReason` | string | M1 | `stop` \| `length` \| `stopped` \| `failed` \| `tool_denied` \| `timeout` |
| | `messageId` | string | M1 | assistant 消息 ID |
| | `status` | string | M1 | 最终持久化状态：`completed` \| `stopped` \| `failed` |
| | `title` | string \| null | M1 | 首轮成功后生成的会话标题（否则 `null`） |

> 🔴 `tool.status` 使用 **snake_case 枚举值**（`timed_out`），与 PRD §10.5 的 camelCase 表述等价，**以本表为实现基线**。

> 🔴 **V1.2.2 新增（ADR-017）`done.finishReason = "timeout"` 的完整触发口径（🔴 零新错误码）**：
> 本取值自 M1 起即已登记，V1.2.2 把它的**触发集合**明确为**两类，二者同码同 `finishReason`**：
> ```
> ① 上游模型无响应：首字超时（chat.first_token_timeout_seconds）或单轮整体超时
>    （Agent requestTimeoutSeconds）→ error(50002) + done(finishReason=timeout, status=failed)（EX-014，M1 既有）
> ② 🔴 V1.2.2 新增：**单次生成总预算耗尽**（sys_config: chat.generation_deadline_seconds，
>    含全部模型轮次 + 工具执行 + 等待用户确认的时间）
>    → 同样 error(50002) + done(finishReason=timeout, status=failed)
> ```
> 🔴 **为什么复用 `50002` 而不登记新码**：两类对用户是同一件事（本次生成未能在时限内完成、可重试），
> 前端动作完全一致；新增码只增加登记面与前端改动而不改变任何行为（决策与代价见 ADR-017 ④）。
> 🔴 **两类共同的硬保证（@测试 直接断言）**：已生成的 `content` / `reasoning` **必须落库保留**；
> `tool_calls` 不留非终态（统一 `cancelled`）；🔴 `error` 帧仍恰 3 键（§5.2 G-3）。
> 🔴 **服务端必须让业务收敛早于连接死亡**：`SseEmitter` 的 timeout = `chat.generation_deadline_seconds`
> **+** `chat.deadline_grace_seconds`（宽限），因此 ② 的 `error` + `done` 一定写得出去。
> 🔴 **反向判据（本轮 P1-2 的验收锚点）**：出现「连接静默关闭且**无** `done`，而用户并未关页面/切网」
> 即为**缺陷**（`architecture.md` §9.5.4 不变量 5）；`done` 缺失的**唯一**合法情形是真实的物理断连。
> 🔴 SSE 中不得出现字符串错误码（如 `"error":"AUTH_FAILED"`）。
> 🔴 `tool` 事件**禁止**出现：工具完整入参明文、完整结果正文、消息正文、`systemPrompt`、Skill 正文、MCP `endpoint`、凭据/Token。
> 🔴 **V1.1.4 补注（#2 裁决）`error` 事件的字段集合恒为 `code` + `message` + `retryAfterSeconds` 三项**：即便 `code=30060`（运行时配置非法）也 🔴 **禁止**携带 `violations` / `objectType` / `objectId` / `rule` / `checkedObjects` —— SSE 的接收者是**终端用户**（`END_USER`），字段级校验明细属内部配置结构，下发即泄露（同本表第 3 条口径）。字段级明细的唯一出口是 §7.3.1 的管理端校验接口。
> 🔴 **V1.1.5 追认（G-3 裁决，@后端 已按此实现，🔴 不回退）**：`error` 事件的 JSON 对象**恰好 3 个键**（`code` / `message` / `retryAfterSeconds`），🔴 **键数可断言**（多一个键即缺陷，少一个键亦为缺陷）；`retryAfterSeconds` 一期恒 `null` 但**必须存在**。
> 理由：① §5.4.1 第 1/2 条「字段只增不改不删」是硬约束，`retryAfterSeconds` 一旦下发即永久保留；② 前端已上线并按三项容忍 `null`，回退为两项会使前端解析形状发生变化（属破坏性变更）；③ 恒定形状让 @测试 可做**精确键集合断言**，比"可选字段"更强的可验收性。

> 🔴 **V1.1.6 新增（`delta.reasoning` 思考过程通道）**：
> **为什么复用 `delta` 而不新增 `event: reasoning`** —— §5.4.1 第 6 条把事件名集合冻结为
> `meta|delta|tool|error|done`，既有前端对未知事件名一律走 `default: ignore`，
> 新增事件名会让整条通道被静默丢弃（属破坏性变更）。故按第 1 条「字段只增不改」以**新增字段**实现。
> 🔴 **为什么思考内容不能塞进 `text`**：会同时造成三重破坏 ——
> ① 只读 `text` 的前端把思考过程当答案渲染（违反 §5.4.1 第 4 条）；
> ② 思维链被拼进 `messages.content` 落库，污染历史与「复制回答」；
> ③ 首轮会话标题取自正文，将取到思考片段。
> 🔴 **一期不落库是刻意裁定**（0 DDL）：思维链体积大、无检索价值，且属模型中间产物；
> 若二期需要持久化，须先新增 `messages` 列并修订本表。
> 🔴 **V1.1.7 已改判为落库**：用户反馈「恢复历史会话时看不到思考过程」，
> 遂新增 `messages.reasoning LONGTEXT NULL`（architecture §13.3 as-built）。
> 🔴 **仍必须与 `content` 分列**：思维链混入 `content` 会同时污染上下文回灌、会话标题与「复制回答」。
> 🔴 **仍不回灌模型**：落库只为展示，`roundText`（回灌上下文的来源）绝不包含 `reasoning`，
> 否则多轮工具编排会把思维链反复塞回 prompt，token 成本随轮次放大。
> 🔴 **前端渲染纪律**：思考内容必须**纯文本**渲染，🔴 禁止走 Markdown ——
> 中间产物常含未闭合代码围栏 / HTML 片段，既会破版也会无谓扩大 XSS 净化面。
> 🔴 **展开策略**：生成中默认展开（过程实时可见），思考结束后自动折叠（视觉重心交还答案）；
> 历史消息默认折叠。🔴 用户手动点过展开/折叠后，自动逻辑**永久让位**于用户意图。
> 🔴 **工具栏必须嵌在思考面板内部**：工具调用发生「在思考之中」，是思考中途的一次外部查询，
> **不是思考的阶段边界** —— 把它当分隔符会把一段连续推理切成两个「思考过程」面板，
> 暗示模型中途停止了思考。合并规则：连续的思考/工具归入同一面板，
> 只有面向用户的正文（`segments[].text`）才结束当前面板（正文意味着模型「对用户说话了」）。
> 🔴 **只含工具、无任何思考时不得套「思考过程」外壳**（非推理模型没有思考过程），工具栏裸渲染。
> 🔴 **首字锚点不变**：思考帧本身是 `delta` 帧，因此仍适用 §5.4.2「首个 delta 或 tool 帧取先到者」。

### 5.3 前端消费约束（`utils/streamRequest.ts`）

```
1. 必须用 fetch + ReadableStream + TextDecoder 解析（EventSource 无法带 authorization 头）
2. 请求头注入 authorization；响应头出现新 authorization 时立即回写 localStorage（ADR-007）
3. HTTP 200 但 content-type 为 application/json → 按普通 Result 处理（走 request.ts 同一套错误分流）
4. body.code / error 事件 code ∈ [20000..20005] → 清 token + 整页跳 SSO（禁止 router.push('/login')）
5. 30000+ 业务码 → 展示语义，绝不跳登录
6. AbortController 用于用户主动中断；中断后仍需调用 §4.6.2 停止接口以持久化 stopped 状态
7. 🔴 未知 event 名、未知字段一律忽略（不得抛异常、不得中断流解析）—— 见 §5.4.1
```

### 5.4 M3 工具事件补充约定

#### 5.4.1 向后兼容（🔴 硬约束）

```
1. 字段只增不改：M1 已定义的 meta/delta/tool/error/done 字段名、类型与语义永久冻结
2. 禁止删除字段：tool.summary 即便被 argsSummary/resultSummary 取代，也必须继续下发
3. 禁止改变枚举既有取值的含义；新增枚举值只允许追加
4. M1 前端在不做任何改动的前提下，必须能正常消费带 M3 新字段的流（未知字段忽略）
5. M3 新增字段允许为 null，前端必须容忍 null 与字段缺失两种形态
6. 事件名集合仍固定为 meta / delta / tool / error / done —— M3 不新增事件名，不新开 SSE 流
7. 🔴 V1.1.6：能力扩展一律以「既有事件加字段」实现（如 delta.reasoning），
   🔴 新增事件名是破坏性变更 —— 既有前端的 default 分支会静默丢弃整条通道。
   新增字段时必须保证「旧前端只读原字段」的语义不被改变（reasoning 之于 text 即为范例）。
```

#### 5.4.2 事件时序

**红线**：`meta` 必**首发**且在任何模型分片之前 flush；🔴 工具调用能力的引入**不得**让首字 P95 ≤5s 回退——`meta` 的 flush 时机与 M1 完全一致（在建立连接时写出，早于 Skill 注入与工具清单构造）。

**🔴 首字 P95 观测锚点（V1.1.1 回写，与 `architecture.md` §9.5.3 一致）**：

```
首字 = 第一个「用户可见帧」= 🔴 第一个 delta 帧 或 第一个 tool 帧，**取先到者**。
       观测区间 = 请求到达服务端 → 该帧 flush 完成。

为什么必须改锚点（V1.1 未定义该场景，属门禁不可断言的缺口）：
🔴 「首轮即工具调用」的生成中**根本不存在前置 delta 帧**（模型第一步就请求调用工具），
   若仍以"第一个 delta"为锚点，该门禁在此场景下会得出"首字永不到达"的错误结论，
   使 AC-NFR 的性能断言无法执行。tool 帧（含 awaiting_confirmation）本身即用户可见内容，
   工具卡片渲染就是用户感知到的"首字"。

配套口径：
① meta 不作为锚点：它不含用户可见内容，且必然早于两者（否则是实现缺陷）
② 确认等待与工具执行耗时**不计入**首字（二者必然发生在首个可见帧之后），
   但计入埋点 messageComplete.durationMs
③ 心跳注释帧 `: ping` 不是可见帧，不作为锚点
```

> 📢 **@测试 需同步 `test-plan` 的性能断言口径**：性能用例必须覆盖两条路径并使用**同一锚点**——
> ① 首轮为文本（锚点 = 首个 `delta`）；② 🔴 首轮即工具调用（锚点 = 首个 `tool` 帧，含 `awaiting_confirmation`）。
> 断言脚本中**禁止**只监听 `delta` 事件，否则场景 ② 会误判为超时/不可断言。

多轮工具调用（含高风险确认）的完整时序示例：

```
event: meta
data: {"conversationId":"1001","messageId":"5002","agentVersion":5,"userMessageId":"5001"}

event: delta
data: {"text":"我先查一下…"}

event: tool
data: {"toolCallId":"9001","toolType":"local","toolKey":"order_refund","riskLevel":"high","status":"awaiting_confirmation","round":1,"summary":"将对订单 A***23 发起退款","argsSummary":"orderId=A***23, amount=***","resultSummary":"","truncated":false,"errorCode":null,"retryAfterSeconds":null}

: ping                                  ← 等待期间心跳持续（最长 tool.confirm_wait_seconds）

（前端此时调用 POST /api/v1/messages/5002/tool-calls/9001/confirm {"decision":"allow"}）

event: tool
data: {"toolCallId":"9001","toolType":"local","toolKey":"order_refund","riskLevel":"high","status":"running","round":1,"summary":"正在执行","argsSummary":"orderId=A***23, amount=***","resultSummary":"","truncated":false,"errorCode":null,"retryAfterSeconds":null}

event: tool
data: {"toolCallId":"9001","toolType":"local","toolKey":"order_refund","riskLevel":"high","status":"succeeded","round":1,"summary":"退款已提交","argsSummary":"orderId=A***23, amount=***","resultSummary":"{\"refundId\":\"R***\",\"state\":\"submitted\"}","truncated":false,"errorCode":null,"retryAfterSeconds":null}

event: delta
data: {"text":"退款已提交，"}

event: tool
data: {"toolCallId":"9002","toolType":"mcp","toolKey":"crm:lookup_user","riskLevel":"low","status":"running","round":2,"summary":"查询用户资料","argsSummary":"uid=1***6","resultSummary":"","truncated":false,"errorCode":null,"retryAfterSeconds":null}

event: tool
data: {"toolCallId":"9002","toolType":"mcp","toolKey":"crm:lookup_user","riskLevel":"low","status":"succeeded","round":2,"summary":"已获取资料","argsSummary":"uid=1***6","resultSummary":"…","truncated":true,"errorCode":null,"retryAfterSeconds":null}

event: delta
data: {"text":"预计 1 个工作日到账。"}

event: done
data: {"finishReason":"stop","messageId":"5002","status":"completed","title":"退款咨询"}
```

状态迁移规则（每次迁移下发一帧 `tool`，**禁止跳帧**）：

| 触发 | 事件序列 |
|---|---|
| 低风险 / 策略允许自动执行 | `pending` → `running` → `succeeded` \| `failed` \| `timed_out` |
| 需确认（`high`，或 `medium` + `toolPolicy=confirm`） | `pending` → `awaiting_confirmation` → (`running` → 终态) \| `denied` \| `timed_out` |
| 用户拒绝 / 等待超时 | → `denied`（`errorCode=30050`）或 `timed_out`（`errorCode=30050`），随后 `error`(30050) + `done`(`tool_denied`) 或继续 `delta`（由 Agent 策略决定，见 §7.8.1） |
| 用户中途 `POST /messages/{id}/stop` | → `cancelled`，随后 `done`(`stopped`) |
| 轮次超上限 | 不再下发新 `tool` 帧，直接 `error`(30054) + `done`(`failed`) |

#### 5.4.3 摘要脱敏规则（`argsSummary` / `resultSummary`）

```
1. 先按 JSON 键名白/黑名单脱敏，再截断，再落库/下发（顺序不可颠倒）
2. 敏感键名（大小写不敏感、含子串即命中）整值替换为 "***"：
   password / passwd / secret / token / authorization / credential / apikey / api_key
   / accesskey / privatekey / signature / cookie / session
3. 疑似个人信息按保留首尾脱敏：
   手机号 → 保留前 1 位 + *** + 后 2 位；邮箱 → 保留首字符 + *** + @域名；
   身份证/银行卡类长数字（≥12 位）→ 保留后 4 位
4. 其余字符串值超过 32 字符时中间省略为 "前 12 字符…后 8 字符"
5. 截断阈值取 sys_config（tool.args_summary_max_chars / tool.result_summary_max_chars），
   🔴 代码中禁止出现字面量
6. 🔴 脱敏后的摘要同时用于：SSE tool 事件、tool_calls 落库、审计 beforeDigest/afterDigest、
   §7.9.1 查询接口 —— 四处使用同一份摘要，不存在"落库明文、下发脱敏"的双轨
```

### 5.5 AG-UI 协议（🔴 彻底替换自定义 SSE 契约）

> 🔴 **本节登记"彻底替换为 AG-UI 协议"的迁移契约**。自定义 SSE（§5.1~§5.4）与 AG-UI
> 两端点在迁移期内并存；新客户端一律走 AG-UI 端点 `POST /api/v1/agui/run`。

#### 5.5.1 传输格式（与 §5.1 的差异）

AG-UI 事件类型**内嵌在 `data` JSON 的 `type` 字段**（PascalCase），**不使用** SSE 的 `event:` 行：

```
data: {"type":"RUN_STARTED","threadId":"1001","runId":"5002","input":{"conversationId":"1001"}}

data: {"type":"TEXT_MESSAGE_CONTENT","messageId":"5002","delta":"你好"}

data: {"type":"RUN_FINISHED","outcome":{"type":"success"}}

```

- 事件顺序：`RUN_STARTED` → (`TEXT_MESSAGE_*` | `REASONING_*` | `TOOL_CALL_*` | `CUSTOM`)\* → `RUN_FINISHED` \| `RUN_ERROR`
- `RUN_STARTED` 在**首个模型分片之前立即 flush**（同 §5.1 的 `meta` 义务，规避代理缓冲）
- 🔴 **`RUN_FINISHED` / `RUN_ERROR` 必发其一**（等价 §5.1「`done 必发`」），前提同为"流已建立"
- 🔴 建流前失败仍走 §4.6.1 两段式 **HTTP 200 + `application/json`**（ADR-021 全局约束不变）
- 心跳仍为注释帧 `: ping`（前端忽略，同 §5.1）

#### 5.5.2 请求体 `RunAgentInput`

```json
{
  "threadId": "1001",
  "runId": "idem-key-1",
  "messages": [{"role": "user", "content": "你好"}],
  "tools": [],
  "context": [],
  "forwardedProps": {"agentId": "..."},
  "resume": []
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `threadId` | string | 会话 ID（映射 conversationId）；`"new"` 表示原子新建会话 |
| `runId` | string | 本次运行 ID，🔴 也是幂等键（等价 §1.4 `Idempotency-Key`） |
| `messages` | array | 对话消息；🔴 本项目仅取最后一条 `role=user` 的 `content` 作为本次输入 |
| `forwardedProps.agentId` | string | `"new"` 场景下指定 Agent |
| `forwardedProps.regenerateMessageId` | string | 🔴 重新生成语义：传此字段即走 `regenerate` 路径（不重复保存用户消息） |
| `resume` | array | 🔴 工具确认中断恢复（见 §5.5.4） |

> 🔴 **历史会话回显仍走 REST**（§4.5.6），AG-UI 只管流式增量 —— 迁移契约明确：不发 `MESSAGES_SNAPSHOT` 回放历史。

#### 5.5.3 事件映射（自定义 SSE ↔ AG-UI）

| 自定义 SSE（旧） | AG-UI（新） | 说明 |
|---|---|---|
| `meta` | `RUN_STARTED` | `threadId`=conversationId、`runId`=assistantMessageId、`input` 承载 agentVersion/userMessageId |
| `delta(text)` | `TEXT_MESSAGE_START` + `TEXT_MESSAGE_CONTENT`\* + `TEXT_MESSAGE_END` | 首片先发 START，末片由收尾自动补 END |
| `delta(reasoning)` | `REASONING_START` + `REASONING_MESSAGE_START` + `REASONING_MESSAGE_CONTENT`\* + `REASONING_MESSAGE_END` + `REASONING_END` | 思维链独立通道 |
| `tool`（状态流转） | `TOOL_CALL_START` + `TOOL_CALL_ARGS` + `TOOL_CALL_END` + `CUSTOM("tool_progress")` + `TOOL_CALL_RESULT`(终态) | 🔴 完整状态机信息（riskLevel/status/confirmExpiresInSeconds 等）经 `CUSTOM("tool_progress")` 承载，标准工具事件只作骨架 |
| `error` + `done(failed)` | `CUSTOM("completion")` + `RUN_ERROR` | `completion` 透传 finishReason/status/title/errorCode |
| `done`（成功/停止） | `CUSTOM("completion")` + `RUN_FINISHED` | `completion` 透传 finishReason/status/title |

> 🔴 **`CUSTOM("tool_progress")` 的 value 字段与 §5.2 的 `tool` 事件 12 字段完全一致**（含 `confirmExpiresInSeconds`），
> 前端翻译层据此重建等价内部事件，保证确认倒计时、风险标签等既有展示能力零退化。
> 🔴 **`CUSTOM("completion")` 的 value 字段**：`finishReason` / `messageId` / `status` / `title` / `errorCode` / `errorMessage`。

#### 5.5.4 工具确认交互（interrupt/resume，🔴 决策已定、实现待落地）

> 🔴 **目标态**（决策 3）：工具需确认时，后端发 `RUN_FINISHED(outcome=interrupt, interrupts=[toolCallId])`
> **结束当前 Run**；用户确认后以新 Run 携带 `resume=[{interruptId, status: resolved|cancelled}]` 恢复执行。
>
> ⚠️ **当前实现态**（过渡）：确认等待仍为"同流内阻塞等待"（ADR-008/010/017 既有的成熟机制），
> `awaiting_confirmation` 状态经 `CUSTOM("tool_progress")` 下发、confirm 接口唤醒 —— 行为与旧契约等价，
> 仅帧格式迁移。**interrupt/resume 的跨 Run 状态持久化恢复属独立架构变更**（触及
> `ToolOrchestrator` 执行循环的挂起/恢复、`ToolConfirmRegistry` 阻塞原语、生成上下文的序列化），
> 需单独 ADR 裁决后再实施，不在本次协议迁移范围内。

---

## 6. M2 接口占位（管理治理闭环）—— 🔴 全量 Deferred 至二期

> 🔴 **Boss 决策（PRD V1.2 §2.2 / §2.3 / DEC-008 / DEC-010，2026-08-13）**：
> **M2「管理后台全量」整体 Deferred 至二期，一期不实现、不签署、不计入 M3 缺陷**。
> 状态是 **Deferred，不是 Cancelled**：二期必须完整补做并由六位专家独立签署。
> 一期所有租户级配置（站点配置、Agent、Skill、MCP Server、Tool 授权、成员角色）由 **DBA/开发人员直接写库**维护，不提供任何 `/admin/*`、`/platform/*` 页面或入口。
>
> 本表仅登记路径与职责，**请求/响应字段不在本版补全**；二期启动前由 @架构师 升级为正式契约。**未补全前 @后端 不得实现任何标注 `Deferred` 的条目**。
>
> ⚠️ 部分条目已被 Boss 提升为 **M2-min（M3 最小后端前置）**，其正式契约见 §7，**不受本节 Deferred 约束**；「一期状态」列为唯一判据。

| 方法 | 路径 | 职责 | 权限 | 一期状态 |
|---|---|---|---|---|
| GET / PUT | `/api/v1/admin/site/config/draft` | 站点配置草稿读写 | `USER` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})` | Deferred |
| POST | `/api/v1/admin/site/config/validate` | 草稿校验（返回 `violations[]`） | 同上 | Deferred（改库后的独立校验已提升，见 **§7.3.1**） |
| POST | `/api/v1/admin/site/config/publish` | 发布（原子切版本指针） | 同上 | Deferred |
| POST | `/api/v1/admin/site/config/rollback` | 回滚（生成新版本） | 同上 | Deferred |
| GET | `/api/v1/admin/site/config/versions` | 版本列表 | 同上 | Deferred |
| GET | `/api/v1/admin/site/config/preview/{token}` | 草稿预览（10 分钟失效、noindex） | 同上 | Deferred |
| GET/POST/PUT/DELETE | `/api/v1/admin/agents[/{id}]` | Agent 增删改查 | 同上 | Deferred |
| POST | `/api/v1/admin/agents/{id}/copy` \| `/publish` \| `/enable` \| `/disable` \| `/default` | Agent 复制/发布/启停/默认 | 同上 | Deferred |
| GET/POST/PUT/DELETE | `/api/v1/admin/skills[/{id}]` + `/publish` | Skill 管理与发布 | 同上 | Deferred（运行时消费与校验见 **§7.5**） |
| POST/PUT/DELETE | `/api/v1/admin/mcp[/{id}]` | MCP 配置写操作（凭据只写不回显） | 同上 | Deferred（一期由 DBA 写库） |
| GET | `/api/v1/admin/mcp/{id}` | MCP 配置只读查询（含凭据状态） | 同上 | ✅ **已提升 M2-min → §7.4.1** |
| POST | `/api/v1/admin/mcp/{id}/test` | 连接测试（分类结果） | 同上 | ✅ **已提升 M2-min → §7.4.2** |
| POST | `/api/v1/admin/mcp/{id}/discover` | 工具发现 | 同上 | ✅ **已提升 M2-min → §7.4.3** |
| PUT | `/api/v1/admin/mcp/{id}/tools/{toolKey}/grant` | 工具逐项授权（新发现默认禁用） | 同上 | Deferred（一期授权走 DB 写入，数据契约见 **§7.4.4**） |
| GET / PUT | `/api/v1/admin/tools[/{toolKey}/grant]` | 本地 Tool 授权与非代码配置 | `USER` + `@TenantRole({TENANT_ADMIN})` | Deferred（一期授权走 DB 写入，数据契约见 **§7.7.2**） |
| GET / PATCH | `/api/v1/admin/members[/{uid}]` | 成员列表 / 角色与状态变更 | `USER` + `@TenantRole({TENANT_ADMIN})` | Deferred |
| GET | `/api/v1/admin/audit` | 租户审计查询（只读，不可删改） | `USER` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})` | Deferred（一期审计**只写不查**，见 **§7.14**） |
| GET/POST/PATCH | `/api/v1/platform/tenants[/{id}]` | 租户创建 / 启停 / 归档 | `ADMIN` | Deferred |
| GET/POST/DELETE | `/api/v1/platform/domains[/{id}]` | 域名绑定与主 Host 切换 | `ADMIN` | Deferred |
| GET/POST/PUT | `/api/v1/platform/local-tools[/{id}]` | 平台本地 Tool 注册表 | `ADMIN` | Deferred（一期注册走 DB 写入，数据契约见 **§7.7.1**） |
| POST | `/api/v1/platform/access-grants` | 受控跨租户临时授权（≤30 分钟，强制原因 + 审计） | `ADMIN` | Deferred |
| GET/PUT | `/api/v1/platform/sys-config[/{id}]` | 平台配置管理（改后主动失效缓存） | `ADMIN` | Deferred（缓存失效已提升，见 **§7.2.1**） |

---

## 7. M2-min + M3 正式契约（🔴 @后端 可据此实现）

> 本节自 V1.1 起从「占位」升级为**正式契约**：每个接口给出方法、路径、权限注解、请求头、字段表、成功响应示例、错误码清单与 REQ/AC 追溯。
> 一期无管理 UI，本节接口的验收方式为**单测 + 集成测试 + 接口实测 + 数据核验**（PRD §8.10 第 7 条），不存在管理页面不构成缺陷。

### 7.1 接口总表与配置键

#### 7.1.1 接口总表

| # | 方法 | 路径 | 权限 | 里程碑 | REQ / AC |
|---|---|---|---|---|---|
| 1 | POST | `/api/v1/platform/cache/evict` | `@Permission(ADMIN)` | M2-min | REQ-CFG-004 / AC-CFG-005、AC-AUD-003 |
| 2 | POST | `/api/v1/admin/config/validate` | `@Permission(USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})`；🔴 平台作用域对象（`objectType=localTool`）在方法体内追加平台管理员判定 → 不足 `10003`（V1.1.5 G-5） | M2-min | REQ-CFG-003、REQ-SKL-001、REQ-TOL-001 / AC-CFG-003、AC-SKL-001、AC-TOL-001 |
| 3 | GET | `/api/v1/admin/mcp/{mcpId}` | `@Permission(USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})` | M2-min | REQ-MCP-001 / AC-MCP-001 |
| 4 | POST | `/api/v1/admin/mcp/{mcpId}/test` | 同上 | M2-min | REQ-MCP-001 / AC-MCP-001、AC-MCP-004、AC-AUD-003 |
| 5 | POST | `/api/v1/admin/mcp/{mcpId}/discover` | 同上 | M2-min | REQ-MCP-002 / AC-MCP-002、AC-MCP-005 |
| 6 | — | Skill 运行时消费（**无对外 HTTP 接口**，仅数据契约 + 校验入口 #2） | — | M3 | REQ-SKL-002 / AC-SKL-002、AC-CHAT-007 |
| 7 | — | MCP 运行时调用（**无对外 HTTP 接口**，SSE 内以 `tool` 事件呈现） | — | M3 | REQ-MCP-003 / AC-MCP-003～006 |
| 8 | — | 本地 Tool 运行时编排（**无对外 HTTP 接口**） | — | M3 | REQ-TOL-002 / AC-TOL-002、AC-TOL-003 |
| 9 | POST | `/api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm` | `@Permission(USER)` + 本人 | M3 | REQ-TOL-002、REQ-CHAT-003 / AC-TOL-002 |
| 10 | GET | `/api/v1/conversations/{conversationId}/tool-calls` | `@Permission(USER)` + 本人 | M3 | REQ-CHAT-003 / AC-CHAT-007 |
| 11 | POST | `/api/v1/events` | `@Permission(NO)`（含匿名） | M3 | REQ-OBS-001 / AC-OBS-001 |
| 12 | GET | `/api/v1/admin/metrics/usage` | `@Permission(USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})` | M3 | REQ-OBS-001 / AC-OBS-001 |
| **13** | **GET** | **`/api/v1/me/quota`** | **`@Permission(USER)`**（🔴 **仅本人**；不接受任何 `tenantId` / 目标 `uid` 参数） | **M3.1** | REQ-QUOTA-003 / AC-QUOTA-010、AC-QUOTA-013、AC-QUOTA-016 |

> 🔴 **租户隔离（全节适用）**：以上接口除 #1（平台作用域）外，一律要求**已建立租户上下文**；所有查询与写入必须显式 `where tenant_id = 当前租户`；跨租户 ID 一律按 `10004` 处理且不泄露存在性（AC-TEN-004）。请求体/请求头中的 `tenantId` **一律忽略并记安全日志**（EX-003）。
> 🔴 **一期不存在**任何管理 UI；#2～#5、#12 仅供接口实测与数据核验使用。
> 🔴 **V1.2.5 说明**：#13 是**终端用户接口**（不是管理端），已有生产 UI 消费（Composer 额度区），🔴 不属于"仅供实测"之列。

#### 7.1.2 本节新增的 `sys_config` 键（🔴 代码中禁止出现对应字面量）

| group | key | value_type | 默认值 | is_frontend | 用途 |
|---|---|---|---|---:|---|
| `tool` | `max_rounds` | NUMBER | `5` | 0 | 单次生成的工具调用轮次上限，超限 → `30054` |
| `tool` | `confirm_wait_seconds` | NUMBER | `120` | **1** | 高风险工具确认等待上限；超时按拒绝收敛并落 `timed_out`（前端倒计时需要，故下发） |
| `tool` | `confirm_poll_interval_millis` | NUMBER | `200` | 0 | 生成线程等待确认的兜底轮询间隔（跨实例信号） |
| `tool` | `default_timeout_seconds` | NUMBER | `30` | 0 | 工具执行默认超时（PRD §8.7） |
| `tool` | `max_timeout_seconds` | NUMBER | `120` | 0 | 工具执行超时上限，注册值超此上限 → `30060` |
| `tool` | `result_max_bytes` | NUMBER | `1048576` | 0 | 单次工具结果上限（1MB），超出截断并标记 `truncated`（EX-017） |
| `tool` | `args_summary_max_chars` | NUMBER | `200` | 0 | `argsSummary` 截断阈值 |
| `tool` | `result_summary_max_chars` | NUMBER | `500` | 0 | `resultSummary` 截断阈值 |
| `mcp` | `require_https` | BOOLEAN | `true` | 0 | 🔴 **生产必须 `true`**，仅 `test` profile 可置 `false`；生产为 `false` 即为阻断上线项（同 `dev_host_mapping_enabled` 规格） |
| `mcp` | `connect_timeout_seconds` | NUMBER | `10` | 0 | 建连超时 |
| `mcp` | `call_timeout_seconds` | NUMBER | `30` | 0 | `tools/call` 超时，被 `mcp_servers.timeout_seconds` 覆盖时取两者较小值 |
| `mcp` | `discover_timeout_seconds` | NUMBER | `15` | 0 | `tools/list` 超时 |
| `mcp` | `blocked_ip_cidrs` | JSON | `["127.0.0.0/8","::1/128","0.0.0.0/8","169.254.0.0/16","10.0.0.0/8","172.16.0.0/12","192.168.0.0/16","100.64.0.0/10","fc00::/7","fe80::/10"]` | 0 | SSRF 禁止范围（含云元数据 `169.254.169.254`） |
| `mcp` | `allowed_internal_cidrs` | JSON | `[]` | 0 | 平台授权内网白名单；命中白名单可豁免上一项（`test` profile 配 `["127.0.0.1/32"]` 以放行内置 Mock） |
| `mcp` | `transport_preferred` | STRING | `streamable_http` | 0 | 首选传输；`sse` 仅在服务端不支持首选时使用 |
| `mcp` | `max_tools_per_server` | NUMBER | `100` | 0 | 单服务发现工具数上限，超出 → `30060` |
| **`mcp`** | **`sse_legacy_enabled`** | BOOLEAN | **`true`** | 0 | 🔴 **V1.2.0 新增（G6′ 裁决）**：是否允许 `sse` 传输进入 **2024-11-05 异步推送形态**（POST 回 `202` 空体、结果从 GET 流推送）。🔴 **读取 fail-closed**（缺行/不可解析 → `false`）：它是"要不要发起并持有一条 SSE 流"的能力开关，取保守值即**完整回到 G6 行为**（POST 空体 → `protocol_incompatible` / `30052`），是运维**不改代码的止血手段** |
| **`mcp`** | **`sse_stream_max_bytes`** | NUMBER | **`4194304`** | 0 | 🔴 **V1.2.0 新增（G6′ 裁决）**：单次 exchange 内 SSE 流的**累计字节上限**（4MB），超限 → 强制关流 + `protocol_incompatible` + `[SECURITY]` 日志（防"无限流"拖死线程与内存）。🔴 **取值不变量：必须 ≥ `tool.result_max_bytes`**（否则合法的满长工具结果必然失败），违反时 `StartupChecker` **拒绝启动**（同 `analytics_sample_rate` 规格，⚠️ 非 WARN） |
| `skill` | `instruction_max_chars` | NUMBER | `50000` | 0 | 指令正文上限（PRD §8.5） |
| `skill` | `max_variables` | NUMBER | `50` | 0 | 单 Skill 声明变量数上限 |
| `observability` | `analytics_batch_max` | NUMBER | `50` | **1** | `POST /api/v1/events` 单次批量上限（前端需据此分批） |
| `observability` | `analytics_allowed_events` | JSON | 见 §7.10.1 | **1** | 允许上报的事件名白名单 |
| `observability` | `analytics_anonymous_enabled` | BOOLEAN | `true` | 0 | 是否接收匿名事件 |
| **`observability`** | **`analytics_enabled`** | BOOLEAN | **`true`** | 0 | 🔴 **V1.1.4 补登（#1 裁决）**：埋点总开关。`false` → §7.10.1 全部丢弃并返回 `code=0` + `accepted=0`。🔴 **读取 fail-closed**：缺行/读取失败 → 按 `false` 处理（口径见 §7.10.1） |
| **`observability`** | **`analytics_sample_rate`** | NUMBER | **`1.0`** | 0 | 🔴 **V1.1.4 补登（#1 裁决）**：稳定采样率，取值区间 **[0.0, 1.0]**（含端点）。按 `hash(clientEventId)` 判定，同一 `clientEventId` 多次上报判定必须一致。🔴 **读取 fail-closed**：缺行/读取失败/越界 → 按 `0.0`（全部丢弃）处理 |
| `display` | `tool_risk_labels` | JSON | `[{"value":"low","label":"低风险"},{"value":"medium","label":"需注意"},{"value":"high","label":"高风险"}]` | **1** | 风险等级展示文案（前端禁止硬编码） |
| **`chat`** | **`system_prompt_max_chars`** | NUMBER | **`100000`** | 0 | 🔴 **V1.1.3 新增（① 裁决）**：`ContextAssembler` 组装出的 **system 消息总长上限（字符/码点数）** = `agent_versions.system_prompt` + 全部已绑定 Skill 的 `instruction` + `output_constraint`（含变量替换后的实际长度）。超限 → `30060` `rule=systemPromptBudgetExceeded`，🔴 **fail-closed，禁止截断**（口径见 §7.5.2 第 3 条） |
| **`chat`** | **`context_max_chars`** | NUMBER | **`24000`** | 0 | 🔴 **V1.1.9 新增**：**最近消息窗口的总长预算（码点）**。🔴 **为什么条数不够**：`message_max_chars`(20000) × `context_max_messages`(20) = **40 万字符**，远超任何模型窗口 —— 缺预算时上游会拒绝**整个请求**，而窗口每轮都重新纳入同样的超长历史，表现为**该会话永久不可用**（用户重试无效，只能新建会话）。窗口**从最新往回填充**，超预算即停（口径见 §7.5.4） |
| **`chat`** | **`context_summary_item_max_chars`** | NUMBER | **`120`** | 0 | 🔴 **V1.1.9 新增**：历史摘要中**单条消息**的截断上限（码点）。原为代码字面量 `120`，属反硬编码红线违规 —— 它决定"摘要保留多少细节"，运维必须能不改代码调整 |
| **`chat`** | **`generation_deadline_seconds`** | NUMBER | **`300`** | 0 | 🔴 **V1.2.2 新增（ADR-017）**：**单次生成的业务总预算（秒）** = 全部模型轮次 + 工具执行 + **等待用户确认**的总和。🔴 它是"生成最长驻留"的**唯一权威**，取代原先由 `spring.mvc.async.request-timeout` 兼任的职责（后者降级为**纯传输层硬兜底**）。耗尽 → `error(50002)` + `done(finishReason=timeout, status=failed)`，🔴 已生成内容必须落库保留 |
| **`chat`** | **`deadline_grace_seconds`** | NUMBER | **`15`** | 0 | 🔴 **V1.2.2 新增（ADR-017）**：**收尾宽限（秒）**，一值两用 —— ⓐ `SseEmitter` 的 timeout = `generation_deadline + grace`（🔴 传输层比业务多活 grace 秒，保证 `error` + `done` 写得出去）；ⓑ 业务侧在 `剩余 ≤ grace` 时**不再开启任何新工作**（新轮模型 / 新确认卡 / 新工具执行）。🔴 取值不变量见本表下方 |
| **`chat`** | **`tool_usage_guideline`** | STRING | 见下方默认文案 | 0 | 🔴 **V1.2.2 新增（ADR-018 ②）／V1.2.4 形态订正（ADR-019）**：**平台级工具调用纪律段**，🔴 仅当**本次生成确实下发了 `tools`** 时注入。🔴 **注入形态**：**合并进唯一的 `system` 消息，恒为最后一块**（顺序与预算口径见 §7.5.2 ⑥）—— ⚠️ V1.2.2 的「**独立的第二条 `system` 消息**」已**作废**（真实上游 `status=400`「system 角色必须位于列表的最开始」，test-report V4.1 BUG-MCP-004）。🔴 支持唯一占位符 `{{currentTime}}`（替换为服务器当前时间 ISO-8601）。🔴 **仍不计入** `chat.system_prompt_max_chars` 预算（它是**平台段**而非租户配置）—— 🔴 **物理合并 ≠ 预算合并**。🔴 措辞必须**与具体工具无关**（不得出现任何上游字段名），否则等于"替上游猜语义"（ADR-018 ① 已否决该方向） |
| **`ratelimit`** | **`qpm_enabled`** | BOOLEAN | **`true`** | 0 | 🔴 **V1.2.5 新增（ADR-020）**：**QPM 限流总开关的平台默认值**。`false` → 不执行分钟窗判定（🔴 **不影响**日限额）。🔴 **读取 fail-open 侧的例外说明**：它是**拦截型开关**（§11 原则允许 fail-open），但本键**缺失/不可解析**时一律走 `BusinessConfig.requireBoolean` → `50003`（🔴 反硬编码红线优先：平台默认值缺失必须暴露，不得静默按 `true` 或 `false` 继续） |
| **`ratelimit`** | **`daily_quota_enabled`** | BOOLEAN | **`true`** | 0 | 🔴 **V1.2.5 新增（ADR-020）**：**每日额度总开关的平台默认值**。`false` → 该租户用户不受每日次数限制，额度快照 `status=unlimited`、`limit=null`、`remaining=null`（🔴 **禁止**伪造一个数值上限） |
| **`ratelimit`** | **`daily_quota_limit`** | NUMBER | **`50`** | 0 | 🔴 **V1.2.5 新增（ADR-020）**：**每日额度阈值的平台默认值**（次/租户当地日历日/用户）。🔴 代码中禁止出现 `50` 字面量；🔴 取值不变量 ≥1（见本表下方，违反即**拒绝启动**） |

> 沿用既有键：`chat.stream_heartbeat_seconds`、`chat.idempotency_ttl_seconds`、`chat.message_max_chars`、`chat.context_max_messages`、**`ratelimit.message_per_minute`（🔴 V1.2.5 键值 30 → **3**，键本身不变）**、`display.tool_status_labels`、`business.page_size_default`、`business.page_size_max`。
> 🔴 **V1.2.5 废弃并删行：`ratelimit.message_per_hour`**（原 120，PRD V1.4 §8.11.9 裁决"小时窗在 50 次/日下无独立业务价值"）。处置为 **①** 移除代码读取点（`MessageRateLimiter` 小时窗分支）**②** 从 `StartupChecker.REQUIRED_CONFIG` 移除 **③** 删除 `ConfigKeys.MESSAGE_PER_HOUR` 常量 **④** `DELETE` 库内该行（🔴 顺序必须是 ①②③ 上线后再 ④，否则启动即失败）。
> 🔴 **明确否决"保留键但不再读取"**：那会留下一个"库里写着 120、改它却毫无效果"的幽灵配置 —— 这类"配置骗人"正是 AR-021 反复要消灭的模式。残留的 Redis 小时窗键（`…:limit:msg:{uid}:h{yyyyMMddHH}`）由自身 TTL 在 ≤1h 内自然回收，🔴 无需清理动作。
> 🔴 **V1.1.4 订正（#1）**：`observability.analytics_enabled` / `analytics_sample_rate` 原被本注列为"沿用既有键"，但它们**从未**出现在本表、也**从未**出现在 `architecture.md` §13.3（M1 初始化）或 §13.6（M3 登记），库内原本亦无对应行 —— 属**登记缺口**（"沿用"认定本身是错的）。本版将两键从本注移出，正式登记入上表并同步 `architecture.md` §13.6。
> 🔴 新增键必须同时：① 登记本表 ② 在 `sysconfig/ConfigKeys.java` 新增常量 ③ 由 @后端 插入 `sys_config` 记录 ④ 在 `docs/architecture.md` §13.6 初始化数据登记。
> 🔴 **本节键总数 = 25**（V1.1 的 22 键 + V1.1.3 新增 `chat.system_prompt_max_chars` + V1.1.4 补登 `observability.analytics_enabled` / `analytics_sample_rate`），全部纳入 `config/StartupChecker.REQUIRED_CONFIG`（缺键即启动失败，`architecture.md` §13.6 纪律 2）。
> 🔴 **V1.1.9 增补 2 键**（`chat.context_max_chars` / `chat.context_summary_item_max_chars`）→ **本表现共 27 键**，同样全部纳入 `REQUIRED_CONFIG`。
> 🔴 **V1.2.0 增补 2 键**（`mcp.sse_legacy_enabled` / `mcp.sse_stream_max_bytes`，G6′ 裁决）→ **本表现共 29 键**，同样全部纳入 `REQUIRED_CONFIG`；🔴 代码中禁止出现 `true` / `4194304` 字面量，🔴 禁止用代码默认值兜底（`BusinessConfig.requireXxx` 语义不变）。
> ⚠️ 「25 键」这一历史表述保留不改（它锁定的是 M3 交付时的键集，`StartupCheckerRequiredConfigTest` 仍按 25 键断言该子集完整），
> V1.1.9 两键由该测试的独立用例 `contextBudgetKeysAreRequired` 覆盖 —— 🔴 这样既不篡改已冻结的历史契约，也不给新键留豁免口。
> 🔴 **`observability.analytics_sample_rate` 的取值不变量（V1.1.4，#1 裁决）**：必须落在 **[0.0, 1.0]**。🔴 违反时 `StartupChecker` **拒绝启动**（⚠️ 与 `chat.system_prompt_max_chars` 的 WARN 规格**不同**，此处**必须硬失败**）—— 理由：`system_prompt_max_chars` 区间外仍是"运维可能有意为之的调参"，而采样率 `1.5` / `-0.2` **没有任何合法语义**，放行只会让"我以为在采样 150%"的误配悄悄变成全量或全丢。
> 🔴 **`chat.system_prompt_max_chars` 的取值不变量**：必须 **≥ `skill.instruction_max_chars`**（50000），否则"单个合法的满长 Skill 一旦绑定即必然 `30060`"这一自相矛盾状态会出现。`StartupChecker` 在违反该不变量时打印 **WARN**（🔴 不做启动拒绝，与 `architecture.md` §9.5.4 不变量 2 的 WARN 先例同规格 —— 否则运维调参会被启动检查卡死）。

> 🔴 **V1.2.2 增补 3 键**（`chat.generation_deadline_seconds` / `chat.deadline_grace_seconds` / `chat.tool_usage_guideline`，ADR-017 / ADR-018）→ **本表现共 32 键**，同样全部纳入 `StartupChecker.REQUIRED_CONFIG`（🔴 含纪律段：缺键/空白即**启动失败** —— 它是编排正确性的一部分，🔴 **不提供关闭开关**，要弱化引导请改文案本身）；🔴 代码中禁止出现 `300` / `15` / 纪律段文案字面量。
> 🔴 **V1.2.2 新增取值不变量（🔴 拒绝启动级，共 2 条）**：
> ① `chat.generation_deadline_seconds + chat.deadline_grace_seconds ≤ spring.mvc.async.request-timeout / 1000`
> ② `chat.deadline_grace_seconds ≥ 5`
> 🔴 **为什么是拒绝启动而非 WARN**（判据同采样率：区间外**有无合法语义**）：一旦业务预算 + 宽限 > 传输层上限，**传输层必然先超时** → `SseEmitter` 已关闭 → `done` 帧物理上写不出去 → §5.1 第 3 条「`done` 必发」在该配置下**必然被违反**（这正是 BUG-MCP-002 的成因）；`grace < 5s` 同理 —— 来不及完成"落库终态 + 写 `error` + 写 `done`"。
> ⚠️ **配套运维动作（🔴 必须与本版同时生效）**：`application.yml` 的 `spring.mvc.async.request-timeout` 由 `300000` 提到 **`600000`**（它此后**只是传输层硬兜底**，业务封顶已交给 `sys_config`；属 `architecture.md` §7.1 白名单内的基础设施参数）。
> 🔴 **WARN 级不变量（不拒绝启动）**：`chat.generation_deadline_seconds ≥ chat.first_token_timeout_seconds`；以及 `tool.max_rounds ×(tool.max_timeout_seconds + tool.confirm_wait_seconds) > chat.generation_deadline_seconds`（提示"最坏情形跑不完全部轮次"是**有意为之**）。

> 🔴 **V1.2.5 增补 3 键**（`ratelimit.qpm_enabled` / `ratelimit.daily_quota_enabled` / `ratelimit.daily_quota_limit`，ADR-020）→ **本表现共 35 键**，三键全部纳入 `StartupChecker.REQUIRED_CONFIG`（缺键/空白即**启动失败**）；🔴 代码中禁止出现 `3` / `50` / `true` 字面量作为阈值或开关。
> 🔴 **同时从 `REQUIRED_CONFIG` 移除 1 键**（`ratelimit.message_per_hour`）→ 必备键净 **+2**。
> ⚠️ 「25 键 / 32 键」这些历史表述**保留不改**（它们锁定的是各里程碑交付时的键集，`StartupCheckerRequiredConfigTest` 仍按 25 键子集断言）；🔴 `ratelimit.*` 属 **M1 键集**，因此移除 `message_per_hour` **不影响**该 25 键子集断言 —— @后端 不得借机改动那条历史断言。
> 🔴 **V1.2.5 新增取值不变量（🔴 拒绝启动级，共 2 条）**：
> ③ `ratelimit.message_per_minute ≥ 1`
> ④ `ratelimit.daily_quota_limit ≥ 1`
> 🔴 **为什么是拒绝启动而非 WARN**（判据同采样率："区间外**有无合法语义**"）：`0` 或负数意味着"任何人一次都不能发"，而"关闭 QPM / 关闭日限额"的**唯一合法表达**是把对应的 `*_enabled` 置 `false` —— 因此 `≤0` 没有任何合法运维语义，放行只会让一次误配把整站对话打死（且表现为"每个人第一条消息就被限流"，极难与真实限流区分）。
> 🔴 **V1.2.5 新增 WARN 级不变量（1 条）**：`ratelimit.daily_quota_limit ≥ ratelimit.message_per_minute`。
> 🔴 **为什么只 WARN**：日限额小于 QPM 时 QPM 永不先触发（用户一分钟内就能打完全天额度），这是"运维**有意**收紧日额度"的合法调参（如试用租户 daily=2），不是自相矛盾状态 —— 同 `chat.system_prompt_max_chars` 的 WARN 先例。

**🔴 `chat.tool_usage_guideline` 的默认文案（V1.2.2 新增；DBA 按此插入，🔴 代码中不得出现该文案字面量）**
**🔴 V1.2.4 明确：本文案 **一字未改**，DBA **无任何动作** —— BUG-MCP-004 的订正只改它在请求中的物理摆放位置（由"第 2 条 system 消息"改为"唯一 system 消息的末块"），🔴 严禁以"上游报 400"为由删除该键、清空该值或删减纪律条目（那是回退 BUG-MCP-001 的修复，`StartupChecker` 亦会因空白而拒绝启动）。**

```text
当前服务器时间：{{currentTime}}。调用工具时请遵守以下纪律：
1. 只传必填参数；可选参数除非用户明确给出、或你已通过其它工具确认过取值，一律省略。
2. 不要凭推测填写时间、时间戳、枚举值、区域或站点等参数；需要"现在"的时间时以上述服务器时间为准。
3. 工具返回失败时，请阅读失败说明并据此调整；若说明表明参数不合法，优先改为"仅传必填参数"重试一次，
   仍失败则直接向用户说明，不要反复用不同写法重试同一个工具。
4. 每次工具调用都可能需要用户手动确认，请勿发起不必要的调用。
```

> 🔴 **文案纪律（逐条为 @测试 的反向断言依据）**：① 🔴 **禁止**出现任何具体工具名或上游字段名（如 `Mode` / `Query` / `wsa-SearchPro`）—— 一旦出现即等于把 ADR-018 ① 否决的"替上游猜语义"从 schema 挪进 prompt；② 🔴 **建议 ≤ 500 码点**（它不计入 `system_prompt_max_chars`，写成长篇会**无声挤占**上游输入窗口 → 已登记 `architecture.md` **AR-021**；📋 可选加固见 ADR-019 落点 #8：启动期 >500 码点打 **WARN**，🔴 不拒绝启动）；③ 它是**引导**而非**保证** —— 🔴 不得以"纪律里写了"为由削弱任何 Schema 校验、风险确认或审计；④ 🔴 **V1.2.4 新增**：纪律段现为 `system` 消息的**末块** → @测试 断言形态为 **`systemContent.endsWith(占位符替换后的纪律段)`**，🔴 「存在第 2 条 system 消息」的旧断言**作废**；⑤ 🔴 **禁止**为标注来源而在文案里另加可见分隔标记（如「【平台纪律】」）—— 那属**文案**须入库，且把平台内部结构喂给模型零收益。

**🔴 缓存 TTL 键的一期口径（V1.1.1 回写，与 `architecture.md` §12.1.1 / §12.2 一致）**：

| 作用域 | 一期缓存策略 | TTL 配置键 | 说明 |
|---|---|---|---|
| Skill 版本快照（含 `instruction` 正文） | 🔴 **不缓存，直读 MySQL** | **预留 — 一期禁用**（🔴 本表**不登记**，禁止自造） | 指令正文是内部资产（禁入进程内共享缓存）；版本不可变故理论上可进 L2，但一期无 TTL 键故不启用。键名已在 `architecture.md` §12.2 锁定为 `albedo:{env}:{tenantId}:skill:version:{skillId}:{version}` |
| MCP 工具授权清单（`mcp_tools`） | 🔴 **不缓存，直读 MySQL** | **预留 — 一期禁用**（同上） | 一期由 DBA 直接改库授权；缓存会破坏 AC-MCP-004「改库为非法/取消授权后运行时仍须拒绝」。键名锁定为 `…:{tenantId}:mcp:tools:{mcpId}` |
| 本地 Tool 授权清单（`tenant_tool_grants`）与能力绑定（`agent_capability_bindings`） | 🔴 **不缓存，直读 MySQL** | **预留 — 一期禁用**（同上） | 同上，破坏 AC-CFG-004「运行时兜底以**当前库内**配置为准」。键名锁定为 `…:{tenantId}:tool:grants` |

```
🔴 一期结论（不是遗漏，是有意为之）：
① 以上三类数据一律**直读 DB**，从而天然满足「DBA 改库即生效、无需重启也无需失效缓存」
   （AC-CFG-004 / AC-MCP-004）—— 这比引入缓存再补失效逻辑更简单且更安全；
② 性能已评估可承受：工具清单构造收敛为 **≤5 次批量查询**（口径见下表），
   且发生在异步段（不占首字预算，见 §5.4.2 / architecture.md §9.5.3）；🔴 禁止 N+1 逐个绑定单查；
③ 🔴 因此本表**不登记**任何 skill/mcp/tool 授权类的 TTL 键 —— @后端 **禁止自造**
   （如 skill.version_cache_ttl_seconds、mcp.tools_cache_ttl_seconds、tool.grants_cache_ttl_seconds）；
④ 二期启用缓存的前置条件（两条必须同时完成）：
   ⓐ 在本表（§7.1.2）登记对应 TTL 键；
   ⓑ 在 §7.2.1 的 scope=tenant / agentVersion 失效清单中补入这三类键。
```

**🔴 查询次数口径（V1.1.4 订正，#3 裁决；原文"≤3 次"是错的，@测试 按本表断言）**

```
结论：❌ **不要求**运行时收敛到 ≤4 次；✅ 订正文档口径为「运行时 ≤5 次」。
理由：原"≤3 次"表述遗漏了两次**不可省略**的二级查询 —— mcp_servers（取 status /
endpoint / transport / 凭据，SSRF 与连通性判定必需）与 local_tools（取实现体标识 /
input_schema / risk_level / timeout，参数校验与执行必需）。它们依赖前一次查询的结果集
（mcp_tools → mcp_servers、tenant_tool_grants → local_tools），🔴 不 join 就不可能 ≤3 次。
👉 因此这是**文档错、实现对**，订正文档而非改实现（改实现属为对齐错误数字而动首字链路）。
```

| 场景 | 允许的最大查询次数 | 允许的查询序列（🔴 逐条批量，禁止 N+1） |
|---|---:|---|
| **运行时清单构造**（`ToolCatalogService.buildCatalog`，异步段） | **≤5** | ① `agent_capability_bindings` 一次取全（`idx_tenant_version_sort`）② `mcp_tools` `IN` 批量 ③ `mcp_servers` `IN` 批量 ④ `tenant_tool_grants` `IN` 批量 ⑤ `local_tools` `IN` 批量 |
| **校验入口**（`POST /admin/config/validate`，`objectType=agentVersion`，§7.3.1 G10） | **≤4** | ① 绑定表一次取全 ② `mcp_tools LEFT JOIN mcp_servers` ③ `tenant_tool_grants LEFT JOIN local_tools` ④ `skills LEFT JOIN skill_versions` |
| **每次工具执行前的授权点查**（§7.6.3，V1.1.4 新增） | **≤1**（每次调用） | MCP：`mcp_tools JOIN mcp_servers` 单行点查；本地 Tool：`tenant_tool_grants JOIN local_tools` 单行点查 |

> 🔴 **为什么两个入口允许不同次数**：校验入口 QPS 极低且无首字约束，用 `left join` 收敛是**加分项**；运行时清单构造在异步段、与 JPA 实体映射耦合，强行改 join 会触碰首字链路（收益 ≤1 次往返，风险是 M3 最热的代码路径），🔴 **明确不要求**。
> 🔴 两者**不允许**的是同一件事：N+1 逐个绑定单查 —— 无论哪个入口，出现即为缺陷。
> ⚠️ 上表次数**不含** §7.6.3 的执行前点查（它按"每次工具执行"计，与清单构造不叠加统计）。


---

### 7.2 M2-min：平台缓存失效

#### 7.2.1 按作用域失效缓存

**POST** `/api/v1/platform/cache/evict`
**权限**：`@Permission(PermissionEnum.ADMIN)`（🔴 仅平台管理员）
🔴 **平台角色不足的失败码按 §3「二分口径」（V1.1.5 G-5 裁决）**：`eyes-auth.enabled=true`（生产/开发）→ **`20000`**（切面前置拦截）；`eyes-auth.enabled=false`（仅 test profile）→ **`10003`**（Controller 程序化兜底）。🔴 共同不变量：**绝不返回 `code=0`**（AC-CFG-005 的实质判据）。
**租户上下文**：不需要（平台作用域接口）；`scope` 需要目标租户时由请求体显式给出
**请求头**：`authorization`
**幂等**：天然幂等，但**每次调用独立审计**（§1.4、§7.14）

| 字段（Body） | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `scope` | string | 是 | 枚举 `tenant` \| `host` \| `sysconfig` \| `agentVersion` \| `all` | 失效作用域 |
| `tenantId` | string | 条件必填 | `^[a-z][a-z0-9-]{1,31}$` | `scope=tenant` / `agentVersion` 时必填 |
| `host` | string | 条件必填 | ≤253，小写 FQDN（服务端再规范化：去端口、转小写、去末尾点） | `scope=host` 时必填 |
| `configGroup` | string | 否 | ≤100 | `scope=sysconfig` 时可选；缺省失效该作用域全部配置项 |
| `agentId` | string | 否 | — | `scope=agentVersion` 时可选；缺省失效该租户全部 Agent 版本快照 |
| `reason` | string | 是 | 1~200 字符 | 🔴 审计必需，缺失 → `10001` |

**各 scope 必须失效的键（🔴 L1 进程内 + L2 Redis 两层都必须失效，缺一即为缺陷）**：

| scope | 失效内容 |
|---|---|
| `tenant` | 🔴 **Host 键 `…:platform:tenant:host:{host}`（该租户全部绑定 Host）与 租户号键 `…:platform:tenant:code:{tenantId}` 必须同时失效**（D-003/D-006 教训：只清一个会导致 dev 路径在 TTL 内继续读到旧 `configVersion`，表现为"发布了但不生效"）；同时失效 `site:config:{version}` 快照与该租户 Agent 版本快照、成员角色缓存 |
| `host` | 指定 Host 键 + 其解析出的租户号键（同样成对失效） |
| `sysconfig` | `…:platform:sysconfig:{group}:{key}`（按 `configGroup` 或全量）+ `ConfigService` L1 |
| `agentVersion` | 指定租户（可选指定 Agent）的 Agent 版本快照与能力绑定快照 |
| `all` | 以上全部作用域；🔴 高危操作，`reason` 必须说明工单号 |

> 🔴 **一期无需失效的作用域（V1.1.1 回写，与 `architecture.md` §12.1.1 / §12.2 一致）**：
> **Skill 版本快照、MCP 工具授权清单、本地 Tool 授权清单与能力绑定** 一期 **不缓存、直读 MySQL**（口径见 §7.1.2 末尾），
> 因此 `scope=tenant` / `agentVersion` 的失效清单中 **本期不含这三类键**，@后端 **不实现**其失效逻辑，@测试 **不将其纳入失效断言**。
> 🔴 这不是漏项：DBA 改库后本就立即生效（AC-CFG-004 / AC-MCP-004）。二期若启用这三类缓存，**必须同时**在 §7.1.2 登记 TTL 键并在本表补入对应键，两者缺一即为缺陷。
> 🔴 另注意（与 `architecture.md` §12.2 一致）：本接口**禁止**删除「运行时状态键」——`chat:idem:*`（幂等回放）、`chat:cancel:*`（停止生成）、`tool:confirm:*`（确认信号）、`limit:msg:*`（限流窗口）。它们不是缓存，删除会破坏语义；即便 `scope=all` 也必须跳过。

**成功响应**（全部作用域完成）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "requestedScope": "tenant",
    "results": [
      { "scope": "tenantHost", "target": "albedo-gift.eyescode.top", "l1Evicted": 1, "l2Evicted": 1, "status": "succeeded" },
      { "scope": "tenantCode", "target": "gift", "l1Evicted": 1, "l2Evicted": 1, "status": "succeeded" },
      { "scope": "siteConfig", "target": "gift", "l1Evicted": 0, "l2Evicted": 2, "status": "succeeded" },
      { "scope": "agentVersion", "target": "gift", "l1Evicted": 0, "l2Evicted": 3, "status": "succeeded" }
    ],
    "totalL1Evicted": 2,
    "totalL2Evicted": 7,
    "incompleteScopes": [],
    "auditEventId": "7f1c9a20b4e5427c8a1d3f6091e2c5db"
  },
  "timestamp": 1704067200000
}
```

> 🔴 **`auditEventId` 格式（V1.1.1 回写，全文统一，与 `architecture.md` §11.1.2 一致）**：**32 位 UUID hex，小写，无连字符**（正则 `^[0-9a-f]{32}$`），等于 `audit_logs.event_id` 列值，**对外原样返回**。
> 🔴 **禁止截断为短串**（V1.1 示例中的 8 位短串仅为排版示意，已在本版全部订正）；@前端 与 @测试 一律按 32 位断言，用于与 `audit_logs` 做数据核验对账。

**部分/全部失败**（🔴 禁止伪报成功，AC-CFG-005 / EX-033）：

```json
{
  "code": 30061,
  "message": "缓存失效未全部完成，请重试未完成作用域",
  "data": {
    "requestedScope": "all",
    "results": [
      { "scope": "sysconfig", "target": "*", "l1Evicted": 12, "l2Evicted": 0, "status": "failed" }
    ],
    "totalL1Evicted": 12,
    "totalL2Evicted": 0,
    "incompleteScopes": ["sysconfig"],
    "auditEventId": "7f1c9a21c05f4e1b93a7d2408b6ef113"
  },
  "timestamp": 1704067200000
}
```

**错误码**：`10001`（scope 非法 / 条件必填缺失 / 缺 `reason`）、`10003`（非平台管理员，🔴 **仅** `eyes-auth.enabled=false` 的 test profile；生产为 `20000`，见 §3 二分口径）、`20000`（非平台管理员，生产路径）、`20001/20002/20003/20004/20005`、`30061`（部分或全部失效失败）、`50003`（审计写入失败导致整体失败，EX-024）

> 🔴 **ADR-001 单实例前提**：L1 是进程内缓存，本接口只能失效**本 JVM** 的 L1。依据 `architecture.md` §12.1 / ADR-001（单体单一 JAR、单实例部署），失效与读取发生在同一 JVM，故"改完即生效"成立。若未来打破 ADR-001（多实例），本接口必须同步引入 L1 失效广播，否则契约失效——该约束已写入 ADR-001 推翻条件。
> 🔴 成功后**后续请求不得再命中旧值**（AC-CFG-005 判据）；`all` 与 `sysconfig` 作用域失效后允许出现一次冷启动延迟，但不得读到旧值。

---

### 7.3 M2-min：独立配置校验

#### 7.3.1 校验单对象及其引用链

**POST** `/api/v1/admin/config/validate`
**权限**：`@Permission(PermissionEnum.USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})`；🔴 `objectType=localTool`（平台作用域对象）在**方法体内**追加"必须是平台管理员（`role=ROLE_admin`）"的程序化判定，不足 → **`10003`**（V1.1.5 G-5 订正：原"改用 `@Permission(ADMIN)`"不可实现 —— 注解是方法级的，无法按请求体 `objectType` 分支；口径见 §3）
**租户上下文**：必需（`localTool` 除外）
**幂等**：天然幂等（只读校验，不写业务数据）

| 字段（Body） | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `objectType` | string | 是 | 枚举 `agent` \| `agentVersion` \| `skill` \| `skillVersion` \| `mcp` \| `localTool` \| `toolGrant` \| `siteConfig` | 待校验对象类型 |
| `objectId` | string | 是 | — | 对象 ID；🔴 非当前租户对象 → `10004`（不泄露存在性） |
| `includeReferences` | boolean | 否 | 默认 `true` | 是否沿引用链递归校验（Agent 版本 → Skill 版本 / MCP 工具 / 本地 Tool 授权） |

**校验项**（PRD §8.10 第 1 条）：必填字段、枚举合法性、长度上限、被引用资源状态可用、版本引用存在且不可变、**同租户归属**、Tool 授权存在、MCP endpoint HTTPS/SSRF、凭据密文格式与密钥版本、Skill `{{variable}}` 声明完整性、超时与结果上限落在 `sys_config` 允许区间。

**🔴 裁决（G10）`agentVersion → 绑定` 的递归校验：允许分阶段，但 M3 签署前必须补齐**：

```
① 采纳 @后端 的分阶段安排：G1（绑定表登记）解锁后，运行时侧已覆盖绑定链路的兜底判定
   （非法配置在进入模型/工具执行前以 30060 失败，AC-CFG-004 已达标）。
② 🔴 但本接口的 includeReferences=true 是 AC-CFG-003「可独立调用校验」的**唯一**判据，
   若聚合入口不递归，DBA 就无法在**用户对话之前**发现绑定层的配置错误 ——
   这正是 ADR-013 方案 A 被否决的原因。因此本项**不是可选项，只是可延后**。
③ 🔴 收尾期限 = M3 签署前（@架构师 签署条件之一）。届时 objectType=agentVersion 必须递归覆盖：
   - capability_type='skill'     → skills + skill_versions（精确版本存在且 published、同租户、变量完整性）
   - capability_type='mcpTool'   → mcp_tools + mcp_servers（granted/status/endpoint SSRF/密文格式）
   - capability_type='localTool' → tenant_tool_grants + local_tools（授权四条件、实现体存在、超时区间、config 非代码）
   并在 data.checkedObjects 计入这些对象；违规项按 §7.3.1 的 violations[] 字段级返回。
④ 🔴 未补齐前，@测试 不得将「agentVersion 递归校验」判为通过项，需在 test-report 标记为
   Deferred-in-M3 并跟踪；@后端 不得在响应中把未递归的结果报成 valid=true 的"全量校验通过"
   —— 必须在 warnings[] 明示 rule=referencesNotFullyChecked（避免"伪报校验通过"）。
```

**🔴 G10 收尾裁决（V1.1.3，最终结论）：维持"M3 签署前必须补齐"，补齐后行为按下表**

```
🔴 结论：不放宽、不延期。递归校验是 AC-CFG-003「可独立调用校验」的唯一判据，
   也是 ① 的 system 提示预算、⑥ 的函数名碰撞这两类"只有聚合起来才能发现"的问题的
   唯一提前暴露点 —— 缺了它，这些问题只能等线上 30060。
   @后端 当前以 warnings[] rule=referencesNotFullyChecked 明示未伪报通过，✅ 处置正确，
   但它是**过渡态**，不是终态。
```

| 项 | 补齐后的确定行为 |
|---|---|
| 递归深度 | 🔴 **固定 2 层，禁止实现通用图遍历**：第 1 层 `agentVersion → agent_capability_bindings`；第 2 层按 `capability_type` 展开（`skill → skills + skill_versions`；`mcpTool → mcp_tools + mcp_servers`；`localTool → tenant_tool_grants + local_tools`）。🔴 **不存在第 3 层**（数据模型中无更深引用，§13.5.10），出现"需要第 3 层"的需求必须先回写本节 |
| 循环引用防护 | 🔴 维护 `visited = Set<objectType + ':' + objectId>`：已访问对象**跳过且不重复计入** `checkedObjects`；🔴 检测到重复进入**同一路径**（自引用/环）→ `30060` `rule=circularReference`（fail-closed，禁止无限递归、禁止靠栈深度兜底）。当前数据模型不可能成环，本条是**防御性**约束，防二期加引用时被静默递归爆栈 |
| 查询收敛 | 🔴 与运行时同口径：绑定表一次取全 → 按 `capability_type` 分组 → 三类被引用对象各一次 `IN` 批量查（**≤4 次查询**），🔴 禁止 N+1 逐个绑定单查（同 §9.5.3 纪律）；本接口无 UI、QPS 极低，但**不允许**以此为由写 N+1 |
| `checkedObjects` | = 实际校验过的对象数（含 `agentVersion` 自身 + 每条绑定 + 每个被引用对象，按 `visited` 去重后计数） |
| 新增校验项（🔴 必须一并覆盖） | ① §7.5.2 的 **system 提示总长预算**（`rule=systemPromptBudgetExceeded`，用同一实现累加）② §7.6.5 的**函数名长度/碰撞**（`rule=functionNameTooLong` / `functionNameCollision`）—— 这两项**只在聚合层面可见**，单对象校验永远发现不了 |
| `warnings` 何时消失 | 🔴 精确规则：**当且仅当** `includeReferences=true` **且**三类绑定全部完成上述递归 → `warnings[]` 中**不再出现** `referencesNotFullyChecked`；🔴 `includeReferences=false` 时**必须继续出现**该 warning（确实没查引用，不得因"功能已补齐"就不再提示 —— 否则调用方无法区分"没查"与"查了没问题"） |
| 验收判据 | @测试 以「故意让第 2 层非法（如把 `skill_versions.status` 改 `archived`、把 `mcp_servers.endpoint` 改内网、把 `tenant_tool_grants.granted` 置 0），调 `objectType=agentVersion&includeReferences=true` → 必须 `30060` 且 `violations[]` 精确指向第 2 层对象」为通过判据；🔴 同时断言 `warnings[]` 已不含 `referencesNotFullyChecked` |

**成功响应**（配置合法）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "objectType": "agentVersion",
    "objectId": "5",
    "valid": true,
    "checkedObjects": 7,
    "violations": [],
    "warnings": [
      { "objectType": "skillVersion", "objectId": "31", "field": "variablesSchema", "rule": "unusedVariable", "message": "已声明但未在指令中使用：city" }
    ]
  },
  "timestamp": 1704067200000
}
```

**失败响应**（配置非法，AC-CFG-003 / EX-031 / EX-032）：

```json
{
  "code": 30060,
  "message": "配置校验失败",
  "data": {
    "objectType": "agentVersion",
    "objectId": "5",
    "valid": false,
    "checkedObjects": 7,
    "violations": [
      { "objectType": "skillVersion", "objectId": "31", "field": "instruction", "rule": "undeclaredVariable", "message": "引用了未声明变量：{{userName}}" },
      { "objectType": "mcp", "objectId": "12", "field": "endpoint", "rule": "ssrfRejected", "message": "服务地址不在允许范围内" },
      { "objectType": "mcp", "objectId": "12", "field": "credentialCipher", "rule": "invalidCipher", "message": "凭据密文格式非法或密钥版本不受支持" }
    ]
  },
  "timestamp": 1704067200000
}
```

> 🔴 `violations[].message` **禁止**包含：密钥/凭据明文或片段、内部 IP/域名/端口、堆栈、其他租户资源存在性。`endpoint` 非法只回"不在允许范围内"，**不得回显解析出的 IP**。
> `warnings[]` 不影响 `code=0`（不阻断），仅供 DBA 自查。

**🔴 裁决（#2，V1.1.4）`30060` 的 `data` 载荷形状按「调用者身份」二分，运行时路径 🔴 不带 `violations`**

```
@后端 提出的口径（运行时复用本节 {objectType,objectId,valid,checkedObjects,violations,warnings}）
🔴 **不采纳**，修正为按调用者身份二分。判据是"谁能看到这段 data"，不是"哪个错误码"。
```

| 路径分类 | 典型触发点 | `data` 形状 | 理由 |
|---|---|---|---|
| **A. 管理端**（`@Permission(ADMIN)` 或 `@TenantRole` 保护） | §7.3.1 校验入口、§7.4.2 连接测试、§7.4.3 工具发现 | 🔴 **必须**为本节完整形状：`{objectType, objectId, valid, checkedObjects, violations[], warnings[]}` | 调用者是 DBA / 租户管理员，字段级明细正是接口价值（AC-CFG-003） |
| **B. 终端用户路径**（`END_USER` 可达：SSE `error` 事件、`/api/v1/conversations/**`、`/api/v1/messages/**`） | 运行时兜底 `30060`：`ContextAssembler` 提示预算超限、`ToolCatalogService` 函数名碰撞/超长、绑定链非法 | 🔴 **仅** `code` + `message`（SSE `error` 事件按 §5.2 三字段；JSON 响应 `data` 为 `null`） | `violations[]` 含 `objectType`/`objectId`/`field`/`rule` = **内部配置结构 + 内部对象 ID**。终端用户既无权处置、也不该看见；下发等于把租户配置拓扑泄露给任意登录用户（与 §5.2「禁止暴露内部资产」、§7.3.1「不泄露存在性」同一条防线） |

```
🔴 B 类路径的配套要求（缺一即为缺陷）：
① message 必须是**面向终端用户的通用语义**，且 🔴 不含 objectId / rule / 字段名 / 内部对象类型，
   建议固定为"当前助手配置异常，请联系管理员"（实际文案走前端 i18n，后端只给可展示语义）；
② 🔴 诊断信息不得丢失：同一次失败必须以 **ERROR 日志**记录
   `requestId + tenantId + agentVersion + rule + objectType:objectId`（过 LogScrubber），
   使 DBA 能凭前端展示的 requestId 反查到与 A 类完全等价的字段级信息；
③ 🔴 DBA 的字段级出口唯一 = §7.3.1 管理端校验接口（这正是 G10 要求递归校验的意义：
   B 类只报"有问题"，A 类负责回答"哪儿有问题"）；
④ 前端展示：通用错误态 + 可复制的 requestId，🔴 禁止解析/展示任何 violations 结构
   （§5.4.1 第 5 条已要求容忍字段缺失，@前端 **无改动**）。

🔴 因此 @后端 现状（运行时 30060 只有 code + message）✅ **正确，无需返工**；
   本裁决把它从"未定义的临时口径"升格为**正式契约**，并禁止未来"补齐 violations"的反向改动。
```

**错误码**：`10001`（`objectType` 非法）、`10003`（角色不足 / 成员禁用）、`10004`（对象不存在或跨租户）、`20001~20005`、`30010/30011`、`30060`（校验失败）、`50003`

---

### 7.4 M2-min：MCP 治理（无 UI，一期由 DBA 写库 + 本节接口实测）

#### 7.4.1 MCP 配置只读查询（凭据口径）

**GET** `/api/v1/admin/mcp/{mcpId}`
**权限**：`@Permission(PermissionEnum.USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})`
**租户上下文**：必需

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "mcpId": "12",
    "mcpKey": "crm",
    "name": "CRM 工具服务",
    "transport": "streamable_http",
    "endpoint": "https://mcp.example.com/v1",
    "authType": "bearer",
    "credential": { "configured": true, "last4": "9f2c", "keyVersion": 1, "updatedAt": "2026-08-13T02:10:00.000Z" },
    "timeoutSeconds": 30,
    "status": "enabled",
    "lastCheckStatus": "healthy",
    "lastCheckResult": "success",
    "lastCheckedAt": "2026-08-13T02:12:00.000Z",
    "allowedToolKeys": ["crm:lookup_user"],
    "version": 3
  },
  "timestamp": 1704067200000
}
```

**🔴 凭据查询口径（AC-MCP-001，不可协商）**：

```
1. 只返回 credential.configured（boolean）+ credential.last4（string）+ keyVersion + updatedAt
2. 🔴 永不返回：明文、完整密文、密文片段、IV/Nonce/Tag、加密密钥、解密结果
3. last4 = 明文末 4 位，由离线加密工具在生成密文时一并输出并写入 credential_last4 列；
   🔴 不得由密文推导，也不得在服务端解密以获取（服务端只在运行时调用前解密，且不落任何日志）
4. 明文长度 < 8 时 last4 一律返回 "****"（防止短凭据被 last4 反推）
5. last4 仅供人工核对，🔴 禁止用于任何比较、校验或鉴权逻辑
6. authType=none 时 credential = { "configured": false, "last4": "", "keyVersion": 0, "updatedAt": null }
7. 🔴 keyVersion 一期**恒为 1**（V1.1.1 订正：V1.1 示例中的 2 是误导值）——
   application.yml 白名单只允许单密钥 app.crypto.secret，一期**没有密钥轮换能力**；
   密文头保留版本前缀是为二期"多密钥解密 + 单密钥加密"预留，一期读到 keyVersion != 1
   即视为非法密文 → 30060（与 architecture.md ADR-012 一致）
8. 凭据变更（改库后调用 §7.4.2 首次生效）必须写审计 action=mcp.credential_changed，
   beforeDigest/afterDigest 只记"是否变化"（PRD §15.1）
```

**🔴 凭据密文契约（V1.1.1 回写，与 `architecture.md` ADR-012 一致；@后端 与 DBA 必须逐字遵守）**：

| 项 | 契约 |
|---|---|
| 算法 | `AES-256-GCM`，密钥取 `application.yml` 的 `app.crypto.secret`；IV 12 字节随机、Tag 128 位 → 🔴 相同明文每次加密结果都不同（AC-MCP-007） |
| **密文编码格式** | 🔴 单一字符串列 `mcp_servers.credential_cipher`，格式固定为 **`v{keyVersion}:{base64url(iv)}:{base64url(ciphertext‖tag)}`**（三段，冒号分隔）；不符该格式 → `30060` `rule=invalidCipher`。一期 `keyVersion` 恒为 `1`，即密文必以 `v1:` 开头 |
| **AAD 绑定** | 🔴 附加认证数据 **AAD = `"mcp:{tenantId}:{mcpKey}"`**。关键设计：把密文与"哪个租户的哪个 MCP"绑定 —— 把密文从 A 租户复制到 B 租户（或改了 `mcp_key`）会**解密失败**，从加密层杜绝「密文跨租户搬运」式越权（DBA 误操作或恶意复制） |
| AAD 副作用 | 🔴 `tenant_id` 或 `mcp_key` 变更后**必须重新加密写入**，否则解密失败表现为 `30060`（校验入口）/ `30052`（运行时调用）—— 已写入运维手册 |
| 密文产出 | 🔴 只能由**离线加密工具**（位于 `backend/src/test`，绝不进生产 JAR、绝不提供解密回显）产出，由 DBA 写库；🔴 **禁止**提供任何"提交明文换密文"的 HTTP 端点 |
| 解密时机 | 🔴 只在**运行时调用前**的内存中解密；解密结果不入日志、不入审计、不入异常消息、**不进任何缓存**（L1/L2 均禁入，见 `architecture.md` §12.1.1） |

**错误码**：`10003`、`10004`（跨租户/不存在）、`20001~20005`、`30010/30011`、`50003`

#### 7.4.2 连接测试（分类结果）

**POST** `/api/v1/admin/mcp/{mcpId}/test`
**权限**：`@Permission(PermissionEnum.USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})`
**请求体**：无（配置取自 `mcp_servers` 当前行）
**行为**：先执行 HTTPS/SSRF 校验 → 建连（口径见 §7.6.1「超时口径」）→ **握手判定**（见下方 🔴 G9 裁决）→ `tools/list`（`mcp.discover_timeout_seconds`）→ **强制写审计** `action=mcp.connection_test`

> 🔴 **V1.2.1 订正（超时预算键，取代 V1.2.0 的两键并列表述）**：本接口**整次** exchange 的 deadline 预算 = **`mcp.discover_timeout_seconds`**（唯一取值）。
> 理由：本接口在实现上**就是执行一次 `tools/list`**，不存在"独立握手请求"这一次网络往返；若取 `connect` 预算，会出现"同一次 `tools/list` 在 `/test` 与 `/discover` 两个入口预算不同"的自相矛盾。
> 👉 `mcp.connect_timeout_seconds` 的语义**收窄**为：① §7.6.1 G7 运维不等式（`app.ai.connect-timeout-seconds ≤ 它`）的参照值；② 📋 二期"独立 `initialize` 握手阶段"的预算键。🔴 该键**仍是** `REQUIRED_CONFIG`（缺键即启动失败），不因当前无代码消费点而下线。

> 🔴 **裁决（G9）一期不维护 MCP 会话，握手以 `tools/list` 的成败代表**：
> 一期**不发送** `initialize`、**不维护** `sessionId` / `Mcp-Session-Id`（无会话状态 = 无会话过期、无重连语义，与"单体 + 同步调用 + 不新增线程"的模型一致）。
> 因此本接口的"协议握手成功"**定义为** `tools/list` 返回合法 JSON-RPC 2.0 且含 `result.tools`。
> 🔴 若上游**强制要求**先 `initialize`（或强制携带会话头）才允许 `tools/list`，一律判 `protocol_incompatible`（运行时调用则为 `30052`）—— 该服务在一期**不受支持**，@后端 **不得**为兼容它而私自引入会话状态；如确有此类上游，走 🔄 变更请求由 @架构师 裁决是否纳入二期。
>
> 🔴 **V1.2.0 订正（G9′ / G6′，见 §7.6.2 / §7.6.1）**：上述口径**仅对 `streamable_http` 继续逐字有效**。
> 对 `sse`：客户端**会**在单次 exchange 内发送 `initialize`（兼作形态探测），因此"上游强制要求 `initialize`"**不再**构成 `protocol_incompatible`；
> `sse` 的"握手成功"= **形态判定成功 + `tools/list` 返回合法 JSON-RPC 2.0 且含 `result.tools`**（异步形态还额外要求 `initialize` 的 `result` 成功）。
> 🔴 `data.result` 的 9 个字面量**零扩充**，`mcp_servers.last_check_result` **零 DDL 变更**（新失败模式全部落到既有字面量，映射表见 `architecture.md` ADR-016 失败分类对照表）。
> 🔴 `sse` 的连接测试同样受 **deadline 预算制**约束：整次 exchange（GET 建流 + 最多 3 次 POST + 等待）总耗时 ≤ **`mcp.discover_timeout_seconds`**（🔴 V1.2.1 订正，原文并列 `connect` / `discover` 两键已作废），🔴 **不是每个子请求各取一份**。

**分类结果枚举（`data.result`，字面量固定，@测试 据此断言）**：

| 字面量 | 含义 | `lastCheckStatus` |
|---|---|---|
| `success` | 建连 + 协议握手 + `tools/list` 成功且工具数 ≥1 | `healthy` |
| `dns_failed` | 域名无法解析 | `unhealthy` |
| `tls_failed` | TLS 握手/证书校验失败 | `unhealthy` |
| `auth_failed` | 鉴权被拒（HTTP 401/403/**407** 或 JSON-RPC 鉴权错误）；🔴 **V1.2.1**：`sse` 的 **GET 建流**返回 `401`/`403`/`407` 时**直接判本项**，🔴 **不得**退化为直接 POST（否则鉴权失败被掩盖成 `protocol_incompatible` 并丢失该诊断线索） | `unhealthy` |
| **`connect_failed`** | 🔴 **V1.1.2 新增（G2 裁决）**：TCP 连接未能建立 —— 连接被拒（`ConnectException` / RST）、网络不可达（`NoRouteToHostException`）、连接被重置。**与 `timeout` 的判别口径**：`connect_failed` = 上游**明确拒绝或不可达**（立即失败）；`timeout` = **无响应直至超时**（静默丢包 / 上游卡死） | `unhealthy` |
| `timeout` | 建连或握手**超时**（无响应达超时阈值）；🔴 **V1.2.1 补注**：`sse` 异步形态下，GET 事件流被 `HttpRequest.timeout(remaining)` 打断（`HttpTimeoutException`）→ 🔴 判**本项**（`timeout` / `30051`），**不得**判 `protocol_incompatible` —— 否则"上游一直不回"会被误诊为"协议不兼容"，违反下方 G2 判别口径 | `unhealthy` |
| `protocol_incompatible` | 非 JSON-RPC 2.0 / 缺 `tools` 能力 / 版本不兼容 / 🔴 强制要求 `initialize` 或会话头（G9，🔴 **仅对 `streamable_http`**；`sse` 见 G9′）/ 🔴 `sse` 的下列情形（**V1.2.0 G6′ 起**）：会话端点跨源、`mcp.sse_legacy_enabled=false` 时 POST 只回 `202` 空体、异步形态 `initialize` 返回 `error`、GET 流在给出结果前被上游关闭、流累计字节超 `mcp.sse_stream_max_bytes` | `unhealthy` |
| `no_tools_available` | 握手成功但 `tools/list` 返回空 | `unhealthy` |
| `ssrf_rejected` | HTTPS/SSRF 校验拒绝（🔴 **不发起任何网络连接**） | `unhealthy` |

> 🔴 **裁决（G2）为什么必须新增字面量而不是归入 `timeout`（修正 @后端 临时口径）**：
> ① `ConnectException`（连接被拒）在生产排障中最常见，语义是"端口没开 / 安全组拦了 / 服务没起"，**排障动作与超时完全不同**（超时要查网络与上游负载）；把两者混为 `timeout` 会把 DBA 导向错误的排查方向，直接削弱本接口存在的价值（它是**诊断能力**）。
> ② 代价可控：`mcp_servers.last_check_result` 为 `VARCHAR(32)`，`connect_failed`（14 字符）**无需改表**；错误码不变（仍 `code=0` + `data.result`，运行时映射仍 `30052`）。

**成功响应**（含"测试出诊断结论"的情形，`code=0`）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "mcpId": "12",
    "transport": "streamable_http",
    "result": "auth_failed",
    "healthy": false,
    "latencyMs": 412,
    "toolCount": 0,
    "checkedAt": "2026-08-13T02:12:00.000Z",
    "auditEventId": "7f1c9a30d8b24f069c5e1a7382bd40fa"
  },
  "timestamp": 1704067200000
}
```

> 🔴 **口径裁决**：连接测试是**诊断能力**，`dns_failed`/`tls_failed`/`auth_failed`/**`connect_failed`**/`timeout`/`protocol_incompatible`/`no_tools_available` 均以 `code=0` + `data.result` 承载（接口本身执行成功）。
> **唯一例外**：`ssrf_rejected` 必须返回 `code=30050`（EX-029 强制"连接测试也拒绝"），此时 `data.result="ssrf_rejected"` 同时给出，且**不得**回显解析出的 IP/内网信息。
> 🔴 `data` 中禁止出现凭据、完整响应体、上游返回的错误正文（只做分类）。

**错误码**：`10003`、`10004`、`20001~20005`、`30010/30011`、`30050`（SSRF 拒绝）、`30060`（配置非法，如 `transport=stdio`、凭据密文非法）、`50003`（审计写入失败 → 整体失败，EX-024）

#### 7.4.3 工具发现

**POST** `/api/v1/admin/mcp/{mcpId}/discover`
**权限**：同 §7.4.2
**行为**：SSRF 校验 → `tools/list` → 与 `mcp_tools` 现有行比对 → 落库

> 🔴 **V1.2.0 补注（G6′ ⑤）超时预算口径**：`tools/list` 的 `mcp.discover_timeout_seconds` 是**单次 exchange 的总预算（deadline）**，🔴 **不是每个子请求各取一份** —— `sse` 传输一次 exchange 内可能包含 GET 建流 + 最多 3 次 POST + 流上等待，各步骤取 `remaining()`，累加不得超预算；`remaining ≤ 0` → `timeout`（§7.4.2）/ `30051`（运行时）。
> 🔴 翻页场景（`nextCursor` 非空）同样受**同一个总预算**约束：🔴 **不得**每页重置预算（否则 `MAX_PAGES=50` 会把 15s 预算放大到 12.5 分钟）。

| 字段（Body） | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `dryRun` | boolean | 否 | 默认 `false` | `true` 时只返回比对结果不落库 |

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "mcpId": "12",
    "discoveredAt": "2026-08-13T02:14:00.000Z",
    "newCount": 1,
    "unchangedCount": 2,
    "schemaChangedCount": 1,
    "removedCount": 0,
    "tools": [
      { "toolKey": "crm:create_ticket", "name": "create_ticket", "description": "创建工单", "inputSchemaDigest": "sha256:8c1f…", "riskLevel": "medium", "granted": false, "status": "disabled", "changeType": "new" },
      { "toolKey": "crm:lookup_user", "name": "lookup_user", "description": "查询用户", "inputSchemaDigest": "sha256:2ab7…", "riskLevel": "low", "granted": true, "status": "enabled", "changeType": "unchanged" },
      { "toolKey": "crm:export_all", "name": "export_all", "description": "导出数据", "inputSchemaDigest": "sha256:44de…", "riskLevel": "high", "granted": false, "status": "disabled", "changeType": "schema_changed" }
    ]
  },
  "timestamp": 1704067200000
}
```

**🔴 发现规则（AC-MCP-002 / AC-MCP-005）**：

```
1. changeType 枚举：new / unchanged / schema_changed / removed
2. 🔴 new 一律 granted=false + status=disabled（新发现工具默认禁用，不进模型可调用清单）
3. 🔴 schema_changed 的「已授权」工具自动降级为 granted=false + status=disabled，并写审计
   （防止"先以无害 Schema 拿到授权，再偷换参数"的提权路径）
   🔴 审计 action = mcp.tool_grant_revoked（V1.1.2 新增，G3 裁决），reason='schemaChanged'
4. removed 保留历史行并置 status=disabled，不物理删除（保住 tool_calls 的可追溯外键语义）
   🔴 若被移除的工具原本 granted=1，同样写 mcp.tool_grant_revoked，reason='toolRemoved'
5. 工具数超过 mcp.max_tools_per_server → 30060，且不落库（防发现结果撑爆清单）
6. riskLevel 由平台侧规则给出默认值（🔴 未知/未能分类一律 high），租户不可自行下调
```

**🔴 裁决（G3）系统撤销授权必须用独立 audit action，不得复用 `tool.grant_denied`**：

| 项 | `tool.grant_denied` | **`mcp.tool_grant_revoked`（新增）** |
|---|---|---|
| 语义 | **模型请求调用**一个不可用工具 → 拒绝本次执行 | **系统主动撤销一条授权**（发现阶段，无任何工具调用发生） |
| `actorType` | `endUser` | 🔴 `system` |
| `objectType` / `objectId` | `toolCall` / `tool_calls.id` | 🔴 `mcpTool` / `mcp_tools.id` |
| `result` | `denied` | 🔴 `success`（撤销动作本身执行成功） |
| `beforeDigest` / `afterDigest` | 摘要（§5.4.3） | 🔴 新旧 `inputSchemaDigest`（`removed` 时 after 记 `"removed"`） |
| `reason` | 拒绝原因 | 🔴 固定枚举 `schemaChanged` \| `toolRemoved` |
| 事务边界 | 流式内独立短事务 | 🔴 非流式**同事务**（随 `discover` 落库；审计失败 → 整批回滚 + `50003`，EX-024） |

> 复用会造成两个后果：① 按 `action` 检索安全事件时，"用户越权尝试"被"系统例行降级"淹没；② `objectId` 语义分叉（时而 `toolCall` 时而 `mcpTool`），破坏 `architecture.md` §11.1.2 的 `idx_object` 追溯路径。完整枚举见 §7.14 与 `architecture.md` §11.1.1。

**🔴 裁决（G5-b）`riskLevel` 的授权期写入口径（与高风险确认的生产可达性直接相关）**：

```
① 落库默认值：发现阶段写入平台默认等级；🔴 未知/未能分类一律 high（fail-safe，本条不变）
② 🔴 授权时可显式指定 risk_level（low | medium | high）——
   一期授权动作由**平台方 DBA 写库**执行（DEC-010），租户**没有任何写入通道**，
   因此这与「租户不可自行下调」并不冲突；无需新增任何字段
   （mcp_tools.risk_level 已存在：VARCHAR(16) DEFAULT 'high'）
③ 🔴 默认值**不改为 medium**（架构师明确反对，理由见 architecture.md ADR-015 复核表）：
   MCP 是**外部不可信服务**，其副作用平台无法预知；默认 high 的最坏后果是"多问用户一次"，
   默认 medium 的最坏后果是"未经确认执行了不可撤销的外部操作" —— fail-safe 方向不可调转
④ 因此 awaiting_confirmation（§7.8）在**生产链路上真实可达**：
   tool_policy=auto 时 high 工具必确认；tool_policy=confirm 时 medium/high 均需确认（§7.7.3），
   🔴 确认流程不再是"只存在于测试"的路径
⑤ 二期引入授权 UI 后：「下调 risk_level」必须限定为平台管理员权限并强制写审计
   （建议 action mcp.tool_risk_changed，🔴 二期登记后方可实现）；租户管理员只能上调
```

**错误码**：`10001`、`10003`、`10004`、`20001~20005`、`30010/30011`、`30050`（SSRF）、`30052`（连接/协议/鉴权失败）、`30060`（配置非法 / 工具数超限）、`50003`

#### 7.4.4 工具逐项授权（一期为**数据契约**，无 HTTP 接口）

一期授权由 DBA/开发人员直接写库（DEC-010），运行时按下列数据契约消费；授权 UI 与 `PUT …/grant` 接口 Deferred（§6）。

| 表 | 关键列 | 运行时语义 |
|---|---|---|
| `mcp_tools` | `tenant_id`、`mcp_id`、`tool_key`、`input_schema`、`input_schema_digest`、`risk_level`、`granted`、`status`、`granted_by`、`granted_at` | 🔴 仅 `granted=1 AND status='enabled'` 且所属 `mcp_servers.status='enabled'` 的工具进入模型可调用清单 |
| `agent_capability_bindings` | `tenant_id`、`agent_version_id`、`capability_type`（`skill`\|`mcpTool`\|`localTool`）、`ref_id`、`ref_version`、`variable_values`、`sort_order` | 🔴 未被当前会话 `agentVersion` 绑定的工具**一律不进清单**（AC-MCP-005） |

**🔴 裁决（G1）`agent_capability_bindings` 的 `ref_id` / `ref_version` 三类语义（此前未定义，导致绑定链路无法实现）**：

| `capability_type` | `ref_id` 指向 | `ref_version` 语义 | 运行时是否参与解析 |
|---|---|---|---|
| `skill` | `skills.id` | **精确版本** = `skill_versions.version` | ✅ **参与**：按 `(tenant_id, skill_id, ref_version)` 取那一个不可变版本；禁止"最新"语义（AC-SKL-002）；目标版本缺失 / 非 `published` / 跨租户 → `30060` |
| `mcpTool` | `mcp_tools.id` | 发现批次号（审计对照） | ❌ **不参与**：授权 / Schema / 风险等级一律读 `mcp_tools` **当前行**（AC-MCP-004 改库即生效）；与当前批次号不一致**不构成错误** |
| `localTool` | 🔴 `tenant_tool_grants.id`（**不是** `local_tools.id`） | 绑定时 `local_tools.version` 的**审计快照** | ❌ **不参与**：先读 `tenant_tool_grants` 当前行，再按 `tool_key` 读 `local_tools` 当前行（§7.7.1 版本语义裁定 ②③） |

> 🔴 **该表是 M2-min 新表**，此前只出现在 `architecture.md` §13.4 ER 图与 §13.5.9（被误列为"既有表"），从未进入 §13.3 DDL —— 属登记漏项。现已正式登记为 **`architecture.md` §13.5.10**（含 as-built DDL、唯一键 `uk_tenant_binding`、索引 `idx_tenant_version_sort`），@后端 已实执行的表结构与之**逐字一致，无需改表**。
> 🔴 **运维纪律（写入 README）**：`localTool` 绑定指向 grant 行主键，`tenant_tool_grants` 行被 `DELETE` 后重建会得到新 `id` → 既有绑定悬挂 → 该工具**不进清单**（fail-closed，不报错也不越权）。调整授权一律用 **`UPDATE granted/status`，🔴 禁止 `DELETE + INSERT`**。

> 🔴 未授权 / 未绑定 / 已停用的工具被模型请求调用 → 拒绝执行，`tool.status=denied`、`errorCode=30050`，**并写安全审计** `action=tool.grant_denied`（AC-AUD-003）。模型侧不得看到该工具定义（清单级隔离），运行时校验是**第二道**兜底。

---

### 7.5 M3：Skill 运行时消费契约

> 🔴 **一期无对外 HTTP 接口**：Skill 的增删改查/发布 UI Deferred（§6）；一期能力 = **运行时消费** + **校验入口**（§7.3.1，`objectType=skill`/`skillVersion`）。本节定义数据契约与运行时规则，@后端 据此实现 `ContextAssembler` 衔接。

#### 7.5.1 数据契约

> 🔴 **表结构裁定（V1.1.1 回写，与 `architecture.md` §13.5.2 一致）**：Skill 拆为 **`skills` 主体表 + `skill_versions` 版本表**两张表。
> **key 唯一性归属主体表**（`uk_tenant_skill_key(tenant_id, skill_key)`），**版本唯一性归属版本表**（`uk_tenant_skill_version(tenant_id, skill_id, version)`）。
> V1.1 曾把 `uk_tenant_skill_key` 标在 `skill_versions` 上，与「同一 Skill 多版本递增」「发布后行不可变」自相矛盾，且未登记 `skills` 主体表 —— 本版订正，**@后端 以下表为 DDL 基线**。

| 表 / 列 | 类型 | 约束 |
|---|---|---|
| `skills.skill_key` | VARCHAR(64) | 🔴 `uk_tenant_skill_key(tenant_id, skill_key)` —— **唯一键在主体表**，保证 AC-TEN-003「两租户可用相同 key」 |
| `skills.name` / `description` / `status` / `current_version` / `version` | VARCHAR / ENUM / INT | `status` ∈ `enabled` \| `disabled`；`current_version` = 当前已发布版本号（`0` 表示无可用版本）；`version` 为乐观锁列 |
| `skill_versions.skill_id` | BIGINT | 指向 `skills.id`（🔴 必须同租户，跨租户引用 → `30060`） |
| `skill_versions.version` | INT | 🔴 `uk_tenant_skill_version(tenant_id, skill_id, version)`；租户内**单 Skill 递增**；🔴 `status='published'` 后**行不可变**（`instruction`/`variables_schema`/`output_constraint`/`version` 均 `updatable=false`，仅允许 `published → archived`） |
| `skill_versions.instruction` | TEXT | 1 ~ `sys_config: skill.instruction_max_chars`（50000），超限 → `30060` |
| `skill_versions.variables_schema` | JSON | 数组，元素 `{ "name", "required", "description", "defaultValue" }`；数量 ≤ `skill.max_variables` |
| `skill_versions.output_constraint` | TEXT | 0~5000 |
| `skill_versions.status` | ENUM | `draft` \| `published` \| `archived` |
| `agent_capability_bindings` | — | `capability_type='skill'`，`ref_id=skill_id`，`ref_version=skill_versions.version`（🔴 **精确版本引用，不得引用"最新"**，AC-SKL-002） |
| `agent_capability_bindings.variable_values` | JSON | KV，DBA 写入的变量取值 |

#### 7.5.2 `ContextAssembler` 注入衔接点

```
🔴 V1.2.4 全局结构不变量（ADR-019，🔴 优先于本节其它一切表述）：
   发往上游的 messages 中，**role=system 至多 1 条，且必须位于 index 0**。
   👉 等价表述："system 只能是整个列表的第一条"。
   ⚠️ 这不是风格偏好：真实上游（混元 OpenAI 兼容接口）对此有硬约束，
      违反 → status=400「messages 中 system 角色必须位于列表的最开始」
      → 本次生成在**进入工具调用之前**就以 error(50002) + done(failed) 收敛
      （test-report V4.1 BUG-MCP-004，影响**所有**工具含 calculator）。

注入位置：🔴 **唯一那条 system 消息的内部**，块顺序固定为
  ① 租户段
     ⓐ agent_versions.system_prompt
     ⓑ 已绑定 Skill 版本的 instruction（按 binding.sort_order ASC, ref_id ASC 追加）
     ⓒ 已绑定 Skill 版本的 output_constraint（同序追加于 ① 的末尾）
  ② 历史摘要块（若本轮有更早内容且摘要已缓存）
     🔴 V1.2.4 订正：摘要此前以**另一条 system 消息**注入 → 违反上述不变量
     （长会话摘要 TTL 内该会话**每轮必 400**，不可自愈）→ 改为**同一条 system 内的块**。
  ③ 平台纪律段（仅当本次生成确实下发了 tools）
     = sys_config: chat.tool_usage_guideline，🔴 **恒为最后一块**（口径见下方「⑥ 裁决」框）
然后才是最近消息窗口（user/assistant），再是本轮工具链（assistant(tool_calls) + tool）。

🔴 三块的长度预算**互不合并**（各自不变，见 §7.5.4）：
   ① → chat.system_prompt_max_chars（超限 30060，🔴 禁截断）
   ② → chat.context_summary_max_chars（确定性生成，天然有界）
   ③ → 🔴 **不设预算、不计入任何预算**（平台段；长度纪律由 §7.1.2 文案纪律 ② + AR-021 承担）

🔴 硬约束：
1. 只注入会话绑定 agentVersion 所引用的那个 Skill 版本（快照语义），
   新增 Skill 版本不改变旧会话行为（AC-SKL-002）
2. Skill 停用（status 变更）不影响已绑定的历史 Agent 版本注入 —— 快照不可变；
   但 🔴 Skill 正文中提及的任何工具，其可用性一律由 §7.4.4 / §7.7.2 授权矩阵决定，
   指令文本永远不能提权（"历史静态指令不恢复外部工具权限"，PRD §8.5）
3. 🔴 注入后 **①（租户段）** 的总字符数超出 sys_config: chat.system_prompt_max_chars →
   30060 rule=systemPromptBudgetExceeded（🔴 fail-closed，禁止静默截断系统提示）
   —— 🔴 V1.2.4 明确：判定对象**恒为 ①**，与最终 system 消息的**物理长度无关**；
   完整口径见下方「① 裁决」框
4. 🔴 Skill 正文、systemPrompt 属内部资产：禁止出现在任何对外响应、SSE 事件、
   埋点、审计与日志中；审计/埋点只记 skillVersionId 与 sha256 前 16 位 digest
5. 🔴 V1.2.4 新增：块之间的分隔沿用既有 SystemPromptBudget.SECTION_SEPARATOR，
   🔴 禁止为"标注这段是谁说的"另造可见分隔文案（如「【平台纪律】」）——
   那属**文案**（须入库）且把平台内部结构喂给模型零收益。
   来源可区分性由两条机制承担：ⓐ 契约「③ 恒为末块且逐字等于 sys_config 文案（替换后）」；
   ⓑ 服务端日志分列记录三块的码点数（🔴 只记长度不记正文）。
```

**🔴 裁决（⑥，V1.2.2 新增；🔴 ②③ 于 V1.2.4 重写）平台级工具调用纪律段（`architecture.md` ADR-018 ② + **ADR-019**；🔴 与 ① 的预算严格分离）**

```
背景（实测，test-report V4.0 BUG-MCP-001）：用户以自然语言提问时，模型会"热心补全"外部工具的
可选参数（实测为 Mode / FromTime / ToTime），且凭训练期知识把"最近"推算成 2024 年时间戳
（当前 2026 年）→ 外部返回参数非法 → 反复重试并反复要求用户确认。

🔴 裁决：以「平台级、与具体工具无关」的纪律段做引导，🔴 **不得**改写上游 schema（ADR-018 ① 已否决）。

① 注入条件：🔴 **仅当本次生成确实下发了 tools**（工具清单非空）时注入。
   无工具的生成（含 tool_policy=disabled）🔴 零影响、不注入、不占用任何预算。
② 注入形态（🔴 **V1.2.4 重写，原文作废**）：
   ❌ 作废：「**独立的第二条 system 消息**（不与租户段拼接）」
      —— 被真实上游否证（status=400「system 角色必须位于列表的最开始」，BUG-MCP-004），
         该形态使**所有**含工具的生成在进入工具调用前即失败（实测含 calculator）。
   ✅ 现口径：纪律段**合并进唯一的 system 消息**，🔴 **恒为最后一块**
      （块顺序 = 租户段 → 历史摘要块 → 纪律段；分隔沿用 SystemPromptBudget.SECTION_SEPARATOR）。
   🔴 为何是**末块**：ⓐ 平台纪律不得被租户 systemPrompt 覆盖 —— 后置在指令冲突中更占优势，
      且租户无法在它之后再追加内容（fail-safe 方向）；ⓑ 租户段仍是**前缀**，既有行为零漂移；
      ⓒ 固定后缀可被机械断言（endsWith），验收不依赖人工阅读。
   🔴 原文"必须独立"的两条理由如何被替代：ⓐ「会计入 ① 的预算」→ **不成立**（见 ③：
      预算判定只吃租户段，物理合并 ≠ 预算合并）；ⓑ「排障无法区分谁说的」→ 由
      「纪律段恒为末块且逐字等于 sys_config 文案」+「日志分列三块码点数」替代（§7.5.2 硬约束 5）。
③ 🔴 **不计入 chat.system_prompt_max_chars**（🔴 V1.2.4：本条**原样保留**，未因合并而放宽）：
   ① 的判定对象按本节定义恒为
   「system_prompt + Skill instruction + output_constraint」= **租户配置**，平台段不在其列。
   🔴 若计入，本次变更会把**既有满配租户**直接打成 30060 —— 属不可接受的连带破坏。
   🔴 V1.2.4 明确三条口径（@测试 / @后端 逐条为验收项）：
      ⓐ 30060 校验**不含**纪律段长度，**也不含**其分隔符；
      ⓑ 🔴 最终 system 消息的**物理长度可以超过** system_prompt_max_chars ——
         这是**有意为之**，🔴 不得判为缺陷，🔴 也不得因此截断纪律段（截断 = 静默降级，见 ⑤ 框）；
      ⓒ 🔴 反向不放宽：**租户段自身**超限仍 30060、仍禁截断
         （"不计入" ≠ "预算判定被放宽"）。
   🔴 实现纪律：纪律段**绝不经过** SystemPromptBudget（连 acceptSeparator 也不调用），
      必须在租户段预算判定**完成之后**才拼接 —— 顺序倒过来就等于计入。
④ 占位符：🔴 仅支持 {{currentTime}}（服务器当前时间 ISO-8601）。
   它直接消灭"模型按训练期知识推算时间戳"这一实测失败模式；无占位符则不注入时间。
   ⚠️ 副作用（@测试 注意）：system 段因此含运行时值 → 🔴 不得据"system 段逐字稳定"做断言。
   🔴 V1.2.4 补注（合并后逐条仍成立）：替换发生在**每次**上下文装配内；
      🔴 同一次生成内 system 只构建**一次**、各轮共用 → currentTime = **本次生成开始时刻**
      （🔴 不得断言"逐轮刷新"）；🔴 严禁把"合并后的 system 文本"写入任何缓存
      （会同时造成时间冻结与跨租户串味）；配置侧缓存的是**含占位符的模板**，不受影响。
⑤ 🔴 措辞纪律：必须与具体工具无关（不得出现任何上游字段名/工具名），
   否则等于把 ADR-018 ① 否决的"替上游猜语义"从 schema 挪到 prompt 里换个地方做。
⑥ 🔴 语义边界：它是**引导**不是**保证**。模型仍可能补可选参数 ——
   因此 🔴 不得以"纪律里写了"为由削弱 Schema 校验（§7.6.3 ④）、风险确认（§7.8）或审计（§7.14）；
   也 🔴 不得据此要求"模型必然一次成功"（验收口径见 §8.3 J 组与 ADR-018 ⑤）。
   🔴 V1.2.4 附带结论：正因为"任何闸门都不依赖模型是否遵守纪律"，
      纪律段与租户段同处一条消息**不构成安全问题**（最坏后果 = 回到 BUG-MCP-001 的
      概率性体验问题）→ 已登记 `architecture.md` **AR-023**。
⑦ 纪律段正文与失败诊断回灌（§7.6.4 V1.2.2 裁决框）是**互补的两件事**：
   前者降低"一开始就传错"的概率，后者保证"传错之后能自纠"。🔴 缺任一条本 P1 都不算修完。
```

**🔴 裁决（①）system 消息总长上限：本期实现，口径 = 字符预算 + fail-closed（🔴 订正 V1.1 的契约错误）**
```
🔴 先订正一处**契约错误**（V1.1 措辞不可实现，本版作废）：
   原文写「超出 agent_versions.max_output_tokens 对应的上下文预算」——
   max_output_tokens 是**输出** token 上限（DDL 默认 4096，architecture.md §13.3），
   与**输入**上下文窗口没有任何换算关系。按原文实现会得出"system 提示不得超过 4096 token"
   这种荒谬约束。👉 本版起 max_output_tokens **不再**作为该判定的输入。

裁决：本期实现（不改判二期），但按下述口径，**不新造 token 换算**：
① 判定对象 = ContextAssembler 拼出的 **租户段整体**：
   agent_versions.system_prompt + 全部绑定 Skill 的 instruction + output_constraint
   （🔴 按**变量替换后**的实际长度计，否则可用长变量值绕过）
   🔴 **V1.2.4 订正措辞（口径未放宽）**：原文写"system 消息**整体**"，在 V1.2.2 语境下
   （平台段是独立的第二条消息）与"租户段整体"等价；V1.2.4 起纪律段与摘要块**物理合并**
   进同一条 system 消息，故必须精确表述为 🔴 **判定对象恒为租户段** ——
   与最终 system 消息的**物理长度无关**（历史摘要块按 chat.context_summary_max_chars、
   平台纪律段不设预算，均**不计入**本判定，见 ⑥ 裁决 ③）。
② 度量口径 = 🔴 **字符（Unicode 码点）数**，实现用 String.codePointCount
   （不用 UTF-16 length —— 否则 emoji / 生僻字被算两次，同一份文本在不同租户表现不一致）
③ 阈值 = sys_config: chat.system_prompt_max_chars（默认 100000），🔴 代码中禁止出现该数字
④ 超限行为 = 🔴 **整体失败** 30060 + rule=systemPromptBudgetExceeded，
   在**进入模型之前**失败（AC-CFG-004）；violations[] 给出 objectType=agentVersion +
   field=systemPrompt，🔴 message 中只回"已超出系统提示长度上限"，禁回正文片段与实际字符数以外的内容
⑤ 🔴 **明确否决**「只截断最低优先级片段」：
   Skill 注入顺序（systemPrompt → instruction → output_constraint）本身就是**语义依赖链**，
   截掉尾部 output_constraint 会让模型以"没有输出约束"的形态运行 —— 那是**静默降级**，
   排障时表现为"AI 偶尔不守格式"却查不出原因，且与本节硬约束 3「禁止静默截断系统提示」
   直接冲突。配置超限是**配置问题**，必须让 DBA 看到，不能由运行时替它做减法。

🔴 为什么用字符近似而不是真实 tokenizer（一次性说清，@后端 不得另立方案）：
   ① 上游是混元（OpenAI 兼容接口），🔴 **无官方本地 tokenizer**；引入 jtokkit 等第三方
      属**新增依赖**（本期硬约束禁止），且它编码的是 OpenAI 词表 —— 对混元是"精确的错"，
      不如"近似的对"；
   ② 本键的职责**不是**精确匹配模型上下文窗口，而是消灭"多 Skill 叠加后总长无上限"这一
      fail-open 缺口（单 Skill 已被 skill.instruction_max_chars 约束，叠加后此前无任何上限）；
      模型侧真超限仍由上游报错兜底（→ 50002），两道防线不重复、不冲突。
   ③ 📋 二期若接入具备本地 tokenizer 的模型，可把本键升级为 token 预算：
      届时须先在本表登记新键（如 chat.system_prompt_max_tokens）并回写本框，🔴 禁止就地改语义。

🔴 为什么不影响首字性能（与 §5.4.2 不矛盾）：
   ① 判定是**一次 O(n) 的字符累加**，无分词、无正则回溯、无 IO、无新增查询；
      🔴 实现必须在拼装时**边拼边累加**并在超限处**立即短路返回**，禁止"先拼完再遍历一遍"；
   ② 该逻辑发生在**异步段**（ContextAssembler 在 aiStreamExecutor 内执行，早于首个可见帧
      但晚于 meta 的 flush），按 §5.4.2 / architecture.md §9.5.3 口径**不占首字预算**；
   ③ 与"系统提示不得被裁剪"不矛盾：本条**从不裁剪**，只做"通过 / 30060"二元判定。

校验入口联动（§7.3.1）：objectType=agentVersion 且 includeReferences=true 时，
🔴 必须用**同一实现**累加同一预算并以 violations[] 提前暴露（属 G10 递归校验的一部分），
使 DBA 在用户对话之前就能发现"绑定太多 Skill"，而不是等到线上 30060。
```

#### 7.5.3 `{{variable}}` 声明与替换规则

```
1. 占位符语法：{{name}}，name 匹配 ^[a-zA-Z][a-zA-Z0-9_]{0,63}$
   不匹配该规则的 {{…}} 原样保留（不解释、不报错），避免与 Markdown/代码块冲突
2. 🔴 取值优先级（V1.1.2 重写，G4 裁决 —— 消除"绑定优先"与"内置只读"的自相矛盾）：
   ⓿【最高，不可被覆盖】平台内置只读变量：tenantId / locale / timezone / nowIso
      —— 它们构成**独立命名空间**，恒由平台注入，不参与下面的优先级比较
   ① agent_capability_bindings.variable_values[name]（🔴 仅对**非保留名**生效）
   ② variables_schema[].defaultValue
   🔴 内置变量为保留名：
      - Skill 的 variables_schema 声明同名变量 → 30060 rule=reservedVariable（不变）
      - 🔴 variable_values 中出现同名键 → **一律忽略该键**并记 WARN 日志，
        绝不覆盖平台取值；**不返回错误**（fail-safe：一个多余的 KV 不应让整条生成链路失败）
   ⚠️ 本条修正 @后端 的临时口径（"按字面优先级、绑定可覆盖 + WARN"）：WARN 保留，覆盖行为取消。
   🔴 安全影响（为什么必须是"不可覆盖"）：
      tenantId 是**租户身份语义**，允许绑定覆盖等于让写库者把提示词里的租户号改成别的租户号
      —— 模型据此可能生成跨租户口径的内容，属"提示词层面的身份伪造"，
      与 §1.1「客户端传入 tenantId 一律忽略」（EX-003）同一条防线，不能一处严一处松；
      locale / timezone / nowIso 被覆盖则会产出确定性错误的时间与语言语义，排障成本极高
      （表现为"AI 说的时间总是不对"，却查不出代码问题）。
      租户若需要自定义时间/语言口径，🔴 应声明一个**非保留名**变量（如 displayTimezone）。
3. 未声明即使用（正文出现 {{x}} 但 variables_schema 无 x，且 x 非保留名）：
   → 校验入口 §7.3.1 返回 30060 rule=undeclaredVariable（AC-SKL-001）
   → 运行时兜底同样返回 30060，🔴 在进入模型之前失败（AC-CFG-004）
   🔴 保留名无需声明即可直接使用（它们不走声明校验）
4. 已声明 required=true 但 ① ② 均为空 → 30060 rule=missingVariableValue（进模型前失败）
5. 已声明 required=false 且无值 → 替换为空字符串（确定性行为，不失败）
6. 已声明但未使用 → 校验通过，仅在 warnings[] 提示 rule=unusedVariable
7. 🔴 变量值一律按纯文本注入，禁止二次解析：值中出现的 {{…}} 不再展开（防注入套娃）
```

**相关错误码**：`30060`（未声明变量 / 缺必填值 / 正文超限 / 保留名冲突 / **system 消息总长超预算**，`rule=systemPromptBudgetExceeded`）

---

#### 7.5.4 多轮上下文窗口策略（🔴 V1.1.9 新增）

**🔴 总输入长度由三段各自有界的预算共同封顶**

```
system 提示   ≤ chat.system_prompt_max_chars     超限 30060，禁止截断（§7.5.2 ①）
                🔴 V1.2.4：此处"system 提示"精确指**租户段**（不含摘要块与平台纪律段）
更早内容摘要   ≤ chat.context_summary_max_chars   确定性生成，天然有界
最近消息窗口   ≤ chat.context_max_chars           从最新往回填，超预算即停
```

**🔴 摘要的消息载体（V1.2.4 新增，ADR-019；🔴 订正一个同源既有隐患）**

```
✅ 现口径：更早内容摘要作为 **唯一那条 system 消息内部的一个块**注入
   （位置 = 租户段之后、平台纪律段之前；前缀文案与选材规则**逐字不变**）。

❌ 作废口径：摘要作为**另一条 system 消息**注入（M1 起的既有实现）。
🔴 为什么必须改（这是一个真实存在、只是本轮未被实测触发的缺陷）：
   · 上游硬约束是"system 至多 1 条且必须在 index 0"（§7.5.2 不变量）——
     摘要一旦作为第 2/3 条 system 下发，🔴 与 BUG-MCP-004 触发的是**同一个 400**；
   · 触发条件与工具**无关**：只要"会话有更早内容 + 摘要已缓存"即命中；
   · 🔴 失败形态最恶劣：摘要缓存 TTL 期间该会话**每一轮**都失败 →
     表现为「该会话不可用、用户重试无效」—— 正是本节开头力图消灭的
     **不可自愈**失败模式，只是换了触发原因；
   · 🔴 之所以从未被自动化测出：现有 IT/E2E 的桩上游**不校验消息形态**
     （test-report V4.1 §5 已如实指出该覆盖缺口）→ 故 §8.3 J12 把
     "桩必须复刻上游硬约束"定为**签署前置**。
🔴 不变的部分：摘要触发判定（按**实际纳入条数**）、两端锚定选材、省略标记、
   单条截断、"摘要暂缺即退化为确定性滑动窗口且绝不阻断本轮"—— 全部逐字不变。
🔴 明确否决把摘要改成 role=user 消息：ⓐ 它不是用户说的话（语义错位，且会被后续
   用户输入"覆盖"）；ⓑ 会与窗口首条 user 相邻产生连续 user 消息（部分上游要求
   user/assistant 交替，等于用一个兼容性问题换另一个）；ⓒ 会挤占 context_max_chars。
```

**🔴 为什么必须给窗口加长度预算（缺它会产生不可自愈的故障）**

```
message_max_chars(20000) × context_max_messages(20) = 400,000 字符
→ 远超任何模型的上下文窗口，上游拒绝【整个请求】
→ 🔴 而窗口每轮都会重新纳入同样的超长历史
→ 表现为【该会话永久不可用】：用户无论重试多少次都失败，唯一出路是新建会话
这是多轮场景下最严重的失败模式，且用户侧完全无法自愈。
```

**窗口构建规则（顺序即优先级）**

```
1. 倒序取最近 context_max_messages 条已定稿消息（sent/completed/stopped，is_current=1）
2. 🔴 从【最新】往回累加码点，超 context_max_chars 即停
   —— 越近的轮次对当前回答越关键，预算耗尽时必须先保住最近的对话
3. 🔴 最新一条【无条件保留】，即使它自身就超预算
   —— 它是本轮用户提问；丢了它模型根本不知道要回答什么
4. 空 assistant 不纳入（一条空回答没有信息量，只占预算）
5. 🔴 窗口不得以 assistant 开头 → 丢弃头部孤立 assistant
   —— 真实会话永远从 user 开始，窗口以 assistant 开头只可能是【截断伪影】：
      模型会看到"一个没有问题的回答"，既无信息量又干扰它对话轮结构的理解
   ⚠️ 例外：整窗无 user 时【不清空】（fail-safe 优先于结构洁癖，否则只剩 system）
6. 🔴 本轮工具链（assistant(tool_calls) + tool 结果）由 ChatStreamRunner 在窗口【之后】
   追加，永不进入窗口裁剪逻辑（否则 tool_call_id 配对断裂 → 上游拒绝整个请求）
```

**🔴 摘要触发判定：必须用「实际纳入条数」，不是「条数上限」**

```
❌ 旧口径：countForContext > context_max_messages
   缺陷：那些【条数没超但内容超长】的会话（窗口刚被长度预算裁过）永远拿不到摘要
        —— 而它们恰恰是最需要摘要的
✅ 新口径：countForContext > 实际纳入窗口的条数
```

**🔴 摘要选材：两端锚定**

```
① 紧邻窗口的更早内容【优先】（游标倒序返回）
   —— 旧实现从【最旧】开始拼，摘要预算很快耗尽，
      结果把"紧邻窗口、对连续性最关键"的那几轮全丢了（方向性缺陷）
② 开场首条用户消息【无条件预留预算】
   —— 任务设定（"全程用 TypeScript"/"预算 5000 元"）几乎总在开场；
      旧实现的回看上界会让长会话彻底看不到开场
   ⚠️ 仅当它确实落在窗口之外时才作为锚点（否则与窗口重复、白占预算）
③ 中间被略去时插入显式省略标记，🔴 不让模型误以为摘要是连续的
④ 单条按 context_summary_item_max_chars 截断并补省略号
   —— 补省略号是为了避免模型把半句话当作完整陈述
```

**🔴 摘要边界必须用游标，不能用下标偏移**

```
窗口在装配时会跳过空 assistant、并按长度预算提前收尾，
因此「窗口内的 K 条」≠「倒序结果的前 K 条」。
旧实现用 subList(windowSize, …) 做下标算术，一旦发生上述跳过就会
把仍在窗口里的消息重复计入摘要、或漏掉本该摘要的消息。
✅ 以「窗口中最旧那条」的 (createdAt, id) 为游标是唯一稳的边界。
```

**已知限制（一期接受）**

```
1. 摘要仅存 Redis（TTL 12h）：过期后【首轮】退化为纯滑动窗口，该轮丢失长期上下文，
   生成结束时会重建。彻底消除需把摘要落库（messages/conversations 加列）。
2. 摘要为【确定性拼接】而非模型摘要：稳定可复现、零失败率、零额外成本，
   但压缩率与语义质量不及模型摘要。若要替换，只需改 refreshSummary 实现，
   调用方与降级路径都不用动。
3. 回看上界 context_max_messages × 4 是【查询安全上界】（防超长会话一次拉回几千行），
   不是覆盖范围参数 —— 覆盖保证来自「紧邻优先 + 开场锚点」，两端都不会因它丢失。
```

---

### 7.6 M3：MCP 运行时调用契约

#### 7.6.1 传输取舍

| 传输 | 取舍 |
|---|---|
| `streamable_http` | 🔴 **首选**（`sys_config: mcp.transport_preferred`）：单端点 POST + 可选流式响应，连接管理简单，与单体同步线程模型契合 |
| `sse` | MCP **HTTP+SSE** 传输。🔴 **V1.2.0（G6′ 裁决）起同时支持两种形态**：① **同步应答形态**（POST 响应体内直接返回 JSON-RPC 结果）② **2024-11-05 异步推送形态**（POST 回 `202` 空体，结果由同一次 exchange 内持有的 GET 事件流推送）。形态由客户端**自适应判定**，🔴 **不新增传输枚举值**；单次调用仍受总预算约束（见 G6′ ⑤「deadline 预算制」），🔴 **禁止长驻连接、禁止跨调用复用 session** |
| `stdio` | 🔴 **一律拒绝**（不向租户开放，PRD §8.6）：`mcp_servers.transport='stdio'` → `30060` |

> 🔴 **禁止引入 WebFlux / 消息中间件 / 网关**（ADR-001）：MCP 客户端使用 JDK 内置 `HttpClient` 同步调用，运行在既有 `aiStreamExecutor` 线程内。

**⚠️ 裁决（G6）`sse` 传输一期只支持"同步应答形态" —— 🔴 已由下方 G6′（V1.2.0）取代，保留仅供追溯**：

```
上游若采用旧版 SSE 形态（POST 只回 202 Accepted，真正的 JSON-RPC 结果稍后
由另一条 GET 事件流异步推送），则消费该结果**必须有第二个读取线程**在 GET 流上阻塞等待
—— 与 ADR-008 第 8 条「🔴 不新增线程池」直接冲突（生成线程已被 POST 占用）。

🔴 一期口径（采纳 @后端 临时口径并升格为正式契约）：
① 允许的形态：POST 提交 JSON-RPC 请求 → **同一 HTTP 响应体内**返回 result（可为
   text/event-stream 单事件或 application/json，二者均在**同一次 send() 内**读完）
② 🔴 只回 202 / 空体 / 要求从独立 GET 流取结果 → 运行时调用判 30052；
   连接测试判 data.result="protocol_incompatible"（§7.4.2）
③ 🔴 禁止为兼容旧版形态引入任何常驻读取线程、订阅线程或第二个线程池；
   如确有此类上游必须支持，走 🔄 变更请求由 @架构师 裁决是否纳入二期
   （届时需重估 ADR-006/ADR-008 的线程模型，属架构级变更）
④ 首选传输仍为 streamable_http；sse 是**兼容分支**，不是等价选项。
```

> 🔴 **G6 已失效**：其②的"只回 202 → 一律 `30052`"被 **G6′** 取代（现改为"自适应进入异步形态"）；其③的"禁止引入第二个线程池"**继续有效**，但边界已由 `architecture.md` **ADR-008 第 8 条 V1.4.0 补注**明确定义。G6 ①④ 继续有效。

**🔴 裁决（G6′，V1.2.0，取代 G6 ②）`sse` 传输支持 2024-11-05 异步推送形态，落点为「形态自适应」而非新增传输枚举**：

> 📌 触发前提已成立（G6 自己留的裁决点）：需为 `gift` 租户接入腾讯云 WSA MCP，实测 `POST /sse/{id}` → **`405`**（无 Streamable HTTP 变体）、`POST /message/{id}?sessionId=…` → **`202` 空体**、结果**只从 GET 流推送**、`sessionId` **绑定在那条 GET 流上**、`initialize.result.protocolVersion = 2024-11-05`。完整证据、备选方案对比与技术前提纠正见 `architecture.md` **ADR-016**。

```
① ✅ 支持。🔴 mcp_servers.transport 合法值**仍恰为 2 个**（streamable_http / sse），
   🔴 不新增 sse_legacy：DBA 无法从 URL 判断上游形态，让运维选枚举 = 把探测责任推给运维。
   👉 DDL 零变更；§7.3.1 的 transport 枚举校验零变更；McpCheckResult 字面量零扩充（仍 9 个）。

② 🔴 单次 exchange 的固定序列（全程在**一个** deadline 预算内）：
   1) GET endpoint 建流，🔴 流保持打开
      🔴 **V1.2.1 订正（GET 非 2xx 必须二分，取代原"非 2xx → 退化"的笼统措辞）**：
      · 【安全/鉴权语义类】🔴 直接失败、绝不退化：
          3xx → protocol_incompatible（否则 followRedirects=NEVER 会被退化路径绕过）
          401 / 403 / 407 → auth_failed（否则鉴权失败被掩盖成协议不兼容且丢诊断线索）
      · 【能力类】其余非 2xx（404 / 405 / 5xx / 其它）→ 视为"该地址只接受 POST"，
          退化为直接 POST 原 endpoint（既有兼容行为，🔴 不视为故障），最终分类由该次 POST 决定
      · 🔴 GET 与 POST 必须共用**同一个**状态码分类判据（分叉会让同一故障产生两种 data.result）
      📌 完整逐行映射见 architecture.md ADR-016「失败分类对照表」（🔴 该表为唯一裁决基线）
   2) 等 `event: endpoint` → 🔴 sameOrigin 校验 → sessionUri（无该事件则用原 endpoint）
   3) POST `initialize` —— 🔴 它**同时**是「形态探测」与「协议握手」：
      · 响应体含可解析 JSON-RPC 报文 → **同步形态**：🔴 忽略该 initialize 的成败
        （含 -32601 / -32602 / 任意 error，它只用于探测）→ POST 目标方法 → 从响应体取结果
      · 只回 2xx + 空体 / 无 JSON-RPC 报文（含 202）→ **异步形态**：
        从 GET 流等 initialize 的 result（🔴 必须成功，否则 protocol_incompatible）
        → POST `notifications/initialized`（通知，无 id，🔴 不等结果）
        → POST 目标方法 → 从 GET 流等**匹配 id** 的 result
   4) finally：🔴 强制关流（cancel 订阅 + cancel 响应 Future）

③ 🔴 为何必须先 initialize（而非"先发目标方法、失败再补握手"）：后者在上游强制握手时
   会要求**重发 tools/call** —— 重试非幂等外部工具调用可能造成**重复副作用**，
   与 30056「非幂等结果未知一律不自动重试」直接冲突。前置 initialize 从结构上消灭该路径。
   代价：同步形态的 sse 上游每次 exchange 多一次 POST（sse 是兼容分支、非首选传输，可接受）。

④ 🔴 结果匹配：逐帧解析 SSE（多行 data: 按 \n 拼接、空行为帧边界），
   仅接受 jsonrpc=="2.0" 且 id == 本次 requestId 的报文；其余帧
   （notifications/*、logging、ping、resources 变更、id 不匹配）🔴 一律丢弃。

⑤ 🔴 deadline 预算制（订正既有实现缺口）：exchange 入口算一次 deadline，
   GET / 各次 POST / 各次等待 各取 remaining()，🔴 累加不得超预算；remaining ≤ 0 → timeout。
   预算取值不变：tools/call = min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)；
   tools/list = mcp.discover_timeout_seconds（🔴 含全部翻页，翻页不得重置预算）；
   🔴 V1.2.1 订正：连接测试（POST /admin/mcp/{mcpId}/test）= **mcp.discover_timeout_seconds**
      （原文"连接测试 = mcp.connect_timeout_seconds"**作废** —— 该接口就是执行一次 tools/list，
        不存在独立握手请求；connect 键的语义收窄见下方 G7 表格 V1.2.1 补注）。
   🔴 原实现"每个子请求各取一份完整 timeout"使 sse 最坏可达 2×~4× 预算 —— 属缺陷，一并订正。

⑥ 🔴 「不长驻连接」的正式定义：GET 流存活期 ⊆ 单次 exchange 且 ≤ 单次调用总预算；
   finally 强制关流；🔴 禁止把 sessionId / 流 / 订阅者写入任何字段、静态变量、Redis、DB、缓存；
   🔴 禁止跨调用复用 session。判据 = 存活期由单次调用预算封顶 且 无任何跨调用引用。

⑦ 🔴 SSRF 纪律不削弱（点位零变化）：三处 SsrfGuard 校验（§7.3.1 校验入口 / §7.4.2+§7.4.3 /
   §7.6.3 第 4 步）**完全不变**且仍在发起任何连接之前；followRedirects=NEVER 不变；
   会话端点 sameOrigin 校验保留（跨源 → protocol_incompatible，🔴 不改判 30050）；
   🔴 新增两条纪律：`event: endpoint` **只认第一次出现的值**（防流内二次投毒）；
   本传输**只会**请求两个 URL（已校验 endpoint + 与之同源的 session endpoint），
   流内出现的任何其它 URL 一律不请求。

⑧ 🔴 运维开关（新增 2 个 sys_config 键，见 §7.1.2）：
   mcp.sse_legacy_enabled（BOOLEAN，默认 true，🔴 读取 fail-closed）——
     false 时 POST 空体一律判 protocol_incompatible / 30052，即**完整回到 G6 行为**（止血手段）；
   mcp.sse_stream_max_bytes（NUMBER，默认 4194304）——
     SSE 流累计字节上限，超限 → cancel + protocol_incompatible；
     🔴 不变量：必须 ≥ tool.result_max_bytes，违反则**拒绝启动**。

⑨ 🔴 向后兼容硬约束（@测试 必测）：内置 Mock MCP 的 /mock-mcp/sse 走**同步形态**，
   现有 TRANSPORT_SSE 用例与 AC-MCP-003「两种传输完整链路」🔴 必须原样通过。
   注意：实现会多发一次 initialize POST，Mock 对未知方法回 -32601 —— 🔴 该 error
   必须被忽略（它只是探测信号），不得影响链路结论。
```

**🔴 裁决（G7）超时口径：建连超时是基础设施级、不按请求覆盖**：

| 层级 | 取值来源 | 说明 |
|---|---|---|
| **建连超时（TCP + TLS）** | `application.yml` 的 `app.ai.connect-timeout-seconds`（默认 **10s**，基础设施参数，在 `architecture.md` §7.1 白名单内） | 🔴 JDK17 的 `HttpClient.connectTimeout` **只能设在 Client 上、无法按请求覆盖**；本项目复用**唯一** `HttpClient` Bean（`HttpClientConfig`，`followRedirects=NEVER`），故建连超时对 MCP / AI 上游全局统一 |
| **请求级超时** | `HttpRequest.timeout()`，取 `mcp.connect_timeout_seconds`（🔴 **V1.2.1 收窄**：仅二期"独立 `initialize` 握手阶段"使用，一期**无代码消费点**）、`mcp.discover_timeout_seconds`（`tools/list` **与连接测试**）、`min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)`（`tools/call`） | 这是**真超时**，由 HttpClient 中断请求 |

> 🔴 **V1.2.1 补注（`mcp.connect_timeout_seconds` 的现状与去向）**：一期该键**没有任何代码调用点**（连接测试的 exchange 预算已订正为 `mcp.discover_timeout_seconds`，见 §7.4.2 / G6′ ⑤）。
> 它**保留**的两个理由：① 它是运维不等式 `app.ai.connect-timeout-seconds ≤ mcp.connect_timeout_seconds` 的一端（该不等式仍是排障口径的一部分）；② 📋 二期若为 `streamable_http` 引入独立 `initialize` 握手阶段，该阶段的请求级预算取本键。
> 🔴 因此本键**仍在** `StartupChecker.REQUIRED_CONFIG`（缺键即启动失败），🔴 **不得**因"暂无消费点"而删除键或移出必需集（键总数仍 **29**）。

```
🔴 mcp.connect_timeout_seconds 的语义（🔴 V1.2.1 订正）=
   ① 运维不等式的参照值；② 📋 二期独立 initialize 握手阶段的请求级预算键。
   它**不是** TCP 建连超时本身，也**不再**是"连接测试阶段的总超时"
   （原表述作废：连接测试的 exchange 预算 = mcp.discover_timeout_seconds，见 §7.4.2）。
🔴 运维纪律：必须保持 app.ai.connect-timeout-seconds ≤ mcp.connect_timeout_seconds（两者默认均为 10s）；
   若把后者调小到小于前者，会出现"请求级超时先到、诊断结果为 timeout 而非 connect_failed"的误判。
📋 备选方案否决：为 MCP 单独建第二个 HttpClient Bean（可独立设 connectTimeout）——
   否决理由：会出现两套连接池与两套重定向/代理策略，SSRF 防线（followRedirects=NEVER）
   存在被漏配到其中一套的风险，收益（更精确的建连超时）远小于该一致性风险。
```

#### 7.6.2 JSON-RPC 2.0 载荷约定

> 🔴 **裁决（G9）一期不维护 MCP 会话**：**不发送** `initialize`、**不维护** `sessionId` / `Mcp-Session-Id`、不做 `notifications/initialized`。
> 每次调用都是**无状态的一次 POST**（鉴权靠 `authType` 对应的请求头），"握手成功"以 `tools/list` / `tools/call` 返回合法 JSON-RPC 2.0 为判据。
> 🔴 上游强制要求先 `initialize`（或强制会话头）→ `protocol_incompatible`（连接测试）/ `30052`（运行时）；@后端 **不得**私自引入会话状态（会带来会话过期、重连、跨请求状态三类新问题，与"无状态单体"背离）。
> 📋 二期若需支持 `initialize` 协商（协议版本 / capabilities），须先回写本节并评估会话生命周期归属。

**🔴 裁决（G9′，V1.2.0）G9 的限定修订：`sse` 允许「exchange 内一次性握手」，`streamable_http` 维持原样**（依据 `architecture.md` ADR-016 ⑩）：

| 传输 | `initialize` | 会话状态 | 说明 |
|---|---|---|---|
| `streamable_http` | 🔴 **仍不发送** | 🔴 **仍不维护** `Mcp-Session-Id` | G9 原文对本传输**完全有效、逐字不变**；上游强制要求握手或会话头 → `protocol_incompatible` / `30052`。📋 二期再议 |
| `sse` | ✅ **发送**（🔴 兼作形态探测，见 §7.6.1 G6′ ②③） | ✅ 允许，但🔴 **生命周期严格 ⊂ 单次 exchange** | `sessionId` 由上游在 `event: endpoint` 中给出并绑定在该次 GET 流上；🔴 禁止写入任何字段/静态变量/Redis/DB/缓存，🔴 禁止跨调用复用，`finally` 强制关流 |

```
🔴 修订后仍然成立的不变量（G9 的真正目的）：
   **系统不持有任何跨请求的 MCP 会话状态** —— 因此依然没有会话过期、没有重连语义、
   没有多实例亲和问题，与"无状态单体"完全一致。
🔴 G9 原文中被修订的部分**仅限**"sse 传输不得发送 initialize"这一句；
   其余（不维护跨请求会话、不得私自引入会话状态、streamable_http 全部口径）继续有效。
🔴 连接测试的"握手成功"判据同步细化（§7.4.2）：
   streamable_http = tools/list 返回合法 JSON-RPC 2.0 且含 result.tools（不变）；
   sse = 形态判定成功 + tools/list 返回合法 JSON-RPC 2.0 且含 result.tools
        （异步形态还额外要求 initialize 的 result 成功）。
```


**工具发现 `tools/list`**：

```json
{ "jsonrpc": "2.0", "id": "d1f0…", "method": "tools/list", "params": { "cursor": null } }
```

响应 `result.tools[]` 形态（发现结果的数据形态，落 `mcp_tools`）：

```json
{
  "jsonrpc": "2.0",
  "id": "d1f0…",
  "result": {
    "tools": [
      { "name": "lookup_user", "description": "查询用户", "inputSchema": { "type": "object", "properties": { "uid": { "type": "string" } }, "required": ["uid"] } }
    ],
    "nextCursor": null
  }
}
```

- `toolKey` = `{mcpKey}:{name}`（🔴 租户内唯一，避免多 MCP 同名工具冲突）
- `inputSchemaDigest` = `sha256(规范化 inputSchema)` 前 16 hex，用于 §7.4.3 的 `schema_changed` 判定
- `nextCursor` 非空时继续翻页，累计工具数受 `mcp.max_tools_per_server` 限制

**工具调用 `tools/call`**：

```json
{ "jsonrpc": "2.0", "id": "c8a2…", "method": "tools/call", "params": { "name": "lookup_user", "arguments": { "uid": "10086" } } }
```

响应：

```json
{
  "jsonrpc": "2.0",
  "id": "c8a2…",
  "result": { "content": [ { "type": "text", "text": "{\"name\":\"…\"}" } ], "isError": false }
}
```

#### 7.6.3 每次调用前的强制校验顺序（🔴 顺序不可调整）

```
1. 租户上下文存在（缺失 → 30013）
2. 工具在当前会话 agentVersion 的绑定清单内（否则 → 30050 + 审计）
   🔴 V1.1.5（G-2）：本步在**清单构造期**判定一次，结果作为本轮生成的**快照**；
   执行前点查 **不复查** agent_capability_bindings（理由见下方 G-2 裁决框）
3. mcp_servers.status='enabled' AND mcp_servers.deleted_at IS NULL
   且 mcp_tools.granted=1 AND mcp_tools.status='enabled'（否则 → 30050 + 审计）
   🔴 V1.1.5（G-1）：deleted_at 判空**只对 mcp_servers**（其余表无该列，见下方 G-1 裁决框）
4. 🔴 SSRF 双点位校验之「运行时兜底」：重新校验 endpoint（HTTPS + DNS 解析结果 + 目标 IP）
   —— 不依赖保存时的校验结论（AC-MCP-004：先写合法地址、再改库为非法地址，运行时仍须拒绝）
   拒绝 → 30050，且 🔴 必须写安全审计 action=mcp.ssrf_rejected（EX-029）
5. inputSchema 校验入参（不符 → 30053，不发起网络调用）
6. 风险等级与确认策略判定（§7.7.3；需确认则进入 §7.8 等待）
7. 轮次未超 tool.max_rounds（超限 → 30054）
```

**🔴 裁决（#4，V1.1.4）第 2/3 步的执行时点 = 🔴 每次工具执行前（置 `running` 之前），不只是清单构造时**

```
事实认定：@后端 当前只在**清单构造**阶段校验授权，执行期仅重查了 mcp_servers（status /
endpoint）。因此「DBA 撤销 mcp_tools.granted」与「撤销本地 Tool 授权」在执行期不被复查。

🔴 裁决：✅ **采纳补一次点查**（不是"新增要求"，而是**订正实现缺口**）。
本节标题自 V1.1 起就写着「**每次调用前**的强制校验顺序（🔴 顺序不可调整）」，
第 3 步本身即 granted 点查 —— 只在清单构造时做，属未完整实现既有契约。

为什么不能接受"本轮仍执行一次"：
① 🔴 AC-MCP-004 是**明文 AC**：「改库为非法 / 取消授权后，运行时仍须拒绝」。
   fail-open 直接违反该 AC，不是性能取舍问题；
② 窗口不是毫秒级：清单构造 → 工具执行之间横跨**整轮生成**，含高风险确认等待
   （最长 tool.confirm_wait_seconds=120s）与多轮循环（最多 tool.max_rounds 轮），
   最坏可达数分钟。"DBA 紧急撤授权后还能被执行数分钟"不可接受；
③ 🔴 @后端 的 D-004/D-006 顾虑不成立：那两条纪律约束的是**租户识别 + 配置读取的 20ms 热路径**
   （architecture.md §14.1 不变量）与**清单构造的 N+1**。执行前点查发生在
   **异步段、首个可见帧之后**，既不在 20ms 红线内，也不受首字 P95 约束（§5.4.2）；
   相对 MCP 的一次网络往返（上限 mcp.call_timeout_seconds=30s）完全可忽略。
```

| 项 | 契约 |
|---|---|
| 时点 | 🔴 `pending`/确认通过之后、`tool_calls → running` **之前**；每次工具执行各一次（多轮循环每轮都查） |
| 查询预算 | 🔴 **≤1 次**（§7.1.2 查询次数表第 3 行）：MCP → `mcp_tools JOIN mcp_servers` 单行点查；本地 Tool → `tenant_tool_grants JOIN local_tools` 单行点查。🔴 禁止拆成多次 |
| MCP 判据 | `mcp_tools.granted=1` AND `mcp_tools.status='enabled'` AND `mcp_servers.status='enabled'` AND `mcp_servers.deleted_at IS NULL`（🔴 V1.1.5 G-1 订正：`deleted_at` 判空**仅** `mcp_servers`；`mcp_tools` **无该列**）。endpoint 的 SSRF 重校验仍按第 4 步独立执行 |
| 本地 Tool 判据 | `tenant_tool_grants.granted=1` AND `tenant_tool_grants.status='enabled'` AND `local_tools.status='enabled'`（🔴 V1.1.5 G-1 订正：两表**均无** `deleted_at` 列，原文"两行 `deleted_at IS NULL`"作废；本行与 §7.7.2 的授权四条件一致，🔴 绑定条件按下一行的快照口径） |
| 🔴 复查范围（V1.1.5 G-2 裁决） | 复查**仅**上两行的授权/启用列（+ 第 4 步 SSRF 独立执行）。🔴 **不复查** `agent_capability_bindings`（能力绑定）、🔴 不复查 `agent_versions.tool_policy`、🔴 不复查 `input_schema` 变更 —— 三者均为**本轮生成期快照**，理由见下方 G-2 裁决框 |
| 失败处置 | 🔴 `running` 尚未置入时直接 `pending → denied`；若已置 `running`（确认通过后的竞态）则 `running → denied`（§7.8.1 ③ 已裁决的迁移）。一律 `errorCode=30050` + 审计 `action=tool.grant_denied`，与状态流转**同一独立短事务**（§7.14） |
| 🔴 残余窗口（@测试 据此断言，**不得**判为缺陷） | 点查通过 → `invoke` 返回之间仍有 TOCTOU 窗口，大小 = **单次工具执行时长**（本地 Tool ≈ 毫秒级；MCP ≤ `mcp.call_timeout_seconds`，默认 30s）。判据：撤授权后**新发起**的工具执行必须被 `30050` 拒绝；**已进入 `invoke`** 的那一次允许完成。🔴 不可消除（与 SSRF 的 AR-009 同类），已登记 `architecture.md` **AR-017** |
| 不做的事 | 🔴 不引入"执行中撤授权即中断"的能力（需第二线程/中断机制，违反 ADR-008 第 8 条"不新增线程池"）；🔴 不缓存点查结果（缓存即回到 fail-open） |

**🔴 裁决（G-1，V1.1.5）三张表没有 `deleted_at` 列 → ✅ 订正契约表述，🔴 不做 DDL 变更**

```
事实（@后端 已核对 information_schema）：
  · mcp_servers          → ✅ 有 deleted_at
  · mcp_tools            → ❌ 无
  · tenant_tool_grants   → ❌ 无
  · local_tools          → ❌ 无

🔴 裁决：✅ 订正文档（本节判据两行已改），❌ **不加列**。三条理由：
① **语义已被现列完整承载**：这三张表的"不可用"语义是 granted=0 / status='disabled'，
   不是"行被删除"。§13.5.4 已明文规定 removed 工具**保留历史行置 disabled、🔴 不物理删除**
   （为保住 tool_calls 的 tool_key 可追溯性）—— 既然从不删行，deleted_at 恒为 NULL，
   等于在每条点查 SQL 上加一个永真条件，纯噪声；
② **与既有运维纪律冲突**：§13.5.10 已硬性规定"调整授权一律 UPDATE granted/status，
   🔴 禁止 DELETE + INSERT"。引入软删列会给出第三种"撤销手段"（置 deleted_at），
   而它**不会**被 agent_capability_bindings 的悬挂检测覆盖，反而制造新的歧义面；
③ **代价与收益完全不对称**：加列 = DDL + 重跑 ddl-auto:validate + 改 4 处点查 SQL +
   全量回归，收益 = 0（没有任何 AC 依赖软删语义）。M3 终验期动 DDL 属不必要风险。

🔴 配套（已在 architecture.md §13.5.4 / §13.5.5 / §13.5.6 显式登记）：
   三张表按 §13.5.10 同一体例**显式豁免** §13.2 第 4 条的软删约定，
   避免未来 ddl-auto: validate 漂移与本争议重开。
🔴 @测试 断言：撤销以 granted/status 为唯一判据；🔴 不得断言这三张表的 deleted_at。
```

**🔴 裁决（G-2，V1.1.5）执行前点查 **不**复查「被 agentVersion 绑定」→ ✅ 绑定 = 本轮生成期快照（正式契约）**

```
🔴 裁决：❌ **不要求**执行前复查 agent_capability_bindings；查询预算**维持 ≤1 次不放宽**。
（📌 顺带纠正一处技术判断：三表 join 在 SQL 上仍是 1 次查询，所以"突破预算"不是真正的理由 ——
  真正的理由是下面的语义与安全分层，@后端 无需为此返工。）

理由 ①（语义，决定性）：绑定回答的是「本次生成中，模型**能看到哪些工具**」。
  工具定义在首帧之前就已随 system/tools 载荷交给模型了。若中途解绑，
  模型仍会按已知定义发起调用，而执行侧却拒绝 → 用户看到无法解释的 30050，
  模型还会重试消耗 tool.max_rounds。🔴 一次生成内工具清单必须**自洽**，
  这与 §7.5.2「Skill 按 ref_version 取不可变版本」是同一条"生成期快照"原则。

理由 ②（安全分层，决定性）：绑定是**能力编排**（谁能用什么），
  granted / status / server enabled 才是**授权闸门**（是否被允许用）。
  紧急止血的正确手段是关闸门，而 §7.6.3 的执行前点查**已覆盖全部闸门**：
  🔴 撤 granted、停用 mcp_tools.status、停用 mcp_servers.status、
     撤 tenant_tool_grants.granted / status —— 任一生效即**下一次执行**被 30050 拒绝。
  因此"复查绑定"不增加任何**授权**维度的防护能力，只增加不自洽的失败面。

🔴 安全影响（如实登记，@测试 据此断言而非判缺陷；已登记 architecture.md AR-019）：
  DBA 在生成过程中**解绑**（DELETE agent_capability_bindings 行）后，
  🔴 **本轮生成剩余部分仍可执行该工具**，窗口 = 本轮生成剩余时长
  （最坏 ≈ 确认等待 tool.confirm_wait_seconds + 剩余 tool.max_rounds 轮 × 单次执行时长）。
  🔴 但它**不是**授权绕过：该工具此刻仍处于 granted=1 + enabled 状态，
     即"仍被授权、只是不再编排给这个 Agent 版本"。
  🔴 紧急场景的正确操作（写入 README 运维手册）：
     禁用工具 → UPDATE mcp_tools SET granted=0（或 status='disabled'）→ 下一次执行即拒；
     禁用整个 MCP → UPDATE mcp_servers SET status='disabled' → 同上；
     🔴 **不要**用"解绑"作为止血手段（生效点是下一次生成，不是下一次执行）。

🔴 @测试 断言（C5 / C7 已同步）：
  ⓐ 生成中解绑 → 本轮仍可执行 → ✅ **不判缺陷**（本契约明文授权）；
  ⓑ 解绑后**新发起的下一次生成** → 该工具不出现在清单、模型不再可调用 → 必测；
  ⓒ 生成中撤 granted / 停用 status → **下一次执行**必须 30050 + tool.grant_denied → 必测（C3）。
```

**SSRF 校验规则（保存时与运行时同一实现）**：

| 检查 | 规则 |
|---|---|
| 协议 | 必须 `https`（`sys_config: mcp.require_https=true`）；🔴 生产 `require_https=false` 为阻断上线项 |
| 端口 | 仅允许 443 与 `allowed_internal_cidrs` 场景下的显式端口 |
| 解析 | 解析全部 A/AAAA 记录，**任一**命中 `mcp.blocked_ip_cidrs` 即拒绝（含云元数据 `169.254.169.254`） |
| 白名单 | 命中 `mcp.allowed_internal_cidrs` 可豁免上一项 |
| DNS 重绑定 | 🔴 见下方「解析与连接的可实现口径」（**JDK17 无法严格 pin IP**，本版已订正 V1.1 的措辞） |
| 重定向 | 🔴 一律不跟随（`followRedirects=NEVER`）；出现 3xx → `30052` |

**🔴 解析与连接的可实现口径（V1.1.1 回写，与 `architecture.md` ADR-009 一致；@后端 按本框实现，@测试 按本框断言）**：

```
限制事实（JDK17，不可绕过）：
① JDK17 无法插拔 DNS 解析器 —— InetAddressResolverProvider 自 JDK18 起才存在
② 「以 IP 作为 URL host + 覆写 Host 头」不可行：Host 是受限请求头
   （需 -Djdk.httpclient.allowRestrictedHeaders=host），且 IP-URL 会使
   🔴 TLS 证书主机名校验必然失败；关闭端点识别 = 放弃 TLS 校验，那是更大的风险
👉 因此 V1.1 中"必须复用已解析 IP 发起连接（pin IP）"在 JDK17 下**无法落地**，本版改为等价实现。

采纳的等价实现（mcp/SsrfGuard + config 的 HttpClient 装配）：
① 调用前主动 InetAddress.getAllByName(host) 解析，**逐 IP** 比对
   mcp.blocked_ip_cidrs / mcp.allowed_internal_cidrs；🔴 拒绝则不发起任何网络连接
② 校验通过后**立即以原域名**发起 HTTPS 连接（🔴 保留完整证书链校验与 SNI，不做任何降级）
③ 启动参数固定 -Dnetworkaddress.cache.ttl=10（写入 README 启动脚本），
   使 ② 复用 ① 刚解析的结果，把 DNS 重绑定（TOCTOU）窗口压到 ≤10s
④ 🔴 因为**每次调用前都重新校验**（§7.6.3 第 4 步），攻击者必须在**每一次调用**上
   重新赢得 ≤10s 的竞态，而非一次成功即长期有效
⑤ followRedirects=NEVER：不跟随重定向，规避"302 跳内网"绕过（3xx → 30052）

🔴 残余风险（已知并接受，AR-009）：存在理论上的 DNS 重绑定残余窗口，
   由「每次调用前重校验 + ≤10s DNS 缓存 + 私网/环回/链路本地 CIDR 全覆盖」共同压制。
📋 二期路径：升级 JDK18+ 后改用自定义 InetAddressResolver 做**严格 pin IP**（已登记为二期技术债）。
```

#### 7.6.4 上游结果映射

| 上游情形 | `tool_calls.status` | `errorCode` | SSE 表现 |
|---|---|---|---|
| HTTP 2xx + `isError=false` | `succeeded` | `null` | `tool`(succeeded) |
| `result.isError=true` | `failed` | `30057` | `tool`(failed) |
| JSON-RPC `-32602`（invalid params） | `failed` | `30053` | `tool`(failed) |
| JSON-RPC `-32601` / 非 JSON-RPC 响应 / 缺 `tools` 能力 | `failed` | `30052` | `tool`(failed) |
| HTTP 401 / 403 | `failed` | `30052` | `tool`(failed)，写审计 |
| 连接失败 / 5xx / 3xx | `failed` | `30052` | `tool`(failed) |
| 超时（`mcp.call_timeout_seconds`） | `timed_out` | `30051` | `tool`(timed_out) |
| 结果超 `tool.result_max_bytes` | `succeeded` | `null` | `tool`(succeeded, `truncated=true`)，EX-017 |
| 非幂等工具超时/结果未知 | `timed_out` | `30056` | `tool`(timed_out)，🔴 **禁止自动重试**（AC-TOL-003） |

> 🔴 **MCP 输出按不可信内容处理**（PRD §8.6）：结果只作为 `role=tool` 消息回灌模型，**不得**改写系统提示、不得提升工具权限、不得触发未授权工具；回灌前执行 §5.4.3 脱敏与长度截断。
> 🔴 工具失败**不中断 SSE 流**：以 `tool`(failed) 帧告知，模型可继续生成（是否继续由 Agent 策略决定）；仅当模型无法继续时才发 `error` + `done`。

**🔴 裁决（V1.2.2 新增）失败回灌的「诊断来源二分」（`architecture.md` ADR-018 ③；订正实现缺口）**

```
🔴 事实认定（test-report V4.0 BUG-MCP-001 的直接成因，非推测）：
   编排层此前对**所有**失败码一律回灌**固定措辞**（"工具执行返回失败"），
   而执行器在 isError=true 分支**已经**把上游错误正文放进了结果内容（并已过 truncateForModel）。
   👉 这段**可用诊断在编排层被丢弃**了 —— 模型只知道"失败"，不知道"哪个参数非法"，
      于是只能换写法重试（实测：连续三轮、每轮都要用户再确认一次）。
   ⚠️ 对照：**成功**路径回灌的就是同一个 content。同一条链，失败路径丢内容 → 属**实现不一致**。

🔴 裁决：按「这句诊断是谁说的」二分，🔴 **不是**按错误码是否失败二分。

✅ **可回灌**（来源 = 上游工具自身的业务语义，或我们自己的入参校验器）：
   · 30057（result.isError=true）        → 固定措辞 + **上游错误正文**
   · 30053（本地 JSON Schema 校验失败）  → 固定措辞 + **校验器字段级诊断**
   · 30053（MCP JSON-RPC -32602）        → 固定措辞 + **上游 error.message**
   🔴 处理链**不变**：与成功内容同一条 truncateForModel（脱敏 → 字节截断 tool.result_max_bytes）
      → 🔴 因此**不引入任何新的泄露面**（成功路径本来就把上游文本喂给模型）。

❌ **不可回灌**（来源 = 平台/传输侧诊断，可能含 endpoint / 内网地址 / 堆栈 / 凭据线索）：
   · 30052（连接失败 / DNS / TLS / 3xx / 401 / 协议不兼容）
   · 30051 / 30056（超时类；30056 仍必须明确"结果未知、禁止自动重试"）
   · 30050（安全拒绝）→ 🔴 措辞必须与"不在清单内"**完全一致**，
        🔴 严禁按"撤授权 / 服务停用 / SSRF"差异化 —— 差异化即给出一条**探测平台配置的信道**
        🔴 **V1.2.3 追认**：四源（不在清单内 / preflight 服务停用 / 执行前授权点查 / 执行期竞态）
           统一回灌常量 `ToolOrchestrator.DENIED_FEEDBACK`，由单测**反向断言逐字相同**守护；
           🔴 `SSRF_REJECTED` 复用同一句（措辞不精确是**有意为之**，严禁改精确）；
           内部区分只体现在 `tool_calls.error_code` + audit + 服务端日志
   · 50003（内部错误 / 审计写入失败）
   👉 这些**继续只给固定措辞**（现状不变）。

🔴 判据一句话：
   **"上游工具或校验器**对参数**说的话" → 可回灌；"我们**对基础设施**的诊断" → 不可回灌。**

🔴 附带的不变量（逐条为 @测试 断言项，见 §8.3 J 组）：
   ① `tool` 帧的 resultSummary 仍按 tool.result_summary_max_chars 截断（🔴 与回灌体分别取值，
      ADR-011 第 3 条「两类截断严格分离」**不受本裁决影响**）；
   ② 🔴 回灌内容**不得**出现 endpoint / IP / 端口 / 凭据 / Java 异常类名 / 堆栈；
   ③ 🔴 回灌**不得**改变任何状态机、错误码或审计口径（本裁决只改"回灌文本内容"）；
   ④ 🔴 **禁止**由此派生任何自动重试：非幂等结果未知一律 30056、禁止自动重试（AC-TOL-003）不变；
      轮次仍由 tool.max_rounds 封顶（超限 → 30054）。
```

#### 7.6.5 模型函数名归一化与回映射（🔴 V1.1.3 新增，⑥ 裁决；本地 Tool 与 MCP 工具**共用**）

**背景**：MCP 的 `toolKey` = `{mcpKey}:{toolName}` 含 `:`，而 OpenAI 兼容接口（混元）要求 `tools[].function.name` 匹配 `^[a-zA-Z0-9_-]{1,64}$`。因此**下发给模型的函数名**与**契约中的 `toolKey`** 必然是两个不同的标识符，此前契约未定义两者关系（属契约缺口，@后端 临时口径本版追认并升格）。

| 项 | 规则 |
|---|---|
| 归一化算法 | 🔴 **逐字符映射**：`[a-zA-Z0-9_-]` 原样保留，**其余任何字符**（含 `:`、`.`、空格、中文、非 ASCII）一律替换为 `_`；不做大小写转换、不做去重压缩（`a::b → a__b`，🔴 不得压成 `a_b`，否则更易碰撞） |
| 示例 | `crm:lookup → crm_lookup`；`crm:lookup.v2 → crm_lookup_v2`；本地 Tool `datetime_now → datetime_now`（`local_tools.tool_key` 已受 `^[a-z][a-z0-9_]{1,63}$` 约束，**天然合规、恒等映射**） |
| 长度超限 | 归一化后 **> 64 字符** → 🔴 **拒绝，不截断**：判 `30060` `rule=functionNameTooLong`（`violations[].objectType='mcpTool'`，`field='toolKey'`） |
| 名称碰撞 | 同一清单内两个不同 `toolKey` 归一化后同名 → 🔴 **fail-closed** `30060` `rule=functionNameCollision`；`violations[].message` 只回"工具函数名冲突，请调整 `mcpKey` 或工具名"，🔴 不回显另一方的完整 `toolKey`（同租户内可回显 `mcpKey`，🔴 禁跨租户信息） |
| 回映射 | 🔴 **必须靠映射表**：`ToolCatalogService` 产出的每个工具定义同时携带 `toolKey` 与 `functionName`，`chat`（`ChatStreamRunner`）在**本次生成内**持有 `Map<functionName, 工具定义>`；模型返回 `tool_calls[].function.name` 时**查表**取回工具定义。🔴 **严禁**用字符串还原（`_ → :` 不可逆，`a_b` 无法判断原文是 `a:b` 还是 `a_b`） |
| 对外契约不变 | 🔴 `functionName` 是**内部标识符**：SSE `tool.toolKey`、`tool_calls.tool_key`、§7.9.1 查询、审计 `object_id` 一律记**原始 `toolKey`**，🔴 禁止把 `functionName` 泄漏到任何对外字段（否则前端与 @测试 会出现两套工具标识） |

```
🔴 为什么长度超限与碰撞都判 30060（fail-closed）而不是"静默少下发一个工具"：
① 静默少下发 = 模型看不到该工具 → 表现为"AI 说它没有这个能力"，DBA 无法从任何地方发现原因，
   属最难排查的静默降级（与 §7.5.2 ⑤ 否决"截断最低优先级片段"同一条理由）；
② 30060 是**配置非法**语义，且 §7.3.1 的校验入口用**同一实现**做同样判定 ——
   DBA 可在用户对话之前就发现冲突（这正是 AC-CFG-003 的价值）；
③ 🔴 截断 + 追加 hash 后缀的方案**被否决**：会产出人类不可读的函数名（如 `crm_look_9f2a`），
   模型提示词可读性下降、排障时无法把日志里的函数名对回 toolKey，收益远小于代价。

🔴 归属边界（与 architecture.md §5.1.3 一致）：
   归一化规则与碰撞判定归 tool（ToolCatalogService —— 只有它看得到完整清单）；
   functionName → 定义的映射表归 chat（按"本次生成"持有，生成结束即释放）。
🔴 不新增错误码、不新增 sys_config 键：字符集与 64 长度上限来自**上游接口协议**（非业务参数，
   同 §7.1.2 的配置边界口径），因此写在本表即为基线，禁止入库、也禁止在代码中另设可调开关。
```

---

### 7.7 M3：本地 Tool 契约

#### 7.7.1 平台注册表数据契约（`scope=platform`）

`local_tools` 为**平台表**（无 `tenant_id` 隔离维度），一期由平台管理员直接写库（`/api/v1/platform/local-tools` Deferred）。

| 列 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `tool_key` | VARCHAR(64) | 是 | 全局唯一，`^[a-z][a-z0-9_]{1,63}$`；🔴 该正则已使本地 Tool 的**模型函数名恒等映射**（无需归一化，但仍适用 §7.6.5 的碰撞与 64 长度判定） |
| `name` | VARCHAR(60) | 是 | 展示名 |
| `version` | INT | 是 | 注册版本，🔴 **就地递增**（同一 `tool_key` 恒为**单行**，不是多版本行）；语义见下方裁定 |
| `description` | VARCHAR(500) | 是 | 说明 |
| `input_schema` | JSON | 是 | **JSON Schema draft 2020-12**，根类型必须为 `object`；非法 → `30060` |
| `output_constraint` | TEXT | 否 | 输出约束说明 |
| `risk_level` | ENUM | 是 | `low` \| `medium` \| `high` |
| `idempotent` | TINYINT | 是 | 0/1；🔴 `0`（非幂等）时结果未知一律不自动重试（`30056`） |
| `timeout_seconds` | INT | 是 | 1 ~ `sys_config: tool.max_timeout_seconds`（120），缺省取 `tool.default_timeout_seconds`（30）；越界 → `30060` |
| `status` | ENUM | 是 | `enabled` \| `disabled` |

> 🔴 **版本语义裁定（V1.1.1 回写，与 `architecture.md` §13.5.5 一致）**：
> ① `local_tools` 是**单行表**：`uk_tool_key(tool_key)` 全局唯一，同一 `tool_key` **永远只有一行**，`version` 在该行上**就地递增**（`UPDATE … SET version = version + 1`）。
> 🔴 **不存在「一个 `tool_key` 对应多个版本行」的形态**（V1.1 同时声明"全局唯一"与"version 递增"却未定义形态，本版订正）。
> ② `agent_capability_bindings.ref_version`（`capability_type='localTool'`）的语义 = **绑定时 `local_tools.version` 的取值快照**，
> 🔴 **仅供审计对照**（回答"当时绑定的是哪一版注册元数据"），**不参与运行时解析**。
> ③ 运行时清单构造、参数校验、风险判定、超时与幂等判定**一律读 `local_tools` 当前行**（`input_schema` / `risk_level` / `timeout_seconds` / `idempotent` 取当前值），
> 以保证平台修正注册元数据后**立即生效**（AC-CFG-004）；`ref_version` 与当前 `version` 不一致 **不构成错误、不触发 `30060`**。
> ④ ⚠️ **与 Skill 的差异（不可混用）**：Skill 的 `ref_version` 是**精确版本引用**（§7.5.1，快照语义，旧会话行为不变）；本地 Tool 的 `ref_version` 是**审计快照**（就地递增语义，运行时取当前行）。

> 🔴 **禁止租户上传/写入任何可执行代码**（PRD 非范围项）：`local_tools` 只登记声明式元数据；实现体是平台内置 Java 组件，按 `tool_key` 静态注册。注册表中存在但平台无对应实现 → `30060`。

**🔴 一期平台内置本地 Tool 清单（V1.1.2 新增，G5 裁决；决策记录见 `architecture.md` ADR-015）**

一期内置**且仅内置**下列 2 个**纯函数、无外部副作用**的工具。🔴 清单之外的任何 `local_tools` 行都会因"平台无实现体"被 `30060` 拒绝（fail-closed），这是**正确行为**、不是缺陷。

| `tool_key` | 名称 | `risk_level` | `idempotent` | `timeout_seconds` | 语义 |
|---|---|---|---|---:|---|
| `datetime_now` | 当前时间 | `low` | `1` | `5` | 返回当前时间（可选 IANA 时区，🔴 缺省 UTC）；纯只读，无网络、无数据库 |
| `calculator` | 计算器 | `low` | `1` | `5` | 十进制四则运算表达式求值；纯函数，无网络、无数据库 |

**参数 Schema 要点（🔴 阈值一律写在 `local_tools.input_schema` 里，禁止写死在 Java 中，也不新增 `sys_config` 键）**：

```
datetime_now.input_schema（draft 2020-12，根类型 object，additionalProperties=false）：
  timezone : string，可选。IANA 时区 ID（如 "Asia/Shanghai"）；
             maxLength=64、pattern 限制为 [A-Za-z_]+(/[A-Za-z_+\-0-9]+)*；
             🔴 缺省为 **UTC**（不是租户时区）
  🔴 非法时区值（Schema 通过但 ZoneId 无法解析）→ 实现体抛 BusinessException(30053)，不执行、不回显内部异常
  返回：{ "iso8601": "...", "epochMillis": <number>, "timezone": "..." }

🔴 为什么缺省是 UTC 而不是租户时区（口径明确，避免实现分叉）：
   读 tenants.timezone 就是一次**数据库 IO**，会让本工具不再是"纯函数无 IO"，
   并引入 tool → platform 的新依赖边（architecture.md §5.1.2 未授权该边）。
   👉 正确做法：租户时区通过 Skill 的内置变量 {{timezone}} 注入**系统提示**（§7.5.3 ⓿），
      模型据此在调用时**显式传入** timezone 参数 —— 数据来源不变，但工具保持纯函数。

calculator.input_schema（draft 2020-12，根类型 object，additionalProperties=false）：
  expression : string，必填。minLength=1、maxLength=200、
               🔴 pattern 仅允许 数字 . + - * / ( ) 与空格：^[0-9+\-*/(). ]+$
  返回：{ "expression": "...", "result": "..." }（result 为字符串，避免 JS 精度问题）

🔴 实现纪律（安全边界，逐条不可省，@测试 必须覆盖）：
① 必须自写**递归下降解析器**；🔴 严禁 ScriptEngine / Nashorn / SpEL / JEXL / OGNL / 任何 eval 类设施
   —— 那等于把"模型输出"变成"可执行代码"（RCE）
② 🔴 一期不支持幂运算（^ / **）、阶乘、位运算、变量与函数调用：指数运算是 CPU 放大攻击面
   （一个 20 字符的表达式即可烧满一个 aiStreamExecutor 线程）
③ 数值一律 BigDecimal；除法保留 ≤20 位小数并四舍五入；括号深度上限 16；单个数字长度上限 30
④ 除零 / 溢出 / 解析失败 / 括号不匹配 → BusinessException(30057)（工具执行业务失败），
   🔴 不抛未分类异常、不回显堆栈、不把内部异常消息回灌模型
⑤ 🔴 两个工具都必须是**短、纯、不阻塞**的操作（无网络、无数据库、无锁等待、无无界循环）——
   这是 G8「本地 Tool 超时只计时判定、不强制中断」得以成立的前提（ADR-008 第 8 条）；
   🔴 也因此 `tool` 包**不新增**对 `platform` / 任何资源层包的依赖（architecture.md §5.1.2 不变）
```

**审计要求（🔴 明确，避免 @测试 断言缺失）**：

```
① 两个工具均为 low 风险纯函数 → 🔴 **成功执行不写 audit_logs**。
   理由：审计只承载**安全事件**；若纯函数每次调用都写审计，audit_logs 会被高频无害调用刷爆，
   真正的越权与拒绝事件被淹没（与 architecture.md §13.5.1 的 idx_action_time 检索目标背离）。
   可追溯性由 tool_calls（每次调用必落库 + 脱敏摘要 + 状态机）承担，SSE 与 §7.9.1 均可见。
② 🔴 未授权 / 未绑定 / 已停用被调用 → 仍写 action=tool.grant_denied（§7.14），口径不变。
③ 🔴 riskLevel=low 且 tool_policy ∈ {auto, confirm} → 自动执行、不进确认流程（§7.7.3 矩阵不变）；
   高风险确认（awaiting_confirmation）的生产可达路径由 **MCP 工具**承载（§7.4.3 G5-b 裁决 ④）。
④ 新增内置本地 Tool 必须：先回写本清单（含 Schema 要点/风险/幂等/超时/审计口径）→
   由 @架构师 复核"是否有外部副作用、是否可能长阻塞" → 方可实现。
   🔴 @后端 不得自行发明有真实副作用的工具（退款/导出/工单等）。
```

#### 7.7.2 租户授权数据契约

| 表 `tenant_tool_grants` 列 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | VARCHAR(32) | 🔴 隔离维度；`uk_tenant_tool(tenant_id, tool_key)` |
| `tool_key` | VARCHAR(64) | 指向 `local_tools.tool_key` |
| `granted` | TINYINT | 🔴 默认 `0`；仅 `1` 且 `status='enabled'` 才进清单 |
| `config` | JSON | **非代码配置**（如默认门店、回调白名单）；🔴 禁止出现脚本、表达式、可执行片段，出现 → `30060` |
| `status` | ENUM | `enabled` \| `disabled` |
| `granted_by` / `granted_at` | BIGINT / DATETIME | 授权者 uid 与时间 |

运行时清单构造条件（🔴 四个条件同时满足）：`local_tools.status='enabled'` **AND** `tenant_tool_grants.granted=1 AND status='enabled'` **AND** 被会话 `agentVersion` 通过 `agent_capability_bindings`（`capability_type='localTool'`）绑定 **AND** `agent_versions.tool_policy != 'disabled'`。

> 🔴 **V1.1.5 补注（G-1）**：`tenant_tool_grants` 与 `local_tools` **均无 `deleted_at` 列**（`mcp_tools` 亦无；三表已在 `architecture.md` §13.5.4~§13.5.6 显式豁免软删约定）。撤销授权的唯一判据是 `granted` / `status`，🔴 禁止在任何点查中引用这三张表的 `deleted_at`。
> 🔴 **V1.1.5 补注（G-2）**：上述第 3、4 个条件（绑定、`tool_policy`）在**清单构造期**判定一次并作为本轮生成快照；**每次执行前的点查只复查第 1、2 个条件**（详见 §7.6.3 的 V1.1.5 G-2 裁决框与 AR-019）。

#### 7.7.3 参数校验、风险等级与确认策略

**参数校验**：调用前用 `input_schema` 校验模型给出的 `arguments`；失败 → 🔴 **`30053`**（`tool.status=failed`，`errorCode=30053`），不执行工具、不重试同参；`data`/摘要中只给出失败字段路径（如 `/orderId`），**不回显值**。

**风险等级 × Agent `toolPolicy` 确认矩阵**（PRD §8.7）：

| `agent_versions.tool_policy` | `low` | `medium` | `high` |
|---|---|---|---|
| `disabled` | 不进清单 | 不进清单 | 不进清单 |
| `auto` | 自动执行 | 自动执行 | 🔴 **每次必须确认** |
| `confirm` | 自动执行 | 🔴 必须确认 | 🔴 **每次必须确认** |

> 🔴 `high` 的确认要求**不可被任何策略降级**（PRD §8.7："`high` 每次必须由终端用户确认"）；租户不得自行下调 `risk_level`（该列在平台表，租户无写权限）。
> 🔴 "每次"= 逐次确认：同一会话、同一工具的历史同意**不得**沿用到下一次调用。

**结果约束**：单次结果超 `tool.result_max_bytes`（1MB）→ 截断并标记 `truncated=true`（EX-017）；超时 `timeout_seconds` → `30051`（非幂等工具改判 `30056`）。

**🔴 裁决（G8）本地 Tool 超时 = 计时判定 + 如实上报，**不**强制中断实现体（采纳 @后端 口径）**：

| 项 | 本地 Tool | MCP 工具 |
|---|---|---|
| 超时机制 | 🔴 当前线程 `FutureTask.run()` + 计时判定（**不占用第二个线程**） | `HttpRequest.timeout()`，由 HttpClient **真中断** |
| 实现体长阻塞时 | 🔴 超时**如实上报**（`30051`/`30056`），但判定发生在实现体返回**之后** | 不受影响 |
| 约束来源 | ADR-008 第 8 条「🔴 不新增线程池」—— 强制中断必须有第二个线程去 `cancel(true)` | — |

```
🔴 因此对实现体的硬性纪律（写入 LocalToolHandler 契约注释）：
   必须是**短、纯、不阻塞**的操作：无网络调用、无锁等待、无无界循环、无阻塞 IO。
🔴 一期残余风险**实际为零**：内置清单只有 datetime_now / calculator 两个纯计算工具（§7.7.1 / ADR-015）。
🔴 表达式长度、括号深度等"计算量上界"一律由 local_tools.input_schema 在**入口**约束，
   把"防 CPU 放大"前移到参数校验（30053），而不是依赖超时兜底。
📋 若出现"本地 Tool 可能长阻塞"的真实需求：必须先修订 ADR-008 第 8 条（是否允许一个**有界的**
   工具执行线程池），🔴 严禁在实现层私自起线程绕过该约束（会破坏 §9.5.2 的租户上下文传递纪律）。
```

---

### 7.8 M3：高风险工具逐次确认

#### 7.8.1 通道与状态机（🔴 已裁决，不得另立方案）

```
🔴 不新开 SSE 流：确认交互复用当前生成流 + 一个独立的 HTTP 确认接口。

① 生成线程判定需确认（§7.7.3）→ 落 tool_calls(status=awaiting_confirmation) →
   SSE 下发 tool 事件（status=awaiting_confirmation，含 toolCallId + argsSummary + riskLevel + round）
② 生成线程在**进程内**等待：ConcurrentHashMap<toolCallId, CompletableFuture<Decision>>
   —— 与既有 CancelRegistry 同构（单体单 JVM，ADR-001 单实例前提）
   Redis 仅作跨实例兜底信号：以 tool.confirm_poll_interval_millis 轮询确认标记键
   🔴 若打破 ADR-001（多实例），必须补齐 Redis 信号通道，否则确认可能落在非生成实例而失效
③ 前端调用 §7.8.2 confirm 接口 → 服务端以 tool_calls 行锁（SELECT … FOR UPDATE）流转状态
   → complete 对应 CompletableFuture，唤醒等待中的生成线程
④ 等待上限 sys_config: tool.confirm_wait_seconds（默认 120s）：
   超时按**拒绝**收敛，落 status=timed_out + errorCode=30050，并写审计 action=tool.confirm_timeout
   🔴 等待期间 SSE 心跳必须持续（§5.1），否则连接被中间层掐断
   🔴 **V1.2.2 订正（ADR-017 ③ⓑ）本次实际等待上限 = min(tool.confirm_wait_seconds,
      单次生成剩余预算 − chat.deadline_grace_seconds)**：
      · 原口径把 confirm_wait_seconds 当成**绝对**上限，导致"确认等待可以突破生成总预算"
        → 连接在等待期间被传输层掐断、done 写不出去（BUG-MCP-002 的触发路径）；
      · 🔴 因此确认等待必须在生成预算内，且**必须把实际上限下发前端**：
        SSE tool 帧新增 confirmExpiresInSeconds（§5.2），前端优先用它、缺失才回退 sys_config
        —— 否则倒计时会骗人（显示 120s 而 30s 后即 timed_out）；
      · 🔴 若 剩余预算 − 宽限 ≤ 0 → **根本不下发 awaiting_confirmation 帧**、不落 awaiting 状态，
        本次生成直接 error(50002) + done(finishReason=timeout)（绝不发一张必然超时的确认卡）；
      · 🔴 收敛语义不变：等待超时仍是 timed_out + 30050 + tool.confirm_timeout 审计
        （🔴 零新增状态、零新增错误码、零新增 audit action）。
⑤ 拒绝/超时后是否继续生成由 Agent 策略决定：
   - 模型可在无工具结果下继续 → 回灌"工具被拒绝"的 role=tool 消息，继续 delta，最终 done(stop)
   - 模型无法继续 → error(30050) + done(tool_denied)
```

**`tool_calls.status` 状态机（枚举全列）**：

| 状态 | 进入条件 | 可迁移到 |
|---|---|---|
| `pending` | 模型请求调用，已落库待校验 | `awaiting_confirmation` / `running` / `denied` / `failed` / `cancelled` |
| `awaiting_confirmation` | 需确认（§7.7.3） | `running`（allow） / `denied`（deny） / `timed_out`（等待超时） / `cancelled`（用户停止生成） |
| `running` | 已允许且开始执行 | `succeeded` / `failed` / `timed_out` / `cancelled` / 🔴 **`denied`**（执行期竞态，见下方 ③ 裁决） |
| `succeeded` | 执行成功（含结果被截断） | 终态 |
| `failed` | 执行失败（`30052`/`30053`/`30057`/`50003`） | 终态 |
| `timed_out` | 确认等待超时（`errorCode=30050`）**或** 执行超时（`errorCode=30051`/`30056`） | 终态 |
| `cancelled` | 用户 `POST /messages/{id}/stop` 或会话被删除（EX-022） | 终态 |
| `denied` | 用户拒绝 / 未授权 / 未绑定 / SSRF 拒绝（`errorCode=30050`）；🔴 亦可由 `running` 因执行期竞态进入 | 终态 |

> 🔴 `timed_out` 的两种语义由 `errorCode` 区分：`30050` = **确认等待超时**（语义等同拒绝）；`30051`/`30056` = **执行超时**。§7.8.2 的冲突判定依赖该区分。

**🔴 裁决（③）执行期竞态：增补 `running → denied` 迁移（🔴 修正 @后端 的 `failed + 30052` 归一化口径）**

```
场景（真实可达，不是理论竞态）：确认/授权校验通过 → 行已置 running → 执行器发起调用时
DBA 刚好改库（撤销 mcp_tools.granted / 把 endpoint 改成内网地址），运行时兜底判定拒绝。
🔴 该竞态是**架构必然**，不是实现瑕疵：§7.6.3 第 4 步的 SSRF 重校验发生在
   architecture.md §9.5.1 的 `tool_calls → running` **之后**（先置 running 再执行）。

裁决：❌ 不采纳 failed + 30052；✅ 在状态机增补 running → denied（errorCode=30050）。

理由：
① 🔴 语义不能失真：撤授权 = 授权拒绝、改内网地址 = SSRF 拒绝，二者都是**安全拒绝**，
   errorCode 恒为 30050。归一化成 30052（MCP_UNAVAILABLE = 连接/传输/协议/鉴权/不可用）
   会把**安全事件**伪装成**上游故障** —— DBA 看到 30052 会去查网络，永远查不到"是我撤了授权"；
② 🔴 该迁移**不改动** §7.11.1 的聚合口径：互斥不变量是按 **status** 二分的
   （status='denied' → 只计 toolDeniedCount），新增一条**进入** denied 的路径
   不改变任何聚合公式，也不产生重复计数。@后端 担心的"会改聚合口径"实际不成立；
   反而归一化成 failed 才会让"被撤授权的调用"错计进 toolFailedCount（口径失真）；
③ 审计照旧、零新增 action：撤授权/未绑定 → tool.grant_denied；
   SSRF 拒绝 → mcp.ssrf_rejected（§7.6.3 第 4 步既有强制审计），两者均在
   「tool_calls 状态流转 + 审计」同一独立短事务内（§7.14 第二行）。

🔴 实现要点（@后端）：
① 状态流转仍以 tool_calls 行锁为唯一裁决点；running → denied 与 running → failed 同构，
   只是 errorCode=30050 且必须写对应审计；
② SSE 照常逐帧下发 tool(denied, errorCode=30050)（前端无需改动，30050 文案已存在）；
③ 🔴 confirm 接口的冲突判定必须能区分"用户拒绝导致的 denied"与"执行期竞态导致的 denied"
   —— 依据是 tool_calls.decision 列（已存在，architecture.md §13.5.7），见 §7.8.2 矩阵；
④ 30052 仅保留其原义（连接/传输/协议/上游鉴权失败），🔴 不得再作为竞态兜底码。
```

#### 7.8.2 提交确认决定

**POST** `/api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm`
**权限**：`@Permission(PermissionEnum.USER)` + **本人**（校验 `messageId` 所属会话的 `tenant_id + uid`；非本人/跨租户 → `10004`）
**租户上下文**：必需
**请求头**：`authorization`；🔴 **不使用 `Idempotency-Key`**（§1.4；即便携带也忽略）

| 路径参数 | 类型 | 说明 |
|---|---|---|
| `messageId` | string | 正在生成的 assistant 消息 ID（取自 SSE `meta.messageId`） |
| `toolCallId` | string | 工具调用 ID（取自 SSE `tool.toolCallId`）；🔴 必须隶属该 `messageId`，否则 `10004` |

| 字段（Body） | 类型 | 必填 | 约束 | 说明 |
|---|---|---|---|---|
| `decision` | string | 是 | 枚举 `allow` \| `deny`（其他值 → `10001`） | 用户决定 |
| `reason` | string | 否 | ≤200 字符 | 拒绝原因；🔴 写入审计，禁含敏感值 |

**成功响应**：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "toolCallId": "9001",
    "messageId": "5002",
    "decision": "allow",
    "status": "running",
    "decidedAt": "2026-08-13T02:20:11.000Z",
    "replayed": false,
    "auditEventId": "7f1c9a40e6a14b3d8f27c095b1de73a2"
  },
  "timestamp": 1704067200000
}
```

**幂等与冲突判定（🔴 行锁内执行，@测试 据此断言）**：

🔴 **判定基准（V1.1.3 明确）**：冲突与否**以 `tool_calls.decision` 列（`allow`/`deny`/`NULL`，列已存在）为基准**，而不是只看 `status` —— 因为 `denied` 自 V1.1.3 起有**三种来源**（用户拒绝 / 用户已允许但执行期竞态被拒 / 从未要求确认的授权拒绝），只看 `status` 会把"用户重试自己的 allow"误判成 `30055`。

| 当前 `status`（及 `errorCode` / `decision`） | 提交 `allow` | 提交 `deny` |
|---|---|---|
| `awaiting_confirmation`（`decision=NULL`） | `code=0`，→ `running`，唤醒生成线程 | `code=0`，→ `denied`(30050)，唤醒生成线程 |
| `running` / `succeeded` / `failed`（`decision='allow'`） | `code=0`，`replayed=true`，回放当前状态 | 🔴 `30055`（相反决定） |
| `timed_out`（`errorCode=30051`/`30056`，执行超时，`decision='allow'`） | `code=0`，`replayed=true` | 🔴 `30055` |
| `denied`（`errorCode=30050`，`decision='deny'` —— **用户拒绝**） | 🔴 `30055` | `code=0`，`replayed=true` |
| 🔴 `denied`（`errorCode=30050`，`decision='allow'` —— **执行期竞态被拒**，§7.8.1 ③） | `code=0`，`replayed=true`（用户原决定即 `allow`，🔴 **不是**冲突） | 🔴 `30055` |
| 🔴 `denied`（`errorCode=30050`，`decision=NULL` —— 未授权/未绑定/SSRF，**从未要求确认**） | `10004`（无待确认的工具调用） | `10004` |
| `timed_out`（`errorCode=30050`，确认超时 = 已按拒绝收敛，`decision=NULL`） | 🔴 `30055` | `code=0`，`replayed=true` |
| `cancelled` | `code=0`，`replayed=true`（生成已终止，决定不再生效） | `code=0`，`replayed=true` |
| `pending`（尚未要求确认 / 低风险自动执行） | `10004`（无待确认的工具调用） | `10004` |

**审计（强制，AC-TOL-002 / AC-AUD-003）**：`allow` → `action=tool.confirm_allowed`；`deny` → `action=tool.confirm_denied`；等待超时（由生成线程写）→ `action=tool.confirm_timeout`；🔴 决定冲突（`30055`）→ `action=tool.confirm_conflict`（V1.1.3 新增，见下方 ⑤ 裁决）。审计与 `tool_calls` 状态流转**同一短事务**；审计失败 → 该次工具调用判 `denied`，接口返回 `50003`（EX-024），🔴 但**绝不中断 SSE 流**（§7.14）。

**🔴 裁决（④）回放（`replayed=true`）🔴 不写审计（修正 @后端 的"回放也写同 action + `replayed:` 前缀"口径）**

```
裁决：❌ 不保留回放审计；✅ 收窄为「**仅首次决定**写 audit_logs」。

理由：
① 🔴 审计承载的是**安全事实**（"用户在某时刻做出了某决定"）。回放**不产生新的安全事实** ——
   服务端状态一个字节都没变，写进去的是"客户端又问了一次"，那是访问日志的职责，不是审计的；
② 🔴 会被前端重试放大成审计洪水（Boss 关注点，实测可达）：confirm 接口
   **不使用 Idempotency-Key**（§1.4），而多标签页 / 网络抖动重试 / 用户连点确认按钮
   都会产生重复提交，且 §7.8.2 对回放返回 code=0 —— 前端会认为"成功"而不做抑制。
   一次高风险确认可能落几十条同 action 审计行，把真正的越权与拒绝事件淹没
   （与 §7.7.1「low 风险纯函数成功执行不写审计」同一条抗噪原则，
    索引检索目标见 architecture.md §13.5.1 的 idx_action_time）；
③ 可追溯性无损：首次决定的审计行 + tool_calls 的 decision / decided_by_uid / decided_at
   （列已存在）已完整回答"谁在何时决定了什么"，回放不增加任何信息量。

🔴 配套契约（避免实现分叉）：
① replayed=true 时 🔴 `data.auditEventId` 恒为 **null**
   —— 🔴 禁止编造新 ID，也不要求回查首次审计行（tool_calls 无 audit_event_id 列，
   为回放多做一次 audit_logs 查询属无谓开销）；@前端 与 @测试 不得依赖回放响应的 auditEventId；
② 首次决定（replayed=false）的 auditEventId 仍为 32 位 hex 且**必须**返回（§7.14 不变量 5）；
③ WARN 日志可保留（含 requestId + toolCallId），🔴 但不得落 audit_logs。
```

**🔴 裁决（⑤）`30055` 冲突**必须**留痕：新增 audit action `tool.confirm_conflict`**

```
裁决：✅ 采纳新增 audit action（🔴 不接受"明确不留痕"）。

理由：
① 「用户先 allow 又 deny（或反之）」是**安全相关行为**，而且恰好发生在**高风险工具**这条
   最需要留痕的链路上：可能是多标签页/前端缺陷，也可能是有人试图**翻转一个已生效的高风险决定**
   （例如已 allow 并执行成功后再提交 deny，试图制造"我没批准过"的抗辩）。不留痕等于放弃举证能力；
② 与 ④ 不矛盾：④ 去掉的是**无信息量的回放**，⑤ 记录的是**有信息量的异常**（决定被翻转），
   两者叠加后审计总量**下降**而非上升；30055 是异常路径，天然低频。

契约（同步已写入 §7.14 与 architecture.md §11.1.1 两处枚举表）：
| 项 | 取值 |
|---|---|
| action | `tool.confirm_conflict` |
| actor_type / actor_id | `endUser` / 当前 uid |
| object_type / object_id | `toolCall` / `toolCallId` |
| result | `denied`（请求被拒绝） |
| error_code | `30055` |
| before_digest / after_digest | 既有决定（`allow`/`deny`）/ 被拒绝的提交值（🔴 二者均为枚举字面量，非敏感值，可原样记） |
| reason | 取入参 `reason`（≤200，过 `LogScrubber`），缺省记 `"conflictingDecision"` |
| 事务边界 | 🔴 **独立短事务**（V1.1.4 订正，#5 裁决）：审计以**独立于 confirm 主请求事务**的短事务提交，提交完成后再抛 `30055` |

🔴 防刷（不新增表、不改 DDL）：同一 `toolCallId` 🔴 **至多写一条** conflict 审计 ——
   行锁内先做一次点查 `object_type='toolCall' AND object_id={toolCallId} AND action='tool.confirm_conflict'`
   （`idx_object(object_type, object_id)` 支撑，architecture.md §13.5.1；🔴 `audit_logs` 是平台表，
   租户维度必须手写 `where tenant_id`），已存在则**跳过写入但仍返回 30055**。
   理由：冲突事实"发生过"即已完成留痕，重复刷同一冲突不增加信息量，却是最容易被放大的路径。
🔴 该路径**不改动** tool_calls 任何列（状态已是终态）；审计写入失败 → 返回 `50003`，
   🔴 但**不得**把已终态的行改成 denied（那会篡改历史，且破坏 §7.11.1 聚合）。
```

**🔴 订正（#5，V1.1.4）事务边界由"confirm 请求短事务"改为"独立短事务"—— 文档描述与物理现实冲突，必须改文档**

```
事实（@后端 实测确认，实得 0 条审计）：该路径**必然**以抛 BusinessException(30055) 收尾，
若审计与 confirm 请求同一事务，Spring 的异常回滚会把审计行一并回滚 ——
"必须留痕"的 ⑤ 裁决在物理上恒为 0 条，契约自我否定。

🔴 裁决：✅ **追认 @后端 的独立短事务实现**（`REQUIRES_NEW` 或等价的独立事务模板），
   并已回写上表"事务边界"行。
① 该路径 🔴 **零业务写入**（⑤ 已规定"不改动 tool_calls 任何列"），因此**不存在原子性需求** ——
   §7.14 第一行"审计与业务同一事务"的立意是"不得执行了业务却没审计"，
   本路径没有业务动作可与审计绑定，故不适用；
② 顺序固定：🔴 **先提交审计短事务，再抛 30055**（反序会重现回滚问题）；
③ 审计写入失败 → 返回 `50003`（不是 30055），🔴 且仍**不得**改动已终态的 tool_calls 行；
④ 与去重的关系：点查去重仍在**行锁内**执行（`SELECT … FOR UPDATE` 持有期间），
   审计短事务在锁内提交 —— 保证"至多一条"在并发提交下仍成立；
⑤ @测试 的断言口径不变：提交相反 decision → 30055 且 audit_logs 恰好 1 行
   （连续 3 次仍为 1 行）。🔴 本订正**只改事务实现口径，不改任何对外可观测行为**。
🔴 该例外**只授予本路径**：其余非流式安全操作一律维持 §7.14 第一行的同事务规格
   （判据：该路径是否有业务写入需要与审计原子绑定）。
```

**错误码**：`10001`（`decision` 非法 / `reason` 超长）、`10003`（成员被禁用）、`10004`（消息或工具调用不存在 / 非本人 / 跨租户 / 无待确认调用）、`20001~20005`、`30010/30011`、`30055`（决定冲突）、`50003`（审计写入失败）

> 🔴 前端约束：`tool`(awaiting_confirmation) 到达后展示确认卡片，倒计时取 `sys_config: tool.confirm_wait_seconds`（禁止前端硬编码 120）；收到 `30055` 时**不重试**，直接按服务端最新 `tool` 帧刷新卡片。

---

### 7.9 M3：工具调用可追溯

#### 7.9.1 会话工具调用列表

**GET** `/api/v1/conversations/{conversationId}/tool-calls`
**权限**：`@Permission(PermissionEnum.USER)` + 本人
**租户上下文**：必需
**排序**：`created_at ASC, id ASC`（稳定分页，无重复无遗漏）

| 参数（Query） | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` / `pageSize` | number | 否 | 见 §1.3（`business.page_size_default` / `page_size_max`） |
| `messageId` | string | 否 | 仅返回某条 assistant 消息的工具调用 |
| `status` | string | 否 | 逗号分隔的状态过滤，取值必须属于 §7.8.1 枚举，否则 `10001` |

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "list": [
      {
        "toolCallId": "9001",
        "messageId": "5002",
        "round": 1,
        "toolType": "local",
        "toolKey": "order_refund",
        "toolName": "订单退款",
        "riskLevel": "high",
        "status": "succeeded",
        "errorCode": null,
        "requiresConfirmation": true,
        "decision": "allow",
        "decidedAt": "2026-08-13T02:20:11.000Z",
        "argsSummary": "orderId=A***23, amount=***",
        "resultSummary": "{\"refundId\":\"R***\",\"state\":\"submitted\"}",
        "truncated": false,
        "startedAt": "2026-08-13T02:20:11.200Z",
        "finishedAt": "2026-08-13T02:20:12.400Z",
        "durationMs": 1200
      }
    ],
    "total": 1, "page": 1, "pageSize": 20
  },
  "timestamp": 1704067200000
}
```

**🔴 字段禁含清单（AC-CHAT-007 / PRD §4 第 15 条）**：

```
凭据 / 密钥 / Token / IV / 密文任何片段
MCP endpoint、内部 IP、端口、上游原始错误正文与堆栈
消息正文（user/assistant/tool 的 content）、systemPrompt、Skill 正文
完整工具入参与完整结果（🔴 只允许 §5.4.3 脱敏摘要，四处复用同一份摘要）
其他租户 / 其他用户的任何标识与存在性
decidedByUid 等他人身份（仅本人会话，故只在需要时给出当前用户自身 uid，默认不返回）
```

**状态枚举全列**：`pending` \| `awaiting_confirmation` \| `running` \| `succeeded` \| `failed` \| `timed_out` \| `cancelled` \| `denied`

**错误码**：`10001`（分页/状态参数非法）、`10004`（会话不存在/非本人/跨租户）、`20001~20005`、`10003`、`30010/30011`、`50003`

---

### 7.10 M3：产品埋点上报

#### 7.10.1 批量上报

**POST** `/api/v1/events`
**权限**：`@Permission(PermissionEnum.NO)`（🔴 支持匿名事件，如 `tenantSiteView`）
**租户上下文**：必需（按 Host 建立；🔴 请求体内 `tenantId` **一律忽略**并记安全日志，EX-003）
**幂等**：`clientEventId` + `uk(tenant_id, client_event_id)`（§1.4）

| 字段（Body） | 类型 | 必填 | 约束 |
|---|---|---|---|
| `events` | array | 是 | 1 ~ `sys_config: observability.analytics_batch_max`（50）；空数组或超限 → `10001` |

**事件字段白名单（🔴 仅以下字段被接收，未列出的字段静默丢弃）**：

| 字段 | 类型 | 必填 | 约束 / 说明 |
|---|---|---|---|
| `clientEventId` | string | 是 | `^[A-Za-z0-9_-]{8,64}$`；去重键，客户端生成 |
| `eventName` | string | 是 | 必须命中 `observability.analytics_allowed_events` 白名单，否则整条计入 `discarded` |
| `occurredAt` | string | 是 | ISO-8601 UTC；与服务端时间偏差 >24h → 整条 `discarded` |
| `loginState` | string | 否 | `anonymous` \| `logged_in` |
| `configVersion` | number | 否 | ≥0 |
| `conversationId` | string | 否 | 🔴 必须属于当前租户 + 当前 uid，否则**置空**（不报错、不泄露存在性） |
| `agentId` | string | 否 | 同租户校验，不通过则置空 |
| `agentVersion` | number | 否 | ≥0 |
| `toolType` | string | 否 | `local` \| `mcp` |
| `toolKey` | string | 否 | ≤64 |
| `status` | string | 否 | ≤32，须为本文已定义枚举字面量 |
| `result` | string | 否 | `success` \| `failed` \| `denied` |
| `errorCode` | number | 否 | 🔴 必须是 §2.2 已登记码，否则置空 |
| `durationMs` / `latencyMs` | number | 否 | 0 ~ 600000 |
| `charCount` | number | 否 | ≥0；🔴 只允许长度，禁止正文 |
| `tokenUsage` | object | 否 | `{ promptTokens, completionTokens, totalTokens }`，均为 number |
| `source` | string | 否 | ≤32，如 `newChat` / `agentSelector` |
| `pagePath` | string | 否 | 🔴 仅站内 path，服务端强制去除 query 与 hash（防 Token 经 URL 泄露，AC-AUTH-002） |
| `action` | string | 否 | ≤32，如 `login` / `register` |

`observability.analytics_allowed_events` 默认值（PRD §15.2）：

```json
["tenantSiteView","authClick","authResult","agentSelect","conversationCreate","messageSend","messageFirstToken","messageComplete","toolCallResult","conversationRename","conversationDelete"]
```

**🔴 禁止字段（出现即整条事件 `discarded`，并记安全日志，不回显命中细节）**：

```
messageContent / content / text / prompt / systemPrompt / skillInstruction / instruction
token / authorization / jwt / credential / apiKey / secret / password
phone / mobile / email / idCard / bankCard
工具输入输出正文、完整手机号、完整邮箱、完整身份证/银行卡号
🔴 判定方式：键名不区分大小写的子串匹配 + 值级正则（手机号/邮箱/长数字），任一命中即丢弃整条
```

**匿名事件处理**：未携带有效 `authorization` 时 → `uid` 存 `NULL`，`loginState` 强制 `anonymous`；🔴 不得写入任何可识别个人的字段；`observability.analytics_anonymous_enabled=false` 时匿名事件全部计入 `discarded`。

**采样与开关**（🔴 V1.1.4 订正，#1 裁决：读取侧改 **fail-closed**）：

```
1. observability.analytics_enabled=false → 全部丢弃，返回 code=0 + accepted=0（🔴 不报错，前端无需感知）
2. observability.analytics_sample_rate（[0.0, 1.0]）→ 以 hash(clientEventId) 稳定采样，
   🔴 同一 clientEventId 多次上报的采样判定必须一致（否则去重与采样互相打脸）
3. 采样丢弃计入 discarded，不计入 duplicated
4. 🔴 两键均已在 §7.1.2 正式登记并纳入 StartupChecker.REQUIRED_CONFIG（缺键即启动失败），
   因此运行期"缺行"在正常部署下不可能出现（键总数 25）
5. 🔴 读取侧兜底一律 fail-closed（与 @后端 现行 fail-open 相反，见下方裁决）：
   analytics_enabled 缺失/不可解析 → 按 false（全丢弃）
   analytics_sample_rate 缺失/不可解析/越界 → 按 0.0（全丢弃）
   两种情形均记 ERROR（不是 WARN），响应仍 code=0 且 discarded 计入实际丢弃条数
```

**🔴 裁决（#1，V1.1.4）埋点开关与采样率：正式登记 + 纳入 `REQUIRED_CONFIG` + 读取侧 fail-closed**

```
① 是否登记？✅ **必须登记**。§7.10.1 的「总开关 + 稳定采样」是**强依赖**这两键的正式契约，
   而它们此前既不在 §7.1.2、也不在 architecture.md §13.3/§13.6，库内亦无行 ——
   这是登记缺口（原注把它们写成"沿用既有键"是**误认定**，本版已订正）。
   👉 §7.1.2 键总数 23 → **25**。

② 是否纳入 REQUIRED_CONFIG？✅ **纳入**（缺键即启动失败）。
   @后端 "契约锁定 23 键故不纳入"的顾虑不成立：23 这个数字是**契约的产物**，
   不是契约的约束 —— 契约由我维护，缺键就补登记，而不是让实现去迁就一个过期的计数。
   🔴 且 §13.6 纪律 4 已明令"业务参数禁止用代码默认值兜底"，
   把两键留在 REQUIRED_CONFIG 之外 = 给它们开了一个纪律豁免口。

③ 🔴 fail-open 还是 fail-closed？**fail-closed**（明确否决 @后端 的 fail-open + WARN）。
   这是本项最关键的判断，理由如下：
   ⓐ 🔴 **隐私纪律不允许 fail-open**：PRD §15.2 与本节的立意是"采集受控、可关停、可降量"。
      fail-open 意味着「配置一旦丢失就默默转为全量采集」—— 恰好把**唯一的关停手段**
      变成"失效即最大化采集"。这与 §15.2「禁记敏感信息、采样可控」直接冲突：
      运维原本按 sample_rate=0.05 采 5%，某次误删配置行后系统静默转为 100%，
      且只有一条 WARN —— 没人会因为 WARN 去看日志，直到数据量异常才发现。
   ⓑ 🔴 fail-closed 的代价**可接受且可观测**：埋点丢失 = 分析数据缺一段，
      不影响任何用户可见功能（本接口设计上就"永不影响主流程"，一律 code=0）；
      而 fail-open 的代价 = 超范围采集用户行为数据，属**不可撤销**的隐私事实
      （已落库的行删不掉"曾经采集过"这件事）。
      🔴 判据：可恢复的功能损失 < 不可撤销的隐私损失。
   ⓒ 与 ratelimit 的"Redis 不可用即放行"（§7.12）不冲突：那里 fail-open 的对象是
      **保护措施**（放行只是少了一层保护，不产生新的数据副作用）；
      这里 fail-open 的对象是**采集行为本身**（放行会主动产生新数据）。
      🔴 一般原则：**"要不要拦"的开关可 fail-open，"要不要采/写"的开关必须 fail-closed。**
   ⓓ 有了 ② 的 REQUIRED_CONFIG 兜底后，该分支在正常部署下**不可达** ——
      fail-closed 只是"万一"时的方向选择，成本为零。

④ 日志级别：🔴 由 WARN 升为 **ERROR**（配置缺失导致埋点整体停摆属需要人介入的事件）。
```

**成功响应**（🔴 埋点失败**永不影响主流程**，一律 `code=0`）：

```json
{
  "code": 0,
  "message": "success",
  "data": { "accepted": 8, "duplicated": 1, "discarded": 1 },
  "timestamp": 1704067200000
}
```

**错误码**：`10001`（`events` 为空 / 超过批量上限 / 缺 `clientEventId` 或 `eventName`）、`30010/30011`（未知或暂停租户）、`50003`

---

### 7.11 M3：租户用量与运行指标

#### 7.11.1 用量查询

**GET** `/api/v1/admin/metrics/usage`
**权限**：`@Permission(PermissionEnum.USER)` + `@TenantRole({TENANT_ADMIN, TENANT_OPERATOR})`
**租户上下文**：必需（🔴 只聚合当前租户，AC-TEN-005）
**说明**：一期无管理 UI，仅供接口实测与数据核验

| 参数（Query） | 类型 | 必填 | 约束 |
|---|---|---|---|
| `from` | string | 是 | ISO-8601 UTC |
| `to` | string | 是 | ISO-8601 UTC；`to > from` 且跨度 ≤31 天，否则 `10001` |
| `granularity` | string | 否 | `day`（默认） \| `hour` |

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "from": "2026-08-01T00:00:00.000Z",
    "to": "2026-08-13T00:00:00.000Z",
    "granularity": "day",
    "series": [
      {
        "bucket": "2026-08-12T00:00:00.000Z",
        "messageCount": 320,
        "conversationCount": 44,
        "activeUserCount": 31,
        "tokenUsage": { "promptTokens": 41000, "completionTokens": 98000, "totalTokens": 139000 },
        "toolCallCount": 26,
        "toolFailedCount": 2,
        "toolDeniedCount": 1,
        "rateLimitedCount": 0
      }
    ],
    "total": {
      "messageCount": 320, "conversationCount": 44, "activeUserCount": 31,
      "tokenUsage": { "promptTokens": 41000, "completionTokens": 98000, "totalTokens": 139000 },
      "toolCallCount": 26, "toolFailedCount": 2, "toolDeniedCount": 1, "rateLimitedCount": 0
    }
  },
  "timestamp": 1704067200000
}
```

**🔴 字段聚合口径（V1.1.1 回写，与 `architecture.md` §13.5.7 一致；@测试 据此断言，@后端 禁止另立算法）**：

| 字段 | 数据源 | 口径（SQL 语义） |
|---|---|---|
| `messageCount` | `messages` | 当前租户、时间桶内 `role='user'` 与 `role='assistant'` 的 `isCurrent=true` 行；🔴 不含 `role='system'`/`role='tool'` |
| `conversationCount` | `conversations` | 时间桶内**新建**会话数（按 `created_at` 落桶），非活跃会话数 |
| `activeUserCount` | `messages` | 时间桶内产生过消息的 `distinct uid` |
| `tokenUsage` | `messages` | `assistant` 消息 `token_usage` 三项分别求和；`NULL` 视为 0 |
| `toolCallCount` | `tool_calls` | 🔴 **全部行**（含未进入执行的 `pending`/`denied`），即"模型请求调用工具的总次数" |
| `toolDeniedCount` | `tool_calls` | 🔴 `status='denied'` **OR**（`status='timed_out'` **AND** `error_code=30050`）—— 确认等待超时按 §7.8.1 "语义等同拒绝"归入 denied |
| `toolFailedCount` | `tool_calls` | 🔴 **其余失败态**：`status='failed'`（`error_code` ∈ `30052`/`30053`/`30057`/`50003`）**OR**（`status='timed_out'` **AND** `error_code` ∈ `30051`/`30056`，即执行超时） |
| `rateLimitedCount` | — | 🔴 **一期恒返回 `0`**，见下方说明 |

```
🔴 denied / failed 的互斥不变量（杜绝重复计数或漏计）：
1. status='timed_out' 的行按 error_code 二分且仅二分：
   error_code=30050        → 计入 toolDeniedCount（确认等待超时 = 拒绝语义）
   error_code=30051/30056  → 计入 toolFailedCount（执行超时）
2. status='denied'      → 只计 toolDeniedCount
   status='failed'      → 只计 toolFailedCount
   status='succeeded'   → 两者都不计（含 truncated=true 的成功）
   status='cancelled'   → 🔴 两者都不计（用户主动停止，不是失败也不是拒绝）
   status='pending' / 'awaiting_confirmation' / 'running' → 两者都不计（非终态；
     🔴 architecture.md §9.5.4 保证流结束时 ToolCallRecorder 会在 finally 内把残留行收敛为 cancelled，
     故正常情况下历史区间内不应存在非终态行，若聚合查到即为实现缺陷）
3. ✅ 判据：toolDeniedCount + toolFailedCount + succeeded + cancelled + 非终态残留 = toolCallCount
   任一行被两个计数同时命中，即为缺陷。
4. 🔴 V1.1.3 补注（③ 裁决，**聚合公式不变**）：状态机新增 running → denied 迁移
   （执行期竞态：preflight 通过后 DBA 撤授权 / 改内网地址，§7.8.1）。
   该行 status='denied' 且 error_code=30050 → 按上述第 2 条**只计 toolDeniedCount**。
   🔴 本条不改动任何 SQL 与口径，仅说明"denied 的来源多了一条路径"；
   反之若按 @后端 原口径归一化为 failed+30052，被撤授权的调用会错计进 toolFailedCount（口径失真）。
```

**🔴 `rateLimitedCount` 一期口径（V1.1.1 回写，与 `architecture.md` §13.5.8 一致）**：

```
一期 **恒返回 0**，且 🔴 严禁伪造、严禁估算、严禁用其他指标近似替代。

原因（不是偷懒，是当前确实无持久化数据源）：
① 限流仅在 Redis 固定窗口计数（§7.12），🔴 不落任何数据库表；
② 限流发生在**创建消息之前**，因此 messages / tool_calls 中根本没有对应行可聚合；
③ observability.analytics_allowed_events 白名单（§7.10.1）中也没有任何限流相关事件名，
   🔴 @前端 不得自造事件名上报（未命中白名单会整条 discarded，属无效实现）。

字段保留在响应中的理由：契约形态提前冻结，二期补数据源时前端与 @测试 无需改结构。
```

**📋 二期决策项（🔴 Boss 裁决后由 @架构师 升级本节契约，一期不得提前实现）**：

| 方案 | 做法 | 代价 |
|---|---|---|
| **(a) 前端上报** | `observability.analytics_allowed_events` 新增 `messageRateLimited`，由 @前端 在收到 `10005` 时上报一条埋点；本字段改为聚合 `analytics_events` 中该事件名 | 依赖客户端诚实上报，可被绕过/丢失；但零后端侵入、与既有埋点链路一致 |
| **(b) 后端侧写** | 允许 `MessageRateLimiter` 命中限流时**侧写**一条 `analytics_events`（服务端事件，`uid` 取当前请求，`event_name='messageRateLimited'`） | 数据可信、不可绕过；但需放宽"埋点只由客户端上报"的现有约束，且在限流热路径上增加一次 DB 写入（需评估对 §14.1 的影响） |

> 🔴 二期启动前 **禁止**：自造 `sys_config` 键、自造事件名、把 `rateLimitedCount` 改成非 0 的估算值。三者任一出现即为缺陷。

> 🔴 只返回**聚合计数**：禁止返回消息正文、单条明细、uid 列表、其他租户数据。
> `data` 不是分页结构（固定时间序列），故不套 `{list,total,page,pageSize}`；`total` 为区间合计对象。

**错误码**：`10001`、`10003`、`20001~20005`、`30010/30011`、`50003`

---

### 7.12 M3：限流口径（`10005`）

| 项 | 约定 |
|---|---|
| 生效接口 | §4.6.1 发送消息、§4.6.3 重新生成（沿用现有 `MessageRateLimiter`，不新增组件） |
| 算法 | Redis 固定窗口 + Lua 原子计数；键含 `tenantId + uid`，🔴 单租户/单用户行为不占用他人额度（AC-LMT-001） |
| 阈值 | 🔴 **V1.2.5 订正（ADR-020）**：阈值**不再由 `MessageRateLimiter` 自行读配置**，而由 `quota/QuotaPolicyResolver` 解析出的**有效策略**（平台默认 `ratelimit.message_per_minute`=3 + 租户覆盖 `tenant_quota_policies.qpm_limit`）作为**入参**传入；`qpm_enabled=false` 时**根本不调用**限流器。🔴 代码中禁止出现 `3` / `30` 字面量。🔴 **为什么要上移**：QPM 与日限额必须来自**同一次策略解析**，否则会出现"QPM 用平台默认、日限额用租户覆盖"的错配（§7.15.4） |
| 🔴 小时窗（**已废除**） | 🔴 **V1.2.5 起不存在小时窗业务规则**（PRD V1.4 §8.11.9）：`ratelimit.message_per_hour` 键已删行、代码分支已移除。@测试 必做**反向断言**：在 QPM 放宽（如覆盖为 200）时连续发起 >120 次准入**不得**出现任何限流拒绝（AC-LMT-005） |
| 未建流之前触发 | HTTP 200 + `application/json`：`code=10005`，🔴 `data.retryAfterSeconds`（number，≥1）**必填** ← 🔴 **一期唯一可达路径，@测试 必测**。<br>🔴 **V1.2.7 补注（BUG-QUOTA-001）**：本形态与请求 `Accept` 头 **完全无关**（§1.2.1 不变量 ①）—— 真实浏览器发 SSE 请求时恒带 `Accept: text/event-stream`，此时**仍必须**是 `200 + application/json`。🔴 实测得到 **HTTP 500 + 空体**即为缺陷（根因是异常响应未绕过内容协商，裁决见 `architecture.md` ADR-021）；🔴 该缺陷**不是限流专属**，同一路径上的 `30070` / `10001` / `10004` / `20001~20005` / `50003` 全部同源，故复验必须**同时**覆盖至少一个非限流码 |
| 已建流之后触发 | SSE `error` 事件：`{"code":10005,"message":"…","retryAfterSeconds":37}`，随后 `done`(`finishReason=failed`) ← 🔴 **一期不可达（契约预留）**，见下方判定 |
| Redis 不可用 | 🔴 **放行**（限流是保护措施，不得让缓存故障升级为业务不可用），记 WARN。⚠️ **与日限额有意不同**：日限额在 Redis 不可用时**降级为 DB 直判而非放行**（理由见 §7.15.5 / ADR-020 ⑦ —— QPM 丢一分钟窗口只是抗突发能力下降，日额度放行等于当天无限量） |
| 🔴 与日限额的优先级 | 见 **§7.15.3 准入五步顺序**：**日额度只读预检在 QPM 计数之前** → 日额度已用尽时返回 `30070` 且**不增加本次 QPM 计数**；QPM 超限时返回 `10005` 且**不占用日额度** |

**🔴 判定（V1.1.3）SSE 内 `error.retryAfterSeconds`（`10005`）= 契约预留，**不是**契约错误**

```
结论：✅ **契约预留**（字段形态提前冻结），🔴 一期**不可达**，🔴 不删除、不改判为缺陷。

事实认定（与 §7.11.1 rateLimitedCount 恒 0 的三条原因同源）：
① 限流在 §4.6.1 / §4.6.3 的**建流之前**执行（`MessageRateLimiter` 在 Servlet 线程、
   写用户消息与 flush meta 之前），命中即以 application/json 返回 —— 流根本没建立；
② 一期**没有任何流内限流点**：工具调用、轮次推进、回灌均不计入 ratelimit.*；
   因此 §5.2 的 tool.retryAfterSeconds 亦为同类预留（一期恒 null）。

保留字段的理由（同 rateLimitedCount）：二期若引入「生成中途限流」或「工具级限流」，
前端解析与 @测试 断言结构无需改动；删掉再加回属破坏 §5.4.1「字段只增不改不删」硬约束。

🔴 对各角色的明确要求（本条即为避免误判而写）：
- @测试：🔴 **不得**为该路径编写用例，🔴 **不得**因其不可达而判缺陷；
  test-plan 中如已有该用例，标注为 Deferred-in-M3（预留字段，无触发点）。
  仍必须覆盖：未建流路径的 code=10005 + data.retryAfterSeconds ≥1 + 倒计时后可重试。
- @后端：🔴 **不得**为制造可达性而在流内新增任何限流点（那是**新增业务约束**，
  需 @产品经理 立需求 + 回写本节 + 登记新 sys_config 键，属二期）；
  流内 error 事件的 retryAfterSeconds 一期恒 null。
- @前端：解析逻辑保留（容忍 null 与字段缺失，§5.4.1 第 5 条），无改动。
```

**响应示例**（未建流）：

```json
{
  "code": 10005,
  "message": "发送过于频繁，请 37 秒后重试",
  "data": { "retryAfterSeconds": 37 },
  "timestamp": 1704067200000
}
```

> 前端按 `retryAfterSeconds` 倒计时禁用发送，🔴 禁止硬编码等待秒数，也禁止立即自动重试。

---

### 7.13 M3：内置 Mock MCP（仅 `test` profile）

| 项 | 约定 |
|---|---|
| 启用条件 | 🔴 仅 `spring.profiles.active=test` 注册；生产/开发 profile **不存在**该组件（避免成为攻击面） |
| 路径 | `/mock-mcp/streamable-http`、`/mock-mcp/sse`（🔴 **不在** `/api/v1/**` 下，不受业务契约约束，不返回 `Result` 包装） |
| 场景开关 | 请求头 `X-Mock-Scenario`：`success` \| `auth_failed` \| `timeout` \| `protocol_error` \| `oversize_result` \| `new_tool` \| `schema_changed` |
| 覆盖场景 | 成功、鉴权失败、超时、协议异常、非法地址、工具新增默认禁用、超大结果（AC-MCP-006 / EX-030） |
| SSRF 例外 | Mock 为环回地址，需在 `test` profile 将 `mcp.allowed_internal_cidrs` 配为 `["127.0.0.1/32"]` 且 `mcp.require_https=false`；🔴 生产两项配置必须为 `[]` / `true`，否则视为阻断上线的安全配置错误（同 EX-028 规格） |
| **配置覆盖方式（🔴 V1.1.2 新增，G12 裁决）** | 一期**没有独立测试库**（共享 `albedo` 库）。因此上述宽松值 🔴 **只允许**由测试内的 `SysConfigOverride` 在**单个测试方法/类范围内临时覆盖并 `try-finally` 还原**；🔴 **严禁**把宽松值持久化写入共享库的 `sys_config` 行（等于让生产开关长期处于不安全值，且会污染并行运行的其它测试）。生产侧由 `config/StartupChecker.checkProductionBlockers()` 在 `prod` profile **启动即拦死**（`require_https=false` 或 `allowed_internal_cidrs != []` → 启动失败），构成"覆盖不还原"的最终兜底 |
| **覆盖纪律** | 🔴 覆盖必须走 `ConfigService` 的失效路径（改库后 evict L1+L2），否则测试会读到 TTL 内的旧值（D-003/D-006 同类问题）；🔴 测试结束后必须断言配置已还原（建议在共享基类 `@AfterEach` 中校验），避免"上一个测试把库改了、下一个测试莫名失败" |
| 不可替代 | 🔴 Mock 不可用即判 M3 验收失败，**不得**以真实外部 MCP 服务替代（EX-030 / DEC-009） |

**🔴 V1.2.0 新增：异步形态（2024-11-05）Mock 端点（G6′ ⑨ 的验收基础设施）**

| 端点 | 行为 |
|---|---|
| `GET /mock-mcp/sse-legacy` | 🔴 **保持流打开**（`SseEmitter` / `StreamingResponseBody`），先推 `event: endpoint` / `data: /mock-mcp/sse-legacy/messages?sessionId={uuid}&scenario={s}`，随后按场景把 JSON-RPC 结果**异步推送**到该流；🔴 需维护 `sessionId → emitter` 的 `ConcurrentHashMap`（仅 `src/test`） |
| `POST /mock-mcp/sse-legacy/messages` | 🔴 恒返回 **`202 Accepted` + 空体**；把结果写回对应 `sessionId` 的流。`initialize` 回 `result`（`protocolVersion=2024-11-05`）、`notifications/initialized` 不回任何东西 |

| 新增场景（`scenario`） | 行为 | 被断言 |
|---|---|---|
| `success`（默认） | 完整异步链路：`initialize` → `tools/list` / `tools/call` 均从流推送 | `success` / 正常返回 |
| **`never_push`** | POST 恒回 `202`，🔴 **永不向流推送任何结果** | `timeout` / `30051`（🔴 且总耗时 ≤ 预算，不得 2×） |
| **`close_early`** | 收到目标方法 POST 后 🔴 **直接关闭流**，不推结果 | `protocol_incompatible` / `30052`（🔴 **立即失败，不等到超时**） |
| **`oversize_stream`** | 向流推送 > `mcp.sse_stream_max_bytes` 的噪声帧（非匹配 id） | `protocol_incompatible` / `30052` + `[SECURITY]` 日志 |
| **`init_error`** | `initialize` 回 JSON-RPC `error` | `protocol_incompatible` / `30052` |
| **`noise_then_result`** | 先推大量 `notifications/*` / `logging` / `ping` / **id 不匹配**的帧，再推正确结果 | 🔴 `success`（验证"仅接受匹配 id 的报文、其余一律丢弃"） |

> 🔴 **同步形态端点 `/mock-mcp/sse` 与 `/mock-mcp/sse/messages` 行为保持不变**（G6′ ⑨）：现有 `TRANSPORT_SSE` 用例与 AC-MCP-003 必须原样通过。
> ⚠️ 客户端会先发一次 `initialize`，Mock 对未知方法回 `-32601` —— 🔴 该 `error` 是**探测信号**，客户端必须忽略，@测试 不得据此判缺陷。
> 🔴 `/mock-mcp/sse-cross-origin` 行为不变（仍判 `protocol_incompatible`，🔴 不改判 `30050`）。

---

### 7.14 M3：审计与 SSE 的事务边界（🔴 已裁决）

| 场景 | 事务边界 | 审计失败后果 |
|---|---|---|
| **非流式**安全/管理操作：§7.2.1 缓存失效、§7.4.2 连接测试、§7.4.3 工具发现、MCP 凭据变更生效、平台排障票据签发、跨租户资源探测 | 🔴 审计与业务**同一事务** | 🔴 **整体失败**：回滚业务动作，返回 `50003`（EX-024，"不得执行后伪报未审计"） |
| 🔴 **V1.1.4 新增例外**：**零业务写入的拒绝路径** —— 目前**仅** §7.8.2 的 `30055` 决定冲突（`tool.confirm_conflict`） | 🔴 **独立短事务**：先提交审计，再抛业务异常 | 返回 `50003`；🔴 **不得**改动任何已终态业务行 |
| **流式过程中**的安全事件：工具授权被拒、运行时 SSRF 拒绝、高风险确认/拒绝/确认超时 | 🔴 在**工具执行边界**以**独立短事务**写入：`tool_calls` 状态流转 + 审计事件**同事务**提交 | 🔴 该次工具调用失败（`denied` / `failed`，`errorCode=50003`）；**但绝不允许中断整条 SSE 流** —— 流内以 `tool`(终态) → 必要时 `error` → `done` 收敛 |

```
🔴 为什么流式内必须用独立短事务：
① 一次生成可能持续数十秒并跨多轮工具调用，若与生成共享长事务，
   数据库连接会被长时间占用，且任何后续失败都会回滚已成功的工具状态与审计（审计必须不可篡改）
② SSE 已建立后不能改 HTTP 状态或响应体形态，审计失败只能降级为"该次工具调用失败"，
   不能升级为"整条流失败"—— 否则用户已看到的内容与最终态自相矛盾（EX-015 的反向教训）

🔴 不变量：
1. 审计写入表 audit_logs（platform 表，含 tenant_id 列；租户维度查询必须手写 where tenant_id）
2. 字段按 PRD §15.1：eventId / tenantId|scope / requestId / actorType,actorId / action /
   objectType,objectId / beforeDigest,afterDigest / result / reason,errorCode / ip,userAgent / occurredAt
3. 敏感值只记 sha256 前 16 hex 或"已变化"标记，🔴 禁记明文（AC-AUD-002）
4. 审计只写不改不删；一期无查询接口（§6 的 /admin/audit Deferred），验收走数据核验
5. 🔴 eventId 格式 = 32 位 UUID hex（小写、无连字符，^[0-9a-f]{32}$），唯一键 uk_event_id；
   凡接口返回 auditEventId（§7.2.1 / §7.4.2 / §7.8.2）一律**原样返回该 32 位值，禁止截断**
   （V1.1.1 统一口径，与 architecture.md §11.1.2 一致）
6. 🔴 V1.1.3 新增「审计只记新事实」原则（④ 裁决，全文适用）：
   审计写入的触发条件是**服务端状态发生了变化**或**发生了一次被拒绝的安全尝试**；
   幂等回放（未改变任何状态、未产生新决定）🔴 一律不写 audit_logs，只记 WARN 日志。
   👉 直接推论：confirm 的 replayed=true 不写审计且 data.auditEventId 恒为 null（§7.8.2）；
      §7.2.1 缓存失效**不属于**回放（每次调用都真实执行了失效动作，故仍每次审计）。
7. 🔴 V1.1.4 新增「事务边界的选择判据」（#5 裁决，全文适用）：
   问一句「本路径是否存在业务写入需要与审计**原子绑定**」——
   ⓐ 有（缓存失效、连接测试、工具发现、状态机流转…）→ **同一事务**，审计失败即整体失败；
   ⓑ 没有（纯拒绝路径，如 30055 决定冲突）→ **独立短事务**，🔴 顺序必须"先提交审计、再抛异常"，
      否则异常回滚会把"必须留痕"的审计一并抹掉（@后端 实测：同事务实得 0 条）。
   🔴 ⓑ 的适用清单当前**仅** §7.8.2 的 30055；🔴 新增 ⓑ 类路径必须先回写本表再实现，
      严禁以"反正会抛异常"为由把 ⓐ 类路径改成独立事务（那会重新打开"执行了但没审计"的口子）。
```

**审计 `action` 枚举字面量（@测试 据此断言，AC-AUD-003）**：

> 🔴 **V1.1.5（G-0 裁决）本表共 12 个 `action`，是唯一基线**：本表 = `architecture.md` §11.1.1 表 = `audit/AuditActions` 常量集合，三者**逐行一致**（已于本版复核：字面量与顺序完全相同）。
> 🔴 §8.3 F3 原写"11 项"是**数量文字错误**（M3 期间新增 `mcp.tool_grant_revoked`（V1.1.2 G3）与 `tool.confirm_conflict`（V1.1.3 ⑤）后未同步计数），本版已订正为 **12 项**。
> 🔴 @测试 做**精确集合断言**：`audit/AuditActions.ALL`（`Set<String>`，实现侧已存在且写入前强制校验）**== 下表 12 个字面量**（多一个 = 自造 action → 缺陷；少一个 = 漏实现 → 缺陷）。⚠️ 该类中 `REASON_*` / `DIGEST_*` 常量**不是** action，集合断言时不得计入。
> 🔴 新增 action 必须**先回写本表 + `architecture.md` §11.1.1，再实现**，并同步订正 §8.3 F3 的数量。

| action | 触发点 |
|---|---|
| `tool.grant_denied` | 工具未授权 / 未绑定 / 已停用被调用（§7.4.4、§7.7.2） |
| `tool.confirm_allowed` | 用户允许（§7.8.2）；🔴 **仅首次决定**写入，回放（`replayed=true`）不写（④ 裁决） |
| `tool.confirm_denied` | 用户拒绝（§7.8.2）；🔴 **仅首次决定**写入，回放不写（④ 裁决） |
| `tool.confirm_timeout` | 确认等待超时（§7.8.1 ④） |
| **`tool.confirm_conflict`** | 🔴 **V1.1.3 新增（⑤）· 决定冲突**：同一 `toolCallId` 提交与既有决定**相反**的 `decision`（返回 `30055`）。`actorType='endUser'`、`objectType='toolCall'`、`result='denied'`、`errorCode=30055`；🔴 事务边界 = **独立短事务**（V1.1.4 订正 #5：该路径必然抛 `30055`，同事务会被回滚导致恒 0 条；顺序为"先提交审计再抛错"）；🔴 同一 `toolCallId` **至多一条**（行锁内按 `idx_object` 点查去重，防前端重试刷审计）；🔴 不改动 `tool_calls` 任何列 |
| `mcp.ssrf_rejected` | 保存时或运行时 SSRF 拒绝（§7.6.3 步骤 4） |
| **`mcp.tool_grant_revoked`** | 🔴 **V1.1.2 新增（G3）· 系统发起的授权撤销**：工具发现时 `schema_changed` 已授权工具自动降级（`reason='schemaChanged'`）／`removed` 且原 `granted=1` 被置停用（`reason='toolRemoved'`）；`actorType='system'`、`objectType='mcpTool'`、`result='success'`、事务边界同 `discover`（§7.4.3） |
| `mcp.connection_test` | 连接测试（§7.4.2，含成功） |
| `mcp.credential_changed` | 凭据变更首次生效（§7.4.1） |
| `tenant.cross_probe` | 跨租户资源探测（`10004` 且检测到跨租户 ID） |
| `platform.access_grant_issued` | 平台管理员排障票据签发 |
| `platform.cache_evict` | 缓存失效接口调用（§7.2.1，含部分失败） |

> 🔴 **V1.2.5 明确（ADR-020 ⑨）**：用户维度限流与每日限额 **🔴 零新增 audit action（仍恰 12 项）**。
> 理由：QPM 命中、日额度命中与每次结算都是**高频运行事件**而非安全管理动作（PRD §8.11.8.1 已产品裁决），逐次写审计会以数量级噪声淹没 `idx_action_time` 上真正需要人介入的越权事件（同 ADR-015 ④ 的抗噪原则），且不满足"审计只记新事实"中的"被拒绝的**安全**尝试"这一条件（额度用尽是**权益边界**，不是权限边界）。
> 🔴 可追溯性由三处承担：`user_daily_quota_usages` 账本（每日结算数与首/末结算时间）+ `[QUOTA]` 前缀运行日志（含 `tenantId`/`uid`/`limit`/`settled`/`holds`/`quotaDate`/原因）+ `tenant_quota_policies` 的版本行。
> 🔴 **反向纪律**：二期若把额度配置做成**管理端接口**，那时的配置变更**必须**写审计（属治理动作），🔴 但仍不得为"用尽/命中"这类运行事件新增 action。

---

### 7.15 M3.1：用户额度与限流（REQ-LMT-003 / REQ-QUOTA-001~005）

> 🔴 技术决策与备选方案裁决见 `architecture.md` **ADR-020**；数据模型见 §13.5.11 / §13.5.12；配置键见 §7.1.2。
> 🔴 本节与 §7.12（QPM 口径）**互为一体**：QPM 与每日额度是**两个独立概念**（恢复条件相差 5 个数量级），🔴 严禁互相复用错误码、载荷或前端状态。

#### 7.15.1 查询当前用户对话额度

**GET** `/api/v1/me/quota`
**权限**：`@Permission(PermissionEnum.USER)`（命中即惰性建户，与 §4.3.1 同规格）
**租户上下文**：必需
**请求参数**：🔴 **无**（🔴 **禁止**定义 `tenantId` / `uid` / `date` 等任何参数；传入一律**忽略并记安全日志**，EX-003）

**成功响应**：`data` = **额度快照**（§7.15.2，恰 9 键）

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "enabled": true,
    "limit": 50,
    "used": 12,
    "remaining": 38,
    "status": "available",
    "periodStart": "2026-08-13T16:00:00.000Z",
    "resetsAt": "2026-08-14T16:00:00.000Z",
    "timezone": "Asia/Shanghai",
    "asOf": "2026-08-14T03:21:07.412Z"
  },
  "timestamp": 1704067200000
}
```

**错误码**：`20001/20002/20003/20004/20005`（匿名或 Token 失效 —— 🔴 前端匿名态**根本不调用**本接口，但后端**不做**任何"匿名返回 unlimited"的特例，见下方裁决）、`10003`（成员被禁用）、`30010/30011`、`50003`（🔴 含"平台默认额度配置缺失/非法"与"租户 `timezone` 非法"两类，见 §7.15.5）

```
🔴 匿名访问的裁决（Q：401 / 鉴权段码 / 还是返回未启用？）
结论：走**既有 USER 端点的标准路径**（`20001`/`20002`），🔴 不新增任何匿名分支。
理由：① 额度是**受保护的个人运行数据**（PRD §8.11.7），"匿名返回 unlimited" 会让
      未登录页面显示一个与登录后不同的额度语义，等于给匿名用户一个可探测的口径；
   ② 前端 `request.ts` 对 20000~20005 的动作是"清 token + 整页跳 SSO"——
      若匿名态误调本接口，用户会被无故弹去 SSO，因此**前端侧的纪律**是
      🔴 `uid === null` 时不发起请求（K12 反向断言），而不是让后端造一个假响应；
   ③ 与 `/api/v1/me` 完全同规格 → 前端与 @测试 无需为本接口记第二套鉴权口径。
```

#### 7.15.2 额度快照（🔴 唯一形态，恰 9 键，多一键或少一键均为缺陷）

| 字段 | 类型 | 说明 | PRD 信息项 |
|---|---|---|---|
| `enabled` | boolean | 当前**有效策略**下每日限额是否启用（平台默认 + 租户覆盖解析后的结果） | F-QUOTA-001 |
| `limit` | number \| null | 当前有效日总量；`enabled=false` 时**必须** `null`（🔴 禁止伪造数值上限） | F-QUOTA-002 |
| `used` | number | 🔴 **当日已结算次数**（权威来源 = `user_daily_quota_usages.settled_count`）。🔴 **不含在途预占** —— PRD F-QUOTA-003 明文口径；`enabled=false` 时仍返回**真实**已结算数（🔴 不伪造 0） | F-QUOTA-003 |
| `remaining` | number \| null | `enabled=true` 时 = `max(limit − used − 在途预占数, 0)`；`enabled=false` 时 `null` | F-QUOTA-004 |
| `status` | string | `available` \| `exhausted` \| `unlimited`（🔴 与 `enabled`/`remaining` 恒一致：`enabled=false` → `unlimited`；`remaining=0` → `exhausted`） | F-QUOTA-005 |
| `periodStart` | string | 当前额度日起点，ISO-8601 **UTC**（= 租户当地 00:00 对应的 UTC 时刻） | F-QUOTA-006 |
| `resetsAt` | string | 下一个租户当地零点，ISO-8601 **UTC**；🔴 必须晚于 `periodStart` | F-QUOTA-007 |
| `timezone` | string | 当前租户 IANA 时区（取自 `tenants.timezone`） | F-QUOTA-008 |
| `asOf` | string | 快照计算时刻，ISO-8601 UTC | F-QUOTA-009 |

```
🔴 关于 used + remaining 可能 < limit（在途预占窗口内）—— 这是**有意为之**，不得判缺陷：
  used 严格等于"已结算"（PRD 硬口径），而 remaining 采用**含在途预占的保守口径**。
  差额 = 当前该用户在途的生成尝试数（正常为 0 或 1，多标签页可能 >1），
  存续时间 = 预检通过 → 首个资源消耗证据（通常 <5s，上界受 chat.first_token_timeout_seconds 约束）。
  🔴 为什么 remaining 必须保守：若把在途预占算作"还剩着"，用户会看到"还有 1 次"却在点击时
     立刻收到 30070（预占已被自己的另一个标签页拿走）—— 显示一个点不动的按钮比少显示 1 次更糟。
  🔴 反向纪律：@测试 断言 used/remaining 时必须在**无在途生成**的静止态取样（AC-QUOTA-001
     的 used=50/remaining=0/status=exhausted 即静止态断言）。

🔴 不返回的字段（逐条为反向断言，出现即缺陷）：
  ❌ QPM 阈值 / qpmEnabled —— 下发限流阈值等于把策略暴露给客户端，并诱导前端做本地预判（双实现）；
     QPM 的唯一前端输入是命中时的 `10005 + data.retryAfterSeconds`（§7.12）。
  ❌ retryAfterSeconds —— 日额度不是秒级可恢复（K5）。
  ❌ 租户本地时间字符串 / quotaDate —— 只给 UTC 绝对时间 + IANA 时区，
     🔴 展示由前端按 `timezone` 渲染（禁止用浏览器本地时区推算，K10）。
  ❌ 任何其他用户或其他租户的数据、任何消息正文（AC-QUOTA-016）。
```

#### 7.15.3 准入五步顺序（🔴 顺序不可调整；发生在**建流之前**，故拒绝一律 HTTP 200 + JSON）

生效接口：§4.6.1 发送消息、§4.6.3 重新生成。

```
0  既有前置（不变）：requireKey(Idempotency-Key) → normalizeContent → 🔴 幂等回放命中即返回
   （🔴 回放**不计** QPM、**不占**日额度、**不重复结算** —— AC-QUOTA-007）
1  解析有效策略 + 额度窗口   ← 1 次 DB 读（tenant_quota_policies）+ 租户 timezone
     🔴 QPM 与日限额**必须来自同一次解析**（否则会出现"QPM 取平台默认、日额度取租户覆盖"的错配）
2  🔴 日额度**只读**预检     ← 🔴 绝不写任何计数器
     已用尽 → 30070 + 快照；🔴 此时 QPM 计数器**未被触碰**（AC-QUOTA-012 前半句）
3  QPM 消费（原子 INCR）     ← 超限 → 10005 + retryAfterSeconds
     🔴 此时日额度**未被预占**（AC-QUOTA-012 后半句）；qpm_enabled=false 时整步跳过
4  日额度**预占**（原子 Lua） ← 失败（并发抢走最后一个额度）→ 30070 + 快照
     🔴 必须在**创建消息之前**：否则被拒时会留下孤儿 user/assistant 消息
     （PRD：额度用尽"不新建生成尝试"）；预占成员 = 服务端生成的 reservationId
5  创建消息 + 建流            ← 🔴 本步任何异常（10004/30040/30031/50003…）必须
     在 catch/finally 中**释放预占**（AC-QUOTA-004）
```

```
🔴 步骤 4 失败时 QPM 已计数 —— 这是唯一被明文接受的偏差，不得判缺陷：
  步骤 2 通过说明"请求开始时额度未用尽"，用尽是**并发**造成的；
  而 AC-QUOTA-004 已明文"对应请求已经通过频率准入时，其 QPM 次数不回退"。
  🔴 反向纪律：@后端 严禁为"回退 QPM"而实现 DECR —— 那会让"被限流期间重试不延长封禁窗口"
  这一既有性质失效，并引入"计数可被外部行为回拨"的新竞态。
```

#### 7.15.4 日额度计数口径（🔴 与 PRD §8.11.2 逐条对齐）

| 时点 | 动作 | 计数效果 |
|---|---|---|
| 预检（步骤 2） | 只读 | 🔴 **不计**（@测试 断言计数器前后不变） |
| 预占（步骤 4） | 占位 | 🔴 **不计 `used`**，只占 `remaining` |
| 🔴 **首个"资源已消耗证据"** | **结算（exactly-once）** | `used += 1`（DB 账本 + Redis 镜像） |
| 生成前失败（本地 `50003`、上游 400、配置非法、SSE 在模型开始前断开、模型未被调用） | 释放预占 | 🔴 **不计**（AC-QUOTA-004） |
| 已开始生成后：正常完成 / 模型报错 / SSE 断流 / 用户停止 | 已在证据点结算 | 🔴 **恰计 1 次**（AC-QUOTA-005 / 006） |
| 相同 `Idempotency-Key` 重放 | 走回放分支 | 🔴 **不重复计**（AC-QUOTA-007） |
| 用户点"重试"/"重新生成" | **新的**生成尝试，重新走五步准入 | 达到证据边界则**再计 1 次**；生成前失败则不计（AC-QUOTA-008） |
| 平台内部派生模型调用（会话标题生成 `TitleGenerator`、历史摘要刷新） | 🔴 **不计、不预占** | 它们不是"用户发起的生成尝试"，计入会让"用户只发了 1 条却扣了 2 次"（🔴 反向断言项 K7） |

**🔴 "资源已消耗证据" 的判定集合（满足任一即结算；@后端 不得增删）**：

```
ⓐ 上游产出首个 assistant 正文分片（delta.text 非空）
ⓑ 上游产出首个思考分片（delta.reasoning 非空）—— 它同样是已消耗的模型输出
ⓒ 本轮上游返回了 tool_calls（模型已完成一次推理并请求调用工具）
ⓓ 上游返回了可归属本次尝试的 token usage（totalTokens > 0）
🔴 结算必须在**该帧已 flush 之后**执行（先写 SSE，再落账）：
   证据点 ⓐ/ⓑ 正是首字锚点，落账放在其前会把 DB 往返算进首字 P95（§14.2.1）。
🔴 exactly-once 的**权威闸门 = 进程内一次性标记**（V1.2.6 消歧，完整裁决见 architecture §9.6.2）：
   ① 闸门 = 本次生成的一次性标记（`AtomicBoolean` CAS），🔴 它是唯一的"要不要落账"判定处；
   ② 🔴 `ZREM` **不是**闸门，而是**释放动作 + 诊断信号**（返回 0 → 只记 WARN，
      🔴 不重复计数、也不回退已落账的 DB）；
   ③ 🔴 DB 唯一键**也不是**闸门（结算 SQL 是无条件 `settled_count + 1`，非幂等）。
   动作顺序固定为 🔴 **DB 先写 → 再 ZREM + INCR 镜像**（"ZREM 成功才落账"已废止：
   它存在"ZREM 成功 → 崩溃 → DB 未写 = 用户白得一次生成"的不可补偿窗口）。
   成立依据 = 载荷不变量「一个 `reservationId` 恒由唯一一个 JVM 内的唯一一条生成线程持有并结算」
   （id 只存活堆内、不落库/不入 Redis 值/不下发前端，幂等回放完全不动账）
   → 🔴 "跨进程重复结算"当前**不可达**，与实例数无关。📋 二期若该不变量被打破，
   修法是把幂等性下沉到 DB（`reservation_id UNIQUE` 事件表），🔴 而非改回 ZREM 闸门。
🔴 结算失败（DB 异常）→ 🔴 绝不影响本次生成（不中断流、不改 done、不改 finishReason），
   记 ERROR 日志并**保留预占**（fail-closed 方向：宁可继续占位，也不放行）。
```

#### 7.15.5 配置分层与失败语义（🔴 反硬编码红线的落地口径）

```
有效策略 = 平台默认（sys_config: ratelimit.*）  ⊕  租户覆盖（tenant_quota_policies）

逐字段解析规则：
① 租户覆盖列为 NULL          → 🔴 继承平台默认（这是"未配置"的唯一表达）
② 租户覆盖列非 NULL 且合法    → 使用覆盖值
③ 租户覆盖列非 NULL 但非法    → 🔴 50003 + ERROR 日志，🔴 **禁止静默继承平台默认**
                               （AC-QUOTA-014：非法覆盖必须暴露，否则会掩盖误配置）
④ 平台默认缺失 / 不可解析 / 越界 → 🔴 启动期即拒绝启动（REQUIRED_CONFIG + 取值不变量）；
                               运行期兜底仍为 BusinessConfig.requireXxx → 50003
                               🔴 严禁代码中出现 3 / 50 作为兜底默认值
⑤ 生效时间：读取时按 effective_at <= now 取**最新一行**（🔴 无定时任务、无调度器）；
   未来行对当前尝试完全无效；配置变更只影响生效后的**新尝试**，🔴 不追溯改写已结算用量
⑥ 当日下调 limit 至 <= used → 立即 status=exhausted（remaining 取 max(...,0) 不为负）；
   上调后按新总量恢复；🔴 历史 used 不重算、不清零（AC-QUOTA-015）
⑦ 🔴 一期**不做**用户级白名单/个人加额（PRD §8.11.9）：作用域只有 platform / tenant，
   🔴 且**不为想象中的用户覆盖预留空字段**

失败语义（🔴 错误响应不得泄露表名、键名、内部取值或其他租户信息）：
  平台默认缺失/非法      → 50003（用户可见语义："对话额度配置异常，请联系管理员"）
  租户覆盖非法          → 50003（同上）
  租户 timezone 非法/空 → 🔴 50003，**禁止回落 UTC**
     理由：回落会把中国租户的"今日"边界静默挪到 08:00 —— 用户看到的"今日额度"
     与产品定义不符且无人会发现（静默错误比失败更糟）。
     🔴 选 50003 而非 30060：tenants.timezone 是**平台主数据**（DBA 维护、租户不可编辑），
     不属于 30060 的"Agent/Skill/MCP/Tool 配置或引用链"，用 30060 会污染 violations 契约。
  Redis 不可用          → 🔴 **降级为 DB 直判 + DB 原子结算，不放行**（与 §7.12 的 QPM 有意不同）；
                          代价 = 失去预占防超发（最坏超发 = 并发数），记 WARN，登记为 AR-024
```

#### 7.15.6 额度日窗口与租户时区（🔴 DST 必须正确）

```
zone       = ZoneId.of(tenants.timezone)             🔴 非法即 50003（不回落）
localDate  = 当前时刻在 zone 下的本地日期
periodStart= localDate.atStartOfDay(zone).toInstant()
resetsAt   = localDate.plusDays(1).atStartOfDay(zone).toInstant()
窗口标识    = d{yyyyMMdd}（🔴 **租户当地日期**，不是 UTC 日期）

🔴 DST 硬要求：
  ① 一个额度日的长度可以是 23h / 24h / 25h，🔴 严禁以固定 86400 秒推算 resetsAt；
  ② "当地 00:00 不存在"（spring-forward 落在 00:00 的时区，如 America/Havana）时，
     atStartOfDay(zone) 返回当天实际的第一个时刻（01:00），🔴 相邻两日窗口
     必须**首尾相接、不重叠不留空**；
  ③ Redis 键 TTL = (resetsAt − now) + 固定收尾余量，🔴 不得写死 86400。

🔴 QPM 分钟窗仍以 UTC 纪元构造（m{yyyyMMddHHmm}）—— 明确追认，不改：
  "一分钟"与时区无关；改成租户时区只会让同一 UTC 时刻的窗口边界因租户而异（零收益），
  并打断既有键格式与既有测试。🔴 时区只影响**日历日**边界，不影响分钟窗。
```

---

## 8. 契约一致性核对清单（@测试 / @架构师 签署用）

### 8.1 通用基线（M1 起持续生效）

```
□ 全部 /api/v1/** 成功与业务失败均 HTTP 200，code=0 唯一成功，timestamp 必带
□ 分页结构恒为 {list,total,page,pageSize}，对外字段均 camelCase
□ 所有 id/uid/conversationId/messageId/agentId/toolCallId 为 string；时间为 ISO-8601 UTC
□ 无 10002 / 40001；业务码未占用 20000~20999；所有出现的码均在 §2.2 登记
□ §2.1 子段「已用」列与 §2.2 登记表逐一对应，无多无缺
□ 无 /api/v1/auth/**、无 logout、无刷新令牌接口；前端无 /login 路由
□ /site/status 是唯一非 200 端点，其余站点异常均以 30010/30011/30012 表达
□ SSE 事件仅 meta/delta/tool/error/done，顺序正确、done 必发、error.code 为数字
□ 需幂等的 POST 均校验 Idempotency-Key，缺失返回 10001
□ GET /api/v1/agents 未返回 systemPrompt / model / 凭据类字段
□ GET /api/v1/me 未返回手机号 / 邮箱 / token
```

### 8.2 M2-min + M3 增补项（本版新增）

```
【SSE 与工具事件】
□ tool 事件字段齐备：toolCallId / toolType / toolKey / riskLevel / status / round /
  summary / argsSummary / resultSummary / truncated / errorCode / retryAfterSeconds（§5.2）
□ tool.summary 仍然下发（向后兼容），M1 前端无改动即可消费带新字段的流（§5.4.1）
□ 字段只增不改不删、枚举既有取值语义未变；事件名集合未扩张（仍为 5 个）
□ meta 仍首发且在模型分片前 flush；首字 P95 ≤5s 未因工具调用回退（§5.4.2）
□ 首字锚点 = 首个用户可见帧（delta 或 tool，取先到者）；🔴「首轮即工具调用」场景可断言、未误判超时（§5.4.2）
□ 状态迁移逐帧下发不跳帧；awaiting_confirmation 期间心跳持续（§5.1）
□ error 事件在 code=10005 时携带 retryAfterSeconds（§7.12）
□ tool 事件与 tool_calls 查询均不含正文/凭据/endpoint/内部 IP（§5.2、§7.9.1）

【确认通道】
□ confirm 路径与 §1.4、§7.8.2 完全一致，未使用 Idempotency-Key
□ 重复提交同一 decision → code=0 且 data.replayed=true；相反 decision → 30055（§7.8.2 表逐行覆盖）
□ 🔴 冲突判定以 tool_calls.decision 为基准：decision='allow' 的 denied 行（执行期竞态）再提交 allow
  → code=0 + replayed=true（**不是** 30055）；decision=NULL 的 denied 行 → 10004（§7.8.2 V1.1.3 矩阵）
□ 🔴 回放（replayed=true）**不产生** audit_logs 行，且 data.auditEventId 为 null；首次决定必产生 1 行且 auditEventId 为 32 位 hex（④）
□ 🔴 30055 产生 audit action=tool.confirm_conflict（result=denied, errorCode=30055），
  且同一 toolCallId **至多一条**（连续提交 3 次相反决定 → 仍只有 1 行审计）（⑤）
□ timed_out 的确认超时（errorCode=30050）与执行超时（30051/30056）在冲突判定上表现不同
□ high 风险工具每次调用均要求确认，历史同意未被沿用（§7.7.3）
□ 等待上限取 sys_config: tool.confirm_wait_seconds，前后端均无 120 字面量

【安全与隔离】
□ SSRF 双点位：保存时校验 + 每次调用前运行时兜底；改库为非法地址后运行时仍拒绝 → 30050 + 审计
□ 校验通过后以**原域名**发起连接且保留 TLS 主机名校验；每次调用前重新解析并逐 IP 校验；
  -Dnetworkaddress.cache.ttl=10 已在启动脚本生效；不跟随重定向（§7.6.3 可实现口径）
□ 未授权/未绑定/已停用工具调用 → 30050 + 审计 action=tool.grant_denied
□ 🔴 schema_changed 降级 / removed 且原已授权 → 审计 action=mcp.tool_grant_revoked（reason=schemaChanged / toolRemoved），
  且**未**被误记为 tool.grant_denied（§7.4.3 G3 裁决）
□ 🔴 绑定解析：skill 按 ref_version 精确取版本；mcpTool / localTool 读当前行（ref_version 不参与解析）；
  localTool 绑定指向 tenant_tool_grants.id，grant 行重建后表现为 fail-closed（§7.4.4 G1 裁决）
□ 🔴 内置变量 tenantId/locale/timezone/nowIso **不可被 variable_values 覆盖**（同名键忽略 + WARN，不报错，§7.5.3 G4 裁决）
□ 🔴 连接测试可区分 connect_failed（连接被拒/不可达）与 timeout（无响应超时），两者均 code=0（§7.4.2 G2 裁决）
□ 🔴 一期不发 initialize、不维护会话；上游强制要求 → protocol_incompatible / 30052（§7.6.2 G9 裁决）
□ 🔴 sse 传输只回 202 或要求独立 GET 流取结果 → 30052 / protocol_incompatible，且未引入任何常驻读取线程（§7.6.1 G6 裁决）
□ 🔴 内置本地 Tool 仅 datetime_now / calculator（low + 幂等 + 纯函数）；清单外的 local_tools 行 → 30060；
  calculator 未使用任何 eval 类设施、不支持幂运算、超长/非法字符表达式被 30053 或 30057 挡住（§7.7.1 G5 裁决）
□ 🔴 low 风险纯函数工具**成功执行不写 audit_logs**（可追溯性由 tool_calls 承担，§7.7.1）
□ 新发现工具默认 granted=false/status=disabled；schema_changed 的已授权工具自动降级（§7.4.3）
□ MCP 凭据只回 configured + last4（<8 位返回 ****），永不回显明文/密文片段（§7.4.1）
□ 埋点字段白名单生效；禁止字段命中即整条丢弃；pagePath 去 query（§7.10.1）
□ §7 全部接口的租户隔离：请求内 tenantId 被忽略并记安全日志；跨租户 ID 统一 10004
□ 埋点与工具接口的租户隔离经跨租户实测验证（gift ↔ redbook 互不可见）

【审计与事务边界】
□ 非流式安全操作：审计与业务同事务，审计失败整体失败并返回 50003（EX-024）
□ 流式内安全事件：独立短事务（tool_calls 状态 + 审计同事务）
□ 🔴 审计失败导致该次工具调用失败，但 SSE 流未被中断，仍以 error + done 收敛
□ 审计 action 枚举与 §7.14 一致（🔴 恰 **12** 个，断言 AuditActions.ALL == §7.14 表）；敏感值只记 digest
□ 🔴 执行前授权点查只复查授权/启用列，**不复查能力绑定**（绑定为生成期快照，§7.6.3 的 V1.1.5 G-2 裁决框 / AR-019）；
  🔴 三张表（mcp_tools / tenant_tool_grants / local_tools）**无 deleted_at 列**，不得断言（§7.6.3 的 V1.1.5 G-1 裁决框）

【配置与缓存】
□ 缓存失效同时清 L1 与 L2；scope=tenant 时 Host 键与租户号键成对失效（D-003/D-006）
□ 缓存失效返回各作用域实际条目数；部分失败 → 30061 + incompleteScopes，未伪报成功
□ 🔴 非平台管理员调用缓存失效 → `20000`（`eyes-auth.enabled=true` 的真实环境）/ `10003`（`eyes-auth.enabled=false` 的 test profile）；
  🔴 共同不变量：绝不 `code=0`（§3 二分口径，V1.1.5 G-5）
□ 独立校验入口对非法配置返回 30060 + 字段级 violations，且不含密钥/内部地址
□ 🔴 objectType=agentVersion & includeReferences=true 递归覆盖三类绑定（skill/mcpTool/localTool），
  第 2 层非法即 30060 且 violations 精确指向第 2 层对象；warnings 中已不含 referencesNotFullyChecked；
  includeReferences=false 时该 warning 仍出现（§7.3.1 G10 收尾）
□ 运行时非法配置在进入模型或工具执行前失败（30060），无 NPE / 未分类 500 / 字符串码
□ 工具调用轮次上限取 sys_config: tool.max_rounds，超限 30054 且正常发 done
□ 🔴 system 提示总长超 chat.system_prompt_max_chars → 30060 rule=systemPromptBudgetExceeded，
  在进模型前失败，且**未发生任何截断**（Skill output_constraint 完整保留或整体失败，§7.5.2 ①）
□ 🔴 模型函数名归一化：crm:lookup → crm_lookup；归一化后 >64 字符或碰撞 → 30060
  （rule=functionNameTooLong / functionNameCollision）；SSE 与 tool_calls 仍记原始 toolKey（§7.6.5 ⑥）
□ 🔴 执行期竞态（running 后撤授权/改内网地址）→ tool_calls 为 denied + errorCode=30050（**不是** failed/30052），
  并写 tool.grant_denied / mcp.ssrf_rejected 审计；用量聚合计入 toolDeniedCount（§7.8.1 ③、§7.11.1）
□ 🔴 SSE 内 error.retryAfterSeconds（10005）为**契约预留、一期不可达**：不为该路径写用例、不判缺陷；
  未建流路径的 code=10005 + data.retryAfterSeconds 必测（§7.12）
□ §7.1.2 全部新增 sys_config 键已入库，代码中无对应字面量
□ 🔴 §7.1.2 键总数 = 25（含 chat.system_prompt_max_chars + observability.analytics_enabled
  + observability.analytics_sample_rate），全部在 StartupChecker.REQUIRED_CONFIG；删任一键 → 启动失败
□ mcp.require_https 与 mcp.allowed_internal_cidrs 在生产为 true / []（阻断上线项）
```

### 8.3 M3 签署核对清单（🔴 V1.1.4 新增，@测试 逐条打勾即构成 M3 签署依据）

> 说明：§8.1 / §8.2 是**逐条断言**清单；本节是**签署视图** —— 按主题聚合 M3 全部可验收项，
> 每行给出「判据 + 契约出处」。🔴 打勾规则：`✅`（实测通过）/ `❌`（缺陷，需附缺陷号）/ `⏸`（Deferred，需附本文授权行）。
> 🔴 出现任何 `❌`，或出现未经本文授权的 `⏸`，@架构师 不予签署。
> 🔴 **V1.1.5 说明（保证 @测试 已完成的映射不失效）**：本版**只订正判据文字，🔴 不增、不删、不重排任何条目** ——
> 判据总数仍为 **58 项**（A6 · B6 · C7 · D5 · E11 · F6 · G11 · H6），编号一一保持。
> 本版被订正的条目：**A4**（G-3）、**C3**（G-1）、**C5**（G-2）、**C7**（G-2）、**D3**（G-4/G-5）、**F3**（G-0）、**H6**（G-2 追加 AR-019）。
> ⚠️ **编号消歧（重要）**：本节 **G 组判据编号 `G-1`~`G-11`**（配置治理与运行时兜底）与 **V1.1.5 裁决项 `G-0`~`G-5`** 是两套互不相关的编号。
> 🔴 全文引用裁决项时一律写作 **`V1.1.5 G-x`**；单独出现的 `G-1`~`G-11` 一律指本节 **G 组判据**。

**A. SSE 与工具事件**

| # | 判据 | 出处 |
|---|---|---|
| A1 | `tool` 事件 12 个字段齐备且类型正确；`summary` 仍下发；事件名仍为 5 个 | §5.2、§5.4.1 |
| A2 | 状态迁移逐帧不跳帧；`awaiting_confirmation` 期间 `: ping` 持续 | §5.1 |
| A3 | 首字锚点 = 首个可见帧（`delta` 或 `tool`）；三条链路 P95 ≤5s（含"首轮即工具调用"） | §5.4.2、architecture §14.2.2 门禁 2 |
| A4 | 🔴 `error` 事件的键集合**恰为** `code`/`message`/`retryAfterSeconds` **3 项**（🔴 V1.1.5 G-3 追认三项形状，`retryAfterSeconds` 恒存在、一期恒 `null`）；`code=30060` 时**不含** `violations` 等任何字段级明细 | §5.2（V1.1.4 #2 + V1.1.5 G-3） |
| A5 | 工具失败不中断流；`error` 后必发 `done`；`done` 必发 | §5.1、§7.6.4 |
| A6 | 🔴 SSE / `tool_calls` / §7.9.1 / 审计一律记**原始 `toolKey`**，`functionName` 不出现在任何对外载荷 | §7.6.5 |

**B. 高风险确认与幂等**

| # | 判据 | 出处 |
|---|---|---|
| B1 | §7.8.2 冲突矩阵**逐行**覆盖；判定以 `tool_calls.decision` 为基准（3 类 `denied` 表现不同） | §7.8.2 |
| B2 | 重复同 `decision` → `code=0` + `replayed=true`；🔴 **不产生** `audit_logs` 行且 `auditEventId=null` | §7.8.2 ④、§7.14 不变量 6 |
| B3 | 相反 `decision` → `30055` + `audit action=tool.confirm_conflict`，同一 `toolCallId` **恰好 1 行**（连提 3 次仍 1 行） | §7.8.2 ⑤ |
| B4 | 🔴 `30055` 的审计在**独立短事务**中提交（先审计后抛错），30055 场景实测审计行数 = 1（**不是 0**） | §7.8.2 #5、§7.14 不变量 7 |
| B5 | `high` 风险每次调用均确认，历史同意未被沿用；等待上限取 `tool.confirm_wait_seconds`（前后端无 120 字面量） | §7.7.3、§7.8.1 |
| B6 | 确认等待中"停止生成" ≤1s 收敛为 `cancelled` | architecture §9.5.4、§14.2.2 门禁 3 |

**C. 授权、SSRF 与执行期竞态**

| # | 判据 | 出处 |
|---|---|---|
| C1 | 未授权/未绑定/已停用 → `30050` + `audit action=tool.grant_denied` | §7.6.3、§7.7.2 |
| C2 | SSRF 双点位：保存时 + **每次调用前**；改库为内网地址后运行时仍拒绝 → `30050` + `mcp.ssrf_rejected`；不跟随重定向；`networkaddress.cache.ttl=10` 生效 | §7.6.3、ADR-009 |
| C3 | 🔴 **撤 `mcp_tools.granted` / 停用 `status` / 停用 `mcp_servers` / 撤本地 Tool 授权后，下一次工具执行必须 `30050` 被拒**（执行前 ≤1 次点查，含确认等待后的竞态）。🔴 V1.1.5 口径：判据**只看** `granted` / `status`（+ `mcp_servers.deleted_at`），🔴 三张表无 `deleted_at` 不得断言（V1.1.5 G-1） | §7.6.3（V1.1.4 #4 + V1.1.5 G-1）、AC-MCP-004 |
| C4 | 🔴 执行期竞态落 `denied + errorCode=30050`（**不是** `failed/30052`）；用量计入 `toolDeniedCount` | §7.8.1 ③、§7.11.1 |
| C5 | 🔴 两类残余窗口按契约判定、**均不判缺陷**：ⓐ **已进入 `invoke`** 的那一次允许完成（≤ 单次执行时长，AR-017）；ⓑ 🔴 V1.1.5 G-2 新增：**生成中解绑**（`agent_capability_bindings`）→ 本轮生成剩余部分仍可执行（绑定为生成期快照，AR-019），生效点为**下一次生成** | §7.6.3 残余窗口行 + G-2 裁决框、AR-017 / AR-019 |
| C6 | `schema_changed` 降级 / `removed` 且原已授权 → `mcp.tool_grant_revoked`（未误记为 `tool.grant_denied`） | §7.4.3、§7.14 |
| C7 | 绑定解析：`skill` 按 `ref_version` 精确取版本；`mcpTool`/`localTool` 读当前行；grant 行重建后 fail-closed。🔴 V1.1.5 补：绑定解析**只发生在清单构造期**（生成期快照），执行前点查不再复查绑定（V1.1.5 G-2） | §7.4.4、§7.6.3 的 V1.1.5 G-2 裁决框 |

**D. 租户隔离与鉴权**

| # | 判据 | 出处 |
|---|---|---|
| D1 | §7 全部接口：请求内 `tenantId` 被忽略并记安全日志；跨租户 ID 统一 `10004`（不泄露存在性） | §7.1.1、EX-003 |
| D2 | 跨租户实测（gift ↔ redbook）：工具、埋点、用量、MCP 治理、校验入口互不可见 | AC-TEN-004/005 |
| D3 | 🔴 **每个 `@TenantRole` 端点都有程序化兜底**：在 `eyes-auth.enabled=false` 的 profile 下，角色不足/无身份仍返回 `10003`（🔴 出现 `code=0` 即安全缺陷）。🔴 V1.1.5 补两条口径：ⓐ 平台层 `@Permission(ADMIN)` 端点的失败码按 §3 **二分**（真实环境 `20000` / test `10003`），共同不变量仍是**绝不 `code=0`**（V1.1.5 G-5）；ⓑ 平台层 `@Permission` 的通用兜底维持**二期技术债**（V1.1.5 G-4），一期防线 = 两处 ADMIN 语义端点已有程序化兜底 + `prod` 启动断言 `eyes-auth.enabled=true` | §3（V1.1.4 #6 + V1.1.5 G-4/G-5）、architecture §8.2.1 |
| D4 | 🔴 守护测试存在且有效：反射扫描出的 `@TenantRole` 端点集合 **⊆** 已被兜底断言覆盖的集合（新增端点漏兜底 → 测试红） | §8.3 D3 配套、architecture §8.2.1 |
| D5 | 异步段（工具/审计/埋点写入）一律用快照 uid，未使用 `UserInfoHolder`；快照缺失即拒绝执行 | architecture §9.5.2 |

**E. 埋点与用量**

| # | 判据 | 出处 |
|---|---|---|
| E1 | 字段白名单生效（未列字段静默丢弃）；禁止字段命中 → 整条 `discarded` + 安全日志（不回显命中细节） | §7.10.1 |
| E2 | `pagePath` 强制去 query/hash；`errorCode` 非登记码置空；`conversationId`/`agentId` 跨租户置空（不报错） | §7.10.1 |
| E3 | `clientEventId` 去重计入 `duplicated`；采样判定对同一 `clientEventId` 稳定一致 | §1.4、§7.10.1 |
| E4 | 匿名事件：`uid=NULL` + `loginState=anonymous`；`analytics_anonymous_enabled=false` 时全部 `discarded` | §7.10.1 |
| E5 | 🔴 `analytics_enabled` / `analytics_sample_rate` 已入库（键总数 **25**）且在 `REQUIRED_CONFIG`：删任一键 → **启动失败** | §7.1.2（V1.1.4 #1） |
| E6 | 🔴 读取侧 **fail-closed**：临时置空/破坏两键值 → 事件**全部丢弃**（`accepted=0`）+ ERROR 日志，🔴 **不得**全量接收 | §7.10.1（V1.1.4 #1） |
| E7 | 🔴 `analytics_sample_rate` 越界（如 `1.5`/`-0.2`）→ **启动失败**（非 WARN） | §7.1.2 不变量 |
| E8 | 埋点异常永不影响主流程：一律 HTTP 200 + `code=0` | §7.10.1 |
| E9 | 🔴 用量 `rateLimitedCount` **恒为 0**（非 0 即缺陷）；未自造事件名 / 未自造 `sys_config` 键 / 未估算替代 | §7.11.1 |
| E10 | 用量聚合口径逐字段与 §7.11.1 表一致；互斥不变量成立（`denied + failed + succeeded + cancelled + 非终态 = toolCallCount`） | §7.11.1 |
| E11 | 用量只回聚合计数（无正文、无 uid 列表、无跨租户数据）；`granularity=day/hour` 均可用，跨度 >31 天 → `10001` | §7.11.1 |

**F. 审计与事务边界**

| # | 判据 | 出处 |
|---|---|---|
| F1 | 非流式安全操作：审计与业务同事务，审计失败 → 回滚 + `50003` | §7.14 |
| F2 | 流式内：`tool_calls` 状态 + 审计同一独立短事务；🔴 审计失败使该次调用失败但 **SSE 未中断**（仍 `error` + `done`） | §7.14、ADR-010 |
| F3 | audit `action` 枚举与 §7.14 完全一致（🔴 **12 项**，V1.1.5 G-0 订正原"11 项"；含 `tool.confirm_conflict` / `mcp.tool_grant_revoked`），无自造 action；🔴 断言方式 = `AuditActions.ALL` **集合恒等**于 §7.14 表的 12 个字面量 | §7.14（V1.1.5 G-0）、architecture §11.1.1 |
| F4 | 🔴 六类禁记清单数据核验通过（凭据/正文/工具明文/个人信息/基础设施细节/其他租户存在性） | architecture §11.1.2 |
| F5 | `auditEventId` 恒 32 位小写 hex（未截断），与 `audit_logs.event_id` 一致 | §7.14 不变量 5 |
| F6 | 🔴 low 风险纯函数工具**成功执行不写审计**（反向断言） | §7.7.1 |

**G. 配置治理与运行时兜底**

| # | 判据 | 出处 |
|---|---|---|
| G-1 | 缓存失效同清 L1+L2；`scope=tenant` 时 Host 键与租户号键**成对**失效；部分失败 → `30061` + `incompleteScopes` | §7.2.1 |
| G-2 | 🔴 失效接口**不删除**运行时状态键（`chat:idem/cancel`、`tool:confirm`、`limit:msg`），即便 `scope=all` | §7.2.1 |
| G-3 | 🔴 `objectType=agentVersion & includeReferences=true` 递归覆盖三类绑定，第 2 层非法 → `30060` 且 `violations` 指向第 2 层；`warnings` 已不含 `referencesNotFullyChecked`；`includeReferences=false` 时该 warning 仍出现 | §7.3.1 G10 |
| G-4 | 🔴 校验入口查询 ≤4 次；🔴 运行时清单构造 ≤**5** 次（按 §7.1.2 查询次数表）；两处均无 N+1 | §7.1.2（V1.1.4 #3） |
| G-5 | 🔴 管理端 `30060` 带完整 `violations[]`；🔴 终端用户路径 `30060` 仅 `code + message`，且 ERROR 日志含 `requestId + rule + objectId` 可反查 | §7.3.1（V1.1.4 #2） |
| G-6 | system 提示超 `chat.system_prompt_max_chars` → `30060 rule=systemPromptBudgetExceeded`，进模型前失败且**无截断** | §7.5.2 ① |
| G-7 | 函数名归一化：`crm:lookup → crm_lookup`；>64 或碰撞 → `30060`（`functionNameTooLong` / `functionNameCollision`） | §7.6.5 |
| G-8 | 轮次超 `tool.max_rounds` → `30054` 且正常发 `done`；结果超 `tool.result_max_bytes` → `truncated=true` | §7.6.4、ADR-011 |
| G-9 | 运行时非法配置在进模型/工具执行前失败，无 NPE / 未分类 500 / 字符串码 | AC-CFG-004 |
| G-10 | 内置本地 Tool 仅 `datetime_now` / `calculator`；清单外 `local_tools` 行 → `30060`；`calculator` 安全专项全过 | §7.7.1、ADR-015 |
| G-11 | 内置 Mock MCP 两种传输全链路可用；`SysConfigOverride` 均 `try-finally` 还原（测试后配置无残留） | §7.13 |

**H. 一期授权的 Deferred / 不可达项（🔴 出现在下表即为"允许 ⏸"，@测试 不得判缺陷）**

| # | 项 | 授权出处 |
|---|---|---|
| H1 | SSE 内 `error.retryAfterSeconds`（`10005`）不可达；`tool.retryAfterSeconds` 恒 `null` —— 🔴 不写用例、不判缺陷；未建流路径的 `10005` + `data.retryAfterSeconds` **必测** | §7.12（V1.1.4 #7 维持） |
| H2 | `rateLimitedCount` 恒 0（二期方案 (a)/(b) 待 Boss 裁决） | §7.11.1 |
| H3 | 三类授权/版本快照缓存与其 TTL 键"预留—一期禁用"，不纳入失效断言 | §7.1.2、§7.2.1 |
| H4 | 无任何管理 UI；§7 管理端接口以"接口实测 + 数据核验"验收 | §7.1.1、PRD §8.10 |
| H5 | M2 管理后台全量 Deferred 至二期 | §6、PRD DEC-008 |
| H6 | 执行期竞态的 `invoke` 内残余窗口（C5ⓐ / AR-017）、DNS 重绑定残余窗口（AR-009）、🔴 **生成中解绑的本轮残余（C5ⓑ / AR-019，V1.1.5 G-2）** 均不可消除 | §7.6.3（含 V1.1.5 G-2 裁决框）、ADR-009 |
```

**🔴 I 组：`sse` 双形态核对项（V1.2.0 新增，G6′ / ADR-016；🔴 与 A~H 组的 58 项并列，不改动既有编号）**

> 🔴 **V1.2.1 验证形态与门禁（回应 @后端 第 ④ 问）**：I 组允许以**组件级测试**（零 Spring / 零 DB、对 JDK `HttpServer` 真实上游，如 `SseTransportFormAdaptiveTest`）**先行**给出等价断言 —— 因为两个新 `sys_config` 键落库前 `StartupChecker` 会按契约**拒绝启动**，Spring 上下文型 `*IT` 无法先跑。
> 🔴 但它**不构成签署依据**：I 组签署的**前置条件**是一次 **`mvn -o verify`（含 failsafe / `SseLegacyTransportIT`）全绿**记录（Controller / 审计 / `last_check_*` 落库 / 端到端 `code` 映射只有 IT 能覆盖）。两键落库完成后，`verify` **立即**成为强制门禁。

| # | 核对项 | 依据 |
|---|---|---|
| I1 | 🔴 **不回归**：Mock **同步形态** `/mock-mcp/sse` 的全部既有 `TRANSPORT_SSE` 用例 + AC-MCP-003「两种传输完整链路」原样通过；`initialize` 探测收到 `-32601` 被**忽略**、不影响结论 | §7.6.1 G6′ ⑨、§7.13 |
| I2 | 🔴 **异步形态可用**：`/mock-mcp/sse-legacy` 的 `tools/list` 与 `tools/call` 端到端成功（`data.result="success"`、`isError=false`） | §7.13、ADR-016 ② |
| I3 | `never_push` → `timeout` / `30051`；`close_early` → `protocol_incompatible` / `30052` 且 🔴 **立即失败（不等到超时）**。🔴 **V1.2.1 判据细化**：GET 流被 `HttpRequest.timeout(remaining)` 打断（`HttpTimeoutException`）→ 判 **`timeout` / `30051`**，🔴 **不是** `30052` | ADR-016 失败分类对照表 |
| I4 | `init_error` / `oversize_stream` / 会话端点跨源 → 均 `protocol_incompatible` / `30052`；🔴 跨源**不得**改判 `30050` | §7.4.2、ADR-016 ⑦ |
| I5 | `noise_then_result` → `success`（🔴 验证"仅接受匹配 `id` 的报文，`notifications`/`logging`/`ping`/不匹配 id 一律丢弃"） | ADR-016 ④ |
| I6 | 🔴 `mcp.sse_legacy_enabled=false` → 异步形态上游判 `protocol_incompatible` / `30052`（**完整回到 G6 行为**）；两键任一缺失 → **启动失败**；`sse_stream_max_bytes < tool.result_max_bytes` → **启动失败** | §7.1.2、ADR-016 ⑨ |
| I7 | 🔴 **预算专项**：`sse` 异步形态整次 exchange 总耗时 ≤ 单次调用预算（`tools/call` 取 `min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)`），🔴 **不得为 2× 以上**；`discover` 翻页不重置预算 | §7.6.1 G6′ ⑤、§7.4.3 |
| I8 | 🔴 **泄漏专项（AR-020）**：连续 200 次异步形态 exchange 后 JVM 线程数不单调增长；无 `new Thread` / `Executors.new*` / `supplyAsync` / 无参 `join()`（🔴 静态检查 + 代码评审） | AR-020、ADR-008 第 8 条 V1.4.0 补注 |
| I9 | 🔴 `mcp_servers.transport` 合法值**仍恰 2 个**：写入 `sse_legacy` → `30060` `rule=enum`（证明未偷偷扩枚举）；`data.result` 字面量**仍恰 9 个** | §7.4.2、§13.5.3 |
| **I10** | 🔴 **V1.2.1 新增｜GET 非 2xx 的二分**：GET 建流返回 `401`/`403`/`407` → `auth_failed`、返回 `3xx` → `protocol_incompatible`，且两者🔴 **均不得退化为直接 POST**（断言上游**未收到**任何 POST）；返回 `404`/`405`/`5xx` → **退化为直接 POST** 并按该次 POST 的结果分类（同步形态链路仍成功） | §7.6.1 G6′ ② 步骤 1、ADR-016 失败分类对照表 |

**J 组｜超时预算与外部工具适配（🔴 V1.2.2 新增 J1~J10；🔴 V1.2.4 增补 J11/J12 并订正 J9 判据 —— A~I 组与 J1~J8/J10 编号与判据零变化）**

| # | 判据 | 契约出处 |
|---|---|---|
| **J1** | 🔴 **P1-2 主判据**：把 `chat.generation_deadline_seconds` 临时覆盖为极小值（`SysConfigOverride` + `try-finally`）后发起一次含高风险确认的生成 → 客户端**必须**收到 `error(50002)` + `done(finishReason=timeout, status=failed)`；消息落库 `failed`；`tool_calls` **无**非终态残留 | §5.2 `finishReason=timeout` 口径框、ADR-017 ④、`architecture.md` §9.5.4 不变量 1~3 |
| **J2** | 🔴 **反向断言（本轮 FAIL 项的锚点）**：常规超时路径的后端日志中**不得**出现 `AsyncRequestTimeoutException`，也不得出现"连接静默关闭且无 `done`、而用户并未关页面/切网"的情形；出现即判缺陷（🔴 不得以"可预期事件"为由放行） | ADR-017 ③⑤⑥、§9.5.4 不变量 5、AR-022 |
| **J3** | 🔴 `AsyncRequestTimeoutException` 一旦发生（人为构造），必须是 **WARN + 无响应体**（🔴 不返回 `50003`、不产生 "No converter for Result" 噪声）；`AsyncRequestNotUsableException` → DEBUG；🔴 其余异常仍走 `50003`（反向断言 catch-all 未被放宽） | ADR-017 ⑤ |
| **J4** | 🔴 **确认倒计时不得骗人**：`awaiting_confirmation` 帧带 `confirmExpiresInSeconds`（number ≥1）且在预算被收紧时 **< `tool.confirm_wait_seconds`**；🔴 `剩余 − 宽限 ≤ 0` 时**不出现** `awaiting_confirmation` 帧（也不落 `awaiting_confirmation` 状态）。🔴 **V1.2.3 覆盖级别订正**：后半句（`剩余 − 宽限 ≤ 0`）的验收级别为 **单测**（`ToolFeedbackDiagnosticTest`，注入 `usableSeconds=0`）—— 该分支在「工具执行准入」就位后已是**防御性不变量**（准入要求 `usable ≥ 工具超时 ≥ 1`，本分支判据 `usable ≤ 0`，两点间仅微秒级进程内工作），IT 稳定构造需向生产代码植入时钟钩子 → 🔴 **不得因"缺 IT 证据"判缺陷或阻塞签署**；前半句仍为 IT 级 | §5.2、§7.8.1 ④、ADR-017 ③ⓑ / 落点 #12ⓒ |
| **J5** | 🔴 **启动不变量**：`generation_deadline + grace > spring.mvc.async.request-timeout/1000` → **启动失败**；`deadline_grace_seconds = 1` → **启动失败**；三键任一缺失/空白 → **启动失败**；`deadline < first_token_timeout` → 仅 **WARN**（🔴 不失败） | §7.1.2 V1.2.2 不变量、`architecture.md` §13.6 纪律 8/9 |
| **J6** | 🔴 **P1-1 主判据（可断言部分）**：工具失败时回灌模型的 `role=tool` 内容**包含上游/校验器诊断** —— `30057`（上游 `isError` 正文）、本地 `30053`（字段级校验诊断）、MCP `-32602`（上游 `error.message`）各一例 | §7.6.4 V1.2.2 二分框、ADR-018 ③ |
| **J7** | 🔴 **泄露反向断言**：`30052` / `30051` / `30056` / `30050` / `50003` 的回灌内容**仅固定措辞** —— 不含 endpoint / IP / 端口 / 凭据 / Java 异常类名 / 堆栈；且 `30050` 的**四种**来源（🔴 **V1.2.3 订正：不在清单内 / preflight 服务停用 / 执行前授权点查 / 执行期竞态（含 `GRANT_REVOKED`·`SSRF_REJECTED`）**）回灌措辞**逐字完全一致**，出现差异化即判 🔴 **安全缺陷**（措辞差异 = 探测平台配置的信道）；🔴 `SSRF_REJECTED` 复用"未授权或已停用"这句**不精确**措辞是**有意为之**，不得判为文案缺陷 | §7.6.4 二分框 ❌ 侧、ADR-018 ③ V1.4.3 补注 |
| **J8** | 🔴 **上游 schema 透传守护**：下发给模型的 `tools[].function.parameters` 与 `mcp_tools.input_schema` **逐字相等**（🔴 未被补 `enum`/`format`/改写 `description`）；连续两次 `discover` 的 `unchangedCount` 稳定、🔴 **不产生** `mcp.tool_grant_revoked`（证明未因加工导致 digest 漂移撤授权） | ADR-018 ①、§7.4.3 |
| **J9** | 🔴 **纪律段注入（🔴 V1.2.4 判据订正，原"第 2 条 system"作废）**：catalog 非空 → 上下文中 `role=system` **恰 1 条且在 `index 0`**，其内容 🔴 **`endsWith` 纪律段**（`{{currentTime}}` 已替换为**当前**时间）且 🔴 **`startsWith` 租户段**；catalog 为空 → **不注入**（仍恰 1 条 system 且不含纪律段片段）；🔴 满配 `system_prompt` + 满配 Skill + 纪律段仍**不** `30060`（证明纪律段未计入 `system_prompt_max_chars`），🔴 同一用例反向断言**租户段自身**超限仍 `30060`；🔴 最终 system **物理长度可以超过** `system_prompt_max_chars`（🔴 **不得**判为缺陷）；🔴 纪律段文案中**不含**任何具体工具名/上游字段名 | §7.5.2 ⑥ ②③、§7.1.2 文案纪律 ④、`architecture.md` ADR-019 |
| **J10** | ⚠️ **观察项，🔴 不作为签署阻塞项**：普通自然语句（如"帮我联网搜索最近关于 X 的新闻"）连续 3 次 —— 判据为「**每次失败后模型是否收到了可自纠的诊断**」（查 J6 的回灌内容即可判定），🔴 **不得**要求"模型 100% 不补可选参数"或"必然一次成功"（属模型行为，不在 Albedo 控制范围） | ADR-018 ⑤ 验收口径、责任边界框 |
| **J11** | 🔴 **V1.2.4 新增 —— 单一前导 `system` 不变量（全组合矩阵）**：{有/无 Skill} × {有/无已缓存摘要} × {有/无 tools} × {空/非空 `system_prompt`} 全组合下，发往上游的 `messages` 中 `role=system` **至多 1 条**，存在时**必须在 `index 0`**；🔴 必含一例「**长会话 + 已缓存摘要 + 有工具**」（三块同时存在）→ 仍恰 1 条 system 且生成成功（守护摘要块合并这一**同源既有隐患**）；🔴 适配层反向守护：人为构造 2 条 system 或 system 不在 `index 0` → 抛内部异常并以 `error(50003)` + `done(failed)` 收敛（🔴 `done 必发`不变），🔴 异常信息**不含**任何消息正文；🔴 反向断言常规路径**不出现**该 `50003` | §7.5.2 不变量、§7.5.4 摘要载体框、ADR-019 ①④ |
| **J12** | 🔴 **V1.2.4 新增 —— BUG-MCP-004 回归守护（🔴 签署前置，不得省略）**：至少一条端到端用例的**桩上游必须复刻真实上游硬约束**（收到 >1 条 system 或 system 不在 `index 0` → 返回 **400**）；在该桩下 ⓐ 用 `calculator`（本地 Tool）跑含工具生成 → 🔴 **不出现** `error(50002)`，`tool` 帧走完 `pending → running → succeeded`，`done(status=completed)`；ⓑ 断言桩**实收**请求体里 system 恰 1 条、在 `index 0`、内容以纪律段结尾（🔴 反向守护"靠删/清空 `chat.tool_usage_guideline` 让测试变绿"）。🔴 **不得**以"单测已断言只有 1 条 system"替代本项 —— 本轮回归溜过 731 个用例的**唯一原因**就是桩不校验消息形态 | test-report V4.1 BUG-MCP-004、ADR-019 ⑤ + 落点 #5 |

**K 组｜用户维度限流与每日限额（🔴 V1.2.5 新增 K1~K16；🔴 A~J 组编号与判据零变化）**

| # | 判据 | 契约出处 |
|---|---|---|
| **K1** | 🔴 **QPM 默认与租户覆盖**：平台默认 `ratelimit.message_per_minute=3` 且租户无覆盖时，同一 `tenantId+uid` 一个分钟窗内第 4 次 → HTTP 200 + `code=10005` + `data.retryAfterSeconds ≥ 1`，🔴 **模型未被调用**；`gift` 覆盖为 5 时第 4 次仍可发、`redbook` 继承 3 时第 4 次被拒，且两租户计数互不影响 | §7.12、§7.15.5、AC-LMT-003 / AC-LMT-004 |
| **K2** | 🔴 **小时窗已废除（反向断言）**：`ratelimit.message_per_hour` 行已删除、`StartupChecker.requiredConfigKeys()` **不含**该键、`ConfigKeys` 无该常量；把 QPM 覆盖为 200 后连续 130 次准入**无任何限流拒绝**（>历史 120） | §7.1.2 废弃框、§7.12、AC-LMT-005 |
| **K3** | 🔴 **日限额默认与拒绝形态**：日默认 50 且无覆盖时，前 50 次达到资源消耗边界的尝试均结算成功；第 51 次 → HTTP 200 + `code=30070` + `data` 恰 9 键且 `used=50, limit=50, remaining=0, status=exhausted`，🔴 **模型未被调用**（断言上游 0 次调用） | §7.15.2、§7.15.3、AC-QUOTA-001 |
| **K4** | 🔴 **优先级双向断言**：ⓐ 日额度已用尽 + 同时构造 QPM 超限条件 → 只返回 `30070`，且 🔴 **分钟窗计数器数值未增加**（直接断言 Redis 计数）；ⓑ 日额度可用但 QPM 超限 → 返回 `10005`，且 🔴 `used` 与在途预占数**均未变化** | §7.15.3、AC-QUOTA-012 |
| **K5** | 🔴 **两态严格区分**：`30070` 的 `data` 🔴 **不含** `retryAfterSeconds`（含即缺陷）；前端 `rateLimitStore` 🔴 **不进入**倒计时（`waiting` 恒 false）；`10005` 的形态与文案与 M3 基线逐字不变 | §2.2、§7.15.2、AC-QUOTA-012 |
| **K6** | 🔴 **计数口径（生成前失败不计）**：本地 `50003` 且模型未调用 / 上游在生成开始前 400 / SSE 在模型开始前断开 / 会话只读 `30040` → `used` 不增加且**预占已释放**（断言 `remaining` 恢复）；该请求若已通过频率准入，其 QPM 次数 🔴 **不回退** | §7.15.3 步骤 5、§7.15.4、AC-QUOTA-004 |
| **K7** | 🔴 **计数口径（已消耗必计 + 内部调用不计）**：产生首个正文/思考分片、或返回 `tool_calls`、或返回可归属 token usage 后，无论 `completed` / 模型报错 / SSE 断流 / 用户停止，`used` **恰 +1**；🔴 反向断言：会话标题生成与历史摘要刷新**不产生**任何额度计数 | §7.15.4 证据集合、AC-QUOTA-005 / 006 |
| **K8** | 🔴 **幂等与重试**：同 `Idempotency-Key` 重放 ≥2 次 → 只 1 个用户消息、1 次生成尝试、**至多 1 次**结算，且 🔴 重放**不消费** QPM；点"重试/重新生成" → 新尝试，达到证据边界各 +1、生成前失败不 +1，且不重复创建用户消息 | §1.4、§7.15.3 步骤 0、§7.15.4、AC-QUOTA-007 / 008 |
| **K9** | 🔴 **并发不超发**：仅剩 1 次额度时并发发起 ≥2 个有效请求（`CountDownLatch` 同时释放）→ **恰 1 个**取得生成资格，其余 `30070`；终态 `remaining=0`、`used ≤ limit`，🔴 无负数剩余、无超额生成。⚠️ 本项必须在 **Redis 可用**下断言（降级路径的超发上界见 AR-024，🔴 不得在降级态跑本用例并判缺陷） | §7.15.3 步骤 4、AC-QUOTA-009 |
| **K10** | 🔴 **租户时区与 DST**：`Asia/Shanghai` 当地 23:59:59 前用尽 → `exhausted`；跨过当地 00:00 后可用且 `periodStart`/`resetsAt` 切换、`used` 从新日计算；🔴 必含**一个 DST 时区**用例断言额度日长度为 **23h / 25h**（🔴 非固定 24h），并含**一个"当地 00:00 不存在"**（spring-forward 落在 00:00，如 `America/Havana`）用例断言相邻窗口首尾相接；🔴 `resetsAt` 为 UTC 绝对时间、同时返回 `timezone` | §7.15.6、AC-QUOTA-003 |
| **K11** | 🔴 **时区/配置失败 fail-closed**：租户 `timezone` 置为非法值（如 `Asia/Atlantis`）→ 发送与额度查询均 `50003`，🔴 **不得**回落 UTC 继续生成；平台默认三键任一缺失/非法 → 🔴 **启动失败**；租户覆盖列非法（如 `qpm_limit=0`）→ `50003`，🔴 **不得**静默继承平台默认；🔴 所有相关错误响应**不含**表名/键名/内部取值 | §7.15.5、AC-QUOTA-014 |
| **K12** | 🔴 **接口与隔离**：`GET /api/v1/me/quota` 🔴 **无任何参数**，构造 `?tenantId=` / `?uid=` / 跨租户会话 ID 均无法读到他人额度（参数被忽略 + 安全日志）；同一 uid 在 gift / redbook 的 `used` 与 `limit` **完全独立**；🔴 匿名调用返回 `20001/20002`（**不返回** unlimited），且前端在 `uid === null` 时 🔴 **不发起该请求**（网络面板反向断言） | §7.15.1、AC-QUOTA-013 / 016 |
| **K13** | 🔴 **快照形状可断言**：响应 `data` 键集合**恰为** 9 项（`enabled/limit/used/remaining/status/periodStart/resetsAt/timezone/asOf`）—— 多一键或少一键均为缺陷；`enabled=false` 时 `limit=null` + `remaining=null` + `status=unlimited` 且 `used` 仍为**真实**已结算数（🔴 不为伪造的 0）；🔴 不含 QPM 阈值 | §7.15.2 |
| **K14** | 🔴 **配置即时生效（无缓存）**：DBA 直接改 `tenant_quota_policies`（新增一条 `effective_at <= now` 的行）后，🔴 **无需重启、无需失效缓存**，下一次尝试即按新策略；插入 `effective_at` 为**未来**的行对当前尝试**无影响**；当日下调 limit ≤ used → 立即 `exhausted`，上调后按新总量恢复且历史 `used` 未被重算 | §7.15.5 ⑤⑥、AC-QUOTA-015 |
| **K15** | 🔴 **运行时状态键不被误删**：`POST /api/v1/platform/cache/evict`（含 `scope=all`）🔴 **不删除** `quota:day:*` 与 `quota:hold:*`（与 `chat:idem`/`chat:cancel`/`tool:confirm`/`limit:msg` 同规格）—— 删除等于免费重置额度 | §7.2.1、architecture §12.2 |
| **K16** | 🔴 **持久化与恢复**：结算后 `user_daily_quota_usages.settled_count` 与快照 `used` 一致；🔴 人为删除 Redis 计数镜像键后，下一次预检/预占**从 DB 重建**且 `used` 不回退为 0（反向断言"Redis 丢数据 = 白得额度"这一漏洞不存在）；🔴 零新增 audit action（`AuditActions.ALL` 仍恰 12 项）、零新增埋点事件名、零 SSE 字段变更（`done` 帧键集合与 M3 逐字一致） | §7.14 V1.2.5 框、§7.15.4、ADR-020 ⑦⑨ |

**L 组｜传输层契约与测试替身保真度（🔴 V1.2.7 新增 L1~L7；🔴 全部为签署前置门禁，任一 ❌ 即不予签署；A~K 组编号与判据零变化）**

> 🔴 **本组为什么必须是门禁而非普通核对项**：BUG-QUOTA-001 在 **1166 passed / 0 failed** 下存活至今，
> 与 BUG-MCP-004（桩上游不校验消息形态）**同源** —— 替身比真实对端宽松，缺陷只能靠人工浏览器发现。
> 因此本组的目标不是"修好这一处"，而是 🔴 **让这一类缺陷在测试阶段必然变红**（同 J12 的定位）。
>
> 🔴 **证据力规则（本组的判据基础，AR-029 ①）**：
> 凡断言对象是 **HTTP 报文形态**（状态码 / `Content-Type` / 键集合 / 是否空体），
> 🔴 **MockMvc 与前端 E2E 桩一律不构成证据** —— MockMvc 不经过 Tomcat 的 `/error` 二次分派，
> E2E 桩返回的是"应该的"而不是"实际的"。此类断言必须由**真实 HTTP 栈**用例背书。

| # | 判据 | 依据 |
|---|---|---|
| **L1** | 🔴 **MockMvc 层复现与回归（必须）**：既有 SSE 端点 IT 的请求构造**全部**带上 `Accept: text/event-stream`，在此前提下建流前失败仍 `status=200` + `content-type` 含 `application/json` + `code` 正确。🔴 **判定**：MockMvc **确实执行内容协商**（`HeaderContentNegotiationStrategy` 读 `MockHttpServletRequest` 的 `Accept`，走真实 `RequestMappingHandlerAdapter`），故本项**有效** —— 🔴 不得以"MockMvc 不做内容协商"为由跳过。修复前该用例**必须先红**（未先红即说明头没生效） | §1.2.1、ADR-021 |
| **L2** | 🔴 **真实 HTTP 栈背书（必须，🔴 不可由 L1 替代）**：新增至少一条 `@SpringBootTest(webEnvironment = RANDOM_PORT)` + JDK `HttpClient`（或 `TestRestTemplate`）用例，带 `Accept: text/event-stream` 触发 `10005`，断言 **HTTP 状态码 = 200**、`Content-Length > 0`、`Content-Type` 含 `application/json`、body 四字段齐备且 `data.retryAfterSeconds ≥ 1`。🔴 **不可替代的理由**：本次的"500 + 空体"是 **Tomcat + `BasicErrorController` 二次协商**的产物，MockMvc 物理上观测不到 | AR-029 ①、ADR-021 |
| **L3** | 🔴 **全局性证明（必须）**：L2 的同形断言必须覆盖 **≥2 个不同错误码**，且其中 🔴 **至少一个非限流码**（建议 `10001` 缺 `Idempotency-Key`；可选 `30070` / `10004`）—— 证明修复是 advice 层**全局**生效，而非只治了 `10005` | ADR-021 备选 (a) 否决理由 |
| **L4** | 🔴 **`Accept` 无关性（必须）**：同一失败场景分别以 `Accept: */*`、`application/json`、`text/event-stream` 发起，三者的**状态码 + `Content-Type` + body 键集合 + `code`** 必须一致（`retryAfterSeconds` 等动态值除外）；🔴 反向断言：任一 `Accept` 取值下出现 **406 / 415 / 5xx / 空体**即缺陷 | §1.2.1 不变量 ① |
| **L5** | 🔴 **反射式机械化守护（必须，🔴 防未来新增端点复发）**：扫描全部返回 `SseEmitter` / `ResponseEntity<SseEmitter>` 的 handler 方法，其集合必须 **⊆** 已被 L1/L2 类用例覆盖的端点集合 —— 新增 SSE 端点未覆盖即 🔴 **测试红**（守护范式同 D4）。🔴 配套：SSE 端点的测试请求构造收敛到 `testsupport` 的**单一 helper**并由它**强制注入** `Accept: text/event-stream`（🔴 逐个用例手加头的做法**不被接受**，它必然漏） | AR-029 ②③ |
| **L6** | 🔴 **实现纪律的反向断言（必须）**：ⓐ `ChatController` 的两个 SSE 端点 🔴 **不得**声明 `produces`（静态检查/反射；声明即让 `Accept: application/json` 在 handler mapping 阶段 406）；ⓑ 代码库中 🔴 **不存在** `SseEmitter.completeWithError(`（静态扫描；它会触发错误分派把 JSON 写进已提交的 SSE 流）；ⓒ `AsyncRequestTimeoutException` / `AsyncRequestNotUsableException` 两个处理方法 🔴 **仍为 `void`**（J3 的既有断言不变，🔴 不得因本次改造而被改成返回 `ResponseEntity`） | `architecture.md` §9.3.1、J3 |
| **L7** | 🔴 **前端零改动与探测器保留（必须）**：ⓐ `streamRequest.ts` 的 `!response.ok → NetworkError` 分支 🔴 **必须保留**（静态检查/单测）—— 🔴 **禁止**改为"非 200 也解析 JSON body"（那会把后端契约违反永久掩盖）；ⓑ E2E 桩**无需修改**（其 `200 + application/json` 恰是修复后的正确行为），但 🔴 必须在该 spec 顶部注明「本桩不模拟内容协商，**不覆盖** L1~L4，传输层形态以后端 L2 为准」，防止后人误当作证据；ⓒ 真实浏览器复验：QPM 超限时 console **0 error**、Composer 进入秒级倒计时、草稿保留 | AR-029 ①④、V5.0 BUG-QUOTA-001 期望结果 |

---

## 9. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| **V1.3.0** | **2026-08-24** | **🔴 删除「工具调用确认」与「工具风险等级」功能（产品决策：工具直接自动执行，风险等级机制一并移除；🔴 删除 1 个接口、1 个错误码、3 个 `sys_config` 键、4 个 audit action、多个 DDL 列）：**<br>① **🔴 §7.1.1 接口总表删除第 9 行** `POST /api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm`（工具确认接口整体移除，§7.8.2 确认契约随之一并作废）<br>② **🔴 §2.1/§2.2 删除错误码 `30055 TOOL_CONFIRM_CONFLICT`**（确认冲突码随功能删除；🔴 错误码序号**不复用**，`30054` 与 `30056` 之间留空）<br>③ **🔴 §7.1.2 删除 3 个 `sys_config` 键**：`tool.confirm_wait_seconds` / `tool.confirm_poll_interval_millis` / `display.tool_risk_labels`（键总数 35 → **32**；`StartupChecker.REQUIRED_CONFIG` 同步移除）<br>④ **🔴 §7.14 删除 4 个 audit action**：`tool.confirm_allowed` / `tool.confirm_denied` / `tool.confirm_timeout` / `tool.confirm_conflict`（action 总数 12 → **8**）<br>⑤ **🔴 §7.8.1 状态机删除 `awaiting_confirmation` 状态**（状态总数 8 → **7**）：`pending → running | denied | failed | cancelled`，删除 `awaiting_confirmation → …` 全部流转；`timed_out` 语义收窄为**仅**执行超时（`30051`/`30056`），删除"确认等待超时 `30050`"语义<br>⑥ **🔴 §7.7.1/§7.7.3 删除 `riskLevel` 字段与风险矩阵**：`local_tools.risk_level` / `mcp_tools.risk_level` / `tool_calls.risk_level` / `tool_calls.requires_confirmation` / `tool_calls.decision` / `tool_calls.decided_by_uid` / `tool_calls.decided_at` 共 **7 列删除**；工具策略退化为二元判断（`tool_policy != 'disabled'` 即进清单，`confirm` 取值兼容存量数据、语义等价 `auto`）<br>⑦ **🔴 §5.2 `tool` 事件删除 `riskLevel` / `confirmExpiresInSeconds` 字段**（字段 12 → **10**）；`CUSTOM("tool_progress")` 的 value 同步删除这两字段<br>⑧ **🔴 DDL 变更**：`tool_calls` 表删除 `risk_level` / `requires_confirmation` / `decision` / `decided_by_uid` / `decided_at` 列；`local_tools` / `mcp_tools` 删除 `risk_level` 列（`ddl-auto: validate` 下多余列可先行保留，物理删除由 DBA 执行）<br>⑨ **🔴 语义变更（删除确认后的行为）**：原本需确认的工具（`high` 风险 / `confirm` 策略下的 `medium`）改为**直接自动执行**；工具执行流程中的"等待用户确认"步骤整体移除，`ToolOrchestrator` 校验顺序由 ⑦ 步收窄为 ⑥ 步（删除「风险 × toolPolicy 判定」步）<br>⑩ **自查结论**：本版**零新增**接口/错误码/键/action；`done 必发`、响应体四字段、租户隔离、反硬编码、deadline 预算制、单一前导 `system` 六条红线**全部未放宽**；SSE 事件名仍恰 5 个（`meta`/`delta`/`tool`/`error`/`done`） |
| V1.0 | 2026-08-12 | 首版：通用约定、错误码登记表、M1 全量接口、SSE 事件契约、M2/M3 路径占位 |
| V1.0.1 | 2026-08-12 | M1 回归遗留处置：§4.2.1 增加 `/site/status` 路径易误用警告（不带 `/api/v1` 前缀，误用会得到 `10004`） |
| **V1.1** | **2026-08-13** | **M2-min + M3 正式契约落地（本版），@后端 可据 §7 实现：**<br>① **修复自相矛盾**：§1.4 confirm 行的悬挂引用（原指向一个并不存在的章节编号）改为 §7.8.2，路径与 §7 统一为 `/api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm`<br>② **错误码闭合**：§2.2 正式登记 `30053`/`30054`/`30055`/`30056`/`30057`/`30060`/`30061`，与 §2.1 子段「已用」列逐一对应；`10005` 明确 `data.retryAfterSeconds` 必填<br>③ **§7 由占位升级为正式契约**：新增 `POST /api/v1/platform/cache/evict`、`POST /api/v1/admin/config/validate`、`GET/POST /api/v1/admin/mcp/{mcpId}[/test|/discover]`、`POST /api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm`、`GET /api/v1/conversations/{conversationId}/tool-calls`、`POST /api/v1/events`、`GET /api/v1/admin/metrics/usage` 的完整字段契约、错误码清单与 REQ/AC 追溯；补 Skill 运行时消费契约（无对外接口）、MCP JSON-RPC 与传输取舍、本地 Tool 注册/授权/风险矩阵、内置 Mock MCP、§7.1.2 新增 22 个 `sys_config` 键<br>④ **§5 SSE 扩展**：`tool` 事件新增 `riskLevel`/`round`/`argsSummary`/`resultSummary`/`truncated`/`errorCode`/`retryAfterSeconds`，`error` 新增 `retryAfterSeconds`；新增 §5.4 向后兼容硬约束、多轮工具时序示例、摘要脱敏规则；`tool.summary` 永久保留<br>⑤ **§6 标注 Boss 决策**：M2 管理后台全量 Deferred 至二期，新增「一期状态」列并标注已提升为 M2-min 的条目<br>⑥ **§8 增补 M2-min/M3 核对项**：工具事件字段、confirm 幂等语义、埋点白名单、租户隔离、SSRF 运行时兜底、审计不阻断 SSE、缓存 L1+L2 成对失效<br>⑦ **§7.14 固化事务边界**：非流式安全操作同事务（失败关闭，EX-024）；流式内安全事件独立短事务，审计失败使该次工具调用失败但**绝不中断 SSE** |
| **V1.1.1** | **2026-08-13** | **与 `architecture.md` V1.3 对齐的 9 处定点回写（🔴 接口清单与错误码零新增、零 `sys_config` 新键；仅消除矛盾与补齐口径）：**<br>① **§7.5.1 Skill 表结构订正（影响 DDL）**：改为 `skills` 主体表 `uk_tenant_skill_key(tenant_id, skill_key)` + `skill_versions` `uk_tenant_skill_version(tenant_id, skill_id, version)`，并登记 `skills` 主体表列；原将 key 唯一键标在 `skill_versions` 与「版本递增 + 发布后不可变」矛盾（与 §13.5.2 一致）<br>② **§7.7.1 本地 Tool 版本语义定义（影响 DDL）**：`local_tools` 为**单行表 + `uk_tool_key` + `version` 就地递增**（非多版本行）；`agent_capability_bindings.ref_version` 对 localTool = 绑定时注册版本的**审计快照**，🔴 不参与运行时解析（运行时读当前行），并标明与 Skill「精确版本引用」的差异（与 §13.5.5 一致）<br>③ **§7.11.1 `rateLimitedCount` 口径**：一期**恒返回 0 且不得伪造**，写明三条原因（限流仅 Redis 计数不落库 / 限流发生在建消息之前 / 白名单无对应事件名）；登记二期方案 (a) 前端上报 `messageRateLimited` 与 (b) 后端侧写 `analytics_events`，标注「二期决策项」待 Boss 裁决（与 §13.5.8 一致）<br>④ **§7.6.3 pin IP 措辞改为可落地实现（影响实现）**：JDK17 无 `InetAddressResolverProvider`、`Host` 为受限头、IP-URL 破坏证书校验，故严格 pin IP 不可行；改为等价实现「解析后逐 IP 校验 → 以**原域名**连接保住 TLS 主机名校验 → `-Dnetworkaddress.cache.ttl=10` 收窄 TOCTOU → **每次调用前重校验** → 不跟随重定向」，注明残余风险 AR-009 与二期升 JDK18+ 换 `InetAddressResolver` 的路径；同步订正 §8.2 对应核对项（与 ADR-009 一致）<br>⑤ **§7.11.1 denied/failed 聚合口径**：`toolDeniedCount = status='denied' OR (status='timed_out' AND errorCode=30050)`，`toolFailedCount` = 其余失败态（`failed` + `timed_out` 且 `errorCode ∈ 30051/30056`）；逐状态列出归属并给出「两者之和 + succeeded + cancelled + 非终态 = toolCallCount」的互斥不变量，杜绝重复计数或漏计；同时补全 `messageCount`/`conversationCount`/`activeUserCount`/`tokenUsage`/`toolCallCount` 数据源（与 §13.5.7 一致）<br>⑥ **§7.4.1 凭据契约补全**：`credential.keyVersion` 示例 `2 → 1`，明确一期**恒为 1**（白名单只有单密钥 `app.crypto.secret`，无轮换能力；读到非 1 → `30060`），版本前缀仅为二期轮换预留；新增两条硬契约 —— 密文编码格式 **`v{keyVersion}:{base64url(iv)}:{base64url(ct‖tag)}`** 与 **AAD = `mcp:{tenantId}:{mcpKey}`**（防密文跨租户搬运，含租户号/`mcpKey` 变更须重新加密的副作用）（与 ADR-012 一致）<br>⑦ **`auditEventId` 格式定义**：统一为 **32 位 UUID hex（小写、无连字符，`^[0-9a-f]{32}$`）对外原样返回、禁止截断**；订正 §7.2.1（2 处）、§7.4.2、§7.8.2 的 8 位短串示例，并在 §7.14 不变量登记为第 5 条（与 §11.1.2 一致）<br>⑧ **§5.4.2 首字 P95 观测锚点**：锚点改为**首个用户可见帧（`delta` 或 `tool`，取先到者）**；原以首个 `delta` 为锚点会使「首轮即工具调用」场景永远不可断言。明确 `meta` 与 `: ping` 不作锚点、确认等待与工具执行不计入首字；📢 已标注 **@测试 需同步 test-plan 的性能断言口径**（两条路径同锚点，禁止只监听 `delta`）；同步增补 §8.2 核对项（与 §9.5.3 一致）<br>⑨ **缓存 TTL 键缺口收口**：Skill 版本快照 / MCP 工具授权清单 / 本地 Tool 授权与能力绑定 一期**不缓存、直读 MySQL**（更符合 AC-CFG-004 / AC-MCP-004「改库即生效」）；在 §7.1.2 登记为「**预留 — 一期禁用**」并 🔴 明令禁止自造 TTL 键，在 §7.2.1 失效清单注明这些作用域**一期无需失效**（并重申运行时状态键 `chat:idem/cancel`、`tool:confirm`、`limit:msg` 禁止被 evict）；二期启用需同时补 §7.1.2 TTL 键与 §7.2.1 失效清单（与 §12.1.1 / §12.2 一致） |
| **V1.1.2** | **2026-08-13** | **@后端 M3 能力层 12 项契约缺口 G1~G12 的逐条裁决回写（🔴 零新接口、零新错误码、零新 `sys_config` 键、零 DDL 变更；新增 1 个 audit action）：**<br>① **G1（采纳并升格）§7.4.4 定义 `agent_capability_bindings` 的 `ref_id`/`ref_version` 三类语义**：`skill` = `skills.id` + **精确版本参与解析**；`mcpTool` = `mcp_tools.id` + 批次号**不参与解析**；`localTool` = 🔴 `tenant_tool_grants.id` + 注册版本**审计快照不参与解析**。该表已在 `architecture.md` **§13.5.10** 正式登记（含 as-built DDL，与 @后端 实建结构逐字一致，**无需改表**）；补 🔴「授权调整禁止 `DELETE+INSERT`」运维纪律（否则绑定悬挂 → fail-closed）<br>② **G2（修正）§7.4.2 新增第 9 个诊断字面量 `connect_failed`**（连接被拒 / 网络不可达 / 连接重置），与 `timeout`（无响应超时）给出判别口径 —— 归入 `timeout` 会把 DBA 导向错误排查方向，削弱本接口的诊断价值；`last_check_result` 为 `VARCHAR(32)`，无需改表<br>③ **G3（修正）§7.4.3 + §7.14 新增 audit action `mcp.tool_grant_revoked`**（`reason=schemaChanged`/`toolRemoved`，`actorType=system`、`objectType=mcpTool`、`result=success`、随 `discover` 同事务），并以对照表说明**为何不能复用** `tool.grant_denied`（actor/object/result 三者语义全不同，复用会淹没真实越权事件并破坏 `idx_object` 追溯）<br>④ **G4（修正）§7.5.3 变量优先级重写，消除自相矛盾**：内置变量 `tenantId`/`locale`/`timezone`/`nowIso` 升为 **⓿ 独立命名空间、不可被覆盖**；`variable_values` 同名键 🔴 **忽略 + WARN（不报错，fail-safe）**；写明安全影响（允许覆盖 `tenantId` 等于提示词层面的身份伪造，与 EX-003 同一条防线）与替代做法（用非保留名变量）<br>⑤ **G5（按预裁决登记 + 一处明确反对）§7.7.1 新增「一期平台内置本地 Tool 清单」**：`datetime_now` / `calculator`（`low` + 幂等 + 纯函数 + 5s），给出参数 Schema 要点（阈值一律写在 `local_tools.input_schema`，不新增 `sys_config` 键）、🔴 严禁 eval 类设施、🔴 一期不支持幂运算（CPU 放大面）、错误映射（`30053`/`30057`）与审计口径（🔴 low 风险纯函数**成功执行不写审计**，可追溯性由 `tool_calls` 承担）；§7.4.3 补 **G5-b** MCP `riskLevel` 授权期写入契约（🔴 授权时可指定，**无需新增字段**；🔴 **反对**把默认值改为 `medium`，默认恒 `high`，fail-safe 方向不可调转）—— 决策记录见 `architecture.md` **ADR-015**<br>⑥ **G6（采纳）§7.6.1 `sse` 传输一期只支持"POST 响应内同步返回结果"形态**：只回 202 / 需从独立 GET 流取结果 → `30052` / `protocol_incompatible`；🔴 禁止为兼容旧形态引入常驻读取线程或第二个线程池（与 ADR-008 第 8 条冲突）<br>⑦ **G7（采纳并固化）§7.6.1 新增「超时口径」表**：建连超时 = `app.ai.connect-timeout-seconds`（基础设施级、全局唯一 `HttpClient` Bean、JDK17 无法按请求覆盖）；`mcp.connect_timeout_seconds` 的准确语义 = **握手/连接测试阶段的请求级超时**；给出运维不等式与"为 MCP 单建第二个 HttpClient"的否决理由（两套连接池会让 `followRedirects=NEVER` 存在漏配风险）<br>⑧ **G8（采纳）§7.7.3 登记本地 Tool 超时 = 计时判定 + 如实上报、不强制中断**（强制中断需第二线程，与"不新增线程池"冲突）；对照 MCP 的真超时；实现体必须短/纯/不阻塞；一期残余风险因 G5 的纯函数选型**实际为零**（ADR-008 第 8 条补注 + AR-014）<br>⑨ **G9（采纳）§7.4.2 + §7.6.2 明确一期不维护 MCP 会话**：不发 `initialize`、不维护 `sessionId`，握手以 `tools/list` 成败代表；上游强制要求 → `protocol_incompatible` / `30052`，🔴 禁止私自引入会话状态<br>⑩ **G10（采纳但附收尾期限）§7.3.1 登记 `agentVersion → 绑定` 递归校验**：允许分阶段，但 🔴 **M3 签署前必须补齐**（AC-CFG-003 唯一判据）；给出三类绑定的递归校验清单；未补齐前必须在 `warnings[]` 明示 `rule=referencesNotFullyChecked`，🔴 禁止伪报 `valid=true` 的全量通过<br>⑪ **G11（修正）** `architecture.md` §5.1.2 补画 `platform` 层位并登记 `skill → platform`；🔴 必须经 `TenantService`，禁止直连 `TenantRepository`（api-spec 侧无接口影响）<br>⑫ **G12（采纳）§7.13 新增「配置覆盖方式」两行**：test 侧宽松值只允许 `SysConfigOverride` 方法级临时覆盖 + `try-finally` 还原，🔴 严禁持久化写入共享库；生产由 `StartupChecker` 启动即拦死作为兜底<br>⑬ **§8.2 增补 8 条核对项**（G1~G9 对应断言） |
| **V1.1.3** | **2026-08-13** | **@后端 M3 第三阶段（流编排 + 高风险确认闭环）5 项契约缺口 ①③④⑤⑥ 逐条裁决 + 2 项收尾判定（🔴 零新接口、零新错误码、零 DDL 变更；新增 1 个 `sys_config` 键 + 1 个 audit action）：**<br>① **①（采纳并本期实现，🔴 附一处契约错误订正）§7.5.2 第 3 条重写**：原文「超出 `agent_versions.max_output_tokens` 对应的上下文预算」**不可实现且语义错误**（`max_output_tokens` 是**输出**上限，DDL 默认 4096，与输入上下文无换算关系），本版作废该措辞。改为 **字符（Unicode 码点）预算**：新增 `sys_config: chat.system_prompt_max_chars`（默认 `100000`，§7.1.2），判定对象 = `system_prompt` + 全部绑定 Skill 的 `instruction` + `output_constraint`（🔴 按**变量替换后**长度计），超限 → `30060 rule=systemPromptBudgetExceeded`；🔴 **明确否决"只截断最低优先级片段"**（注入顺序是语义依赖链，截掉 `output_constraint` = 静默降级，与本节硬约束 3 冲突）；说明**不用真实 tokenizer 的三条理由**（混元无本地 tokenizer / 引入 jtokkit 属新增依赖且词表不匹配 / 本键职责是消灭"多 Skill 叠加无上限"的 fail-open 而非精确匹配窗口）与**不影响首字的三条理由**（O(n) 边拼边累加即短路、发生在异步段不占首字预算、只做二元判定从不裁剪）；登记键取值不变量 **≥ `skill.instruction_max_chars`**（违反则 `StartupChecker` WARN，不拒绝启动）<br>② **③（🔴 修正，不采纳 @后端 归一化）§7.8.1 状态机增补 `running → denied`（`errorCode=30050`）**：执行期竞态（preflight 通过后 DBA 撤授权 / 改内网地址）🔴 **不得**归一化为 `failed + 30052` —— 那会把**安全拒绝**伪装成**上游故障**，把 DBA 导向查网络的错误方向；并澄清 @后端 的顾虑不成立：§7.11.1 互斥不变量按 **`status`** 二分，新增一条**进入** `denied` 的路径**不改动任何聚合公式**（§7.11.1 补第 4 条注，公式原样保留），反而归一化才会让被撤授权的调用错计进 `toolFailedCount`；审计沿用 `tool.grant_denied` / `mcp.ssrf_rejected`，零新增 action<br>③ **④（🔴 修正，收窄）§7.8.2 + §7.14 确立「审计只记新事实」**：confirm 幂等回放（`replayed=true`）🔴 **不写** `audit_logs`（回放未改变任何服务端状态，写入的是访问日志语义）；关键理由 = confirm **不使用 `Idempotency-Key`** 且回放返回 `code=0`，多标签页/抖动重试/连点会把单次确认放大成几十条同 action 审计，淹没真实越权事件（同 §7.7.1 low 风险工具不写审计的抗噪原则）；配套契约：🔴 `replayed=true` 时 `data.auditEventId` **恒为 `null`**（禁止编造、不回查），首次决定仍必返 32 位 hex；WARN 日志保留<br>④ **⑤（🔴 采纳新增）§7.14 + `architecture.md` §11.1.1 新增 audit action `tool.confirm_conflict`**：`30055` 必须留痕（`actorType=endUser`/`objectType=toolCall`/`result=denied`/`errorCode=30055`，confirm 请求短事务，`before_digest`=既有决定、`after_digest`=被拒提交值）—— "翻转一个已生效的高风险决定"是安全相关行为，不留痕等于放弃举证能力；🔴 防刷：同一 `toolCallId` **至多一条**（行锁内按 `idx_object` 点查去重，重复提交仍返 `30055` 但不再写入）；🔴 不改动 `tool_calls` 任何列（已终态，改动即篡改历史并破坏聚合）<br>⑤ **⑥（追认并升格为正式契约）新增 §7.6.5「模型函数名归一化与回映射」**：逐字符映射（`[a-zA-Z0-9_-]` 保留，其余一律 `_`，🔴 不压缩连续下划线 —— 压缩更易碰撞），`crm:lookup → crm_lookup`；🔴 >64 字符 → `30060 rule=functionNameTooLong`（**拒绝不截断**）；🔴 碰撞 → `30060 rule=functionNameCollision`（fail-closed）；🔴 回映射**必须靠 `Map<functionName, 定义>`**（`_ → :` 不可逆，严禁字符串还原）；🔴 `functionName` 为**内部标识符**，SSE / `tool_calls` / §7.9.1 / 审计一律记原始 `toolKey`；否决"截断 + hash 后缀"（产出人类不可读函数名，无法把日志对回 `toolKey`）；归属：归一化与碰撞判定归 `tool`（唯一看得到全清单者），映射表归 `chat`（按本次生成持有）<br>⑥ **G10 收尾（🔴 维持"M3 签署前必须补齐"，不放宽不延期）§7.3.1 补齐后行为定义**：🔴 递归深度**固定 2 层**（禁止通用图遍历，数据模型无第 3 层）；循环引用防护 = `visited` 集合 + 检测到环 → `30060 rule=circularReference`（禁止靠栈深度兜底）；查询收敛 ≤4 次（禁 N+1）；🔴 递归必须一并覆盖**只在聚合层面可见**的两项新校验（① 的 system 提示预算、⑥ 的函数名长度/碰撞）；🔴 `warnings[] rule=referencesNotFullyChecked` 的消失规则精确化 —— **当且仅当** `includeReferences=true` 且三类绑定全部递归时消失，`includeReferences=false` 时**必须继续出现**（否则调用方无法区分"没查"与"查了没问题"）<br>⑦ **§7.12 判定：SSE 内 `error.retryAfterSeconds`（`10005`）= 契约预留，🔴 不是契约错误**：限流在**建流之前**执行且一期**无任何流内限流点**（与 §7.11.1 `rateLimitedCount` 恒 0 同源）；保留字段因删除会破坏 §5.4.1「字段只增不改不删」；🔴 @测试 **不得**为该路径写用例、**不得**判缺陷（test-plan 中标 Deferred-in-M3），🔴 @后端 **不得**为制造可达性在流内新增限流点（属新增业务约束，需 @产品经理 立需求 + 登记新键，二期）<br>⑧ **§7.1.2** 新增 1 键（键总数 22 → **23**，全部纳入 `StartupChecker.REQUIRED_CONFIG`）；**§8.2 增补 7 条核对项**（①③④⑤⑥ + G10 + §7.12 对应断言） |
| **V1.1.4** | **2026-08-13** | **@后端 M3 第四阶段（`mvn -B verify` 单测 240 / 集成 227 全绿）7 项契约缺口 #1~#7 逐条裁决 + M3 签署清单（🔴 零新接口、零新错误码、零 DDL 变更；补登 2 个 `sys_config` 键，键总数 23 → **25**；零新增 audit action）：**<br>① **#1（🔴 采纳登记 + 纳入 `REQUIRED_CONFIG` + 否决 fail-open）§7.1.2 正式补登 `observability.analytics_enabled`（BOOLEAN，默认 `true`）与 `observability.analytics_sample_rate`（NUMBER，默认 `1.0`）**：两键此前被 §7.1.2 末注**误认定**为"沿用既有键"，实则从未出现在本表、也从未出现在 `architecture.md` §13.3/§13.6，库内亦无行 —— 属**登记缺口**，本版已从该注移出并正式登记 + 同步 `architecture.md` §13.6（键总数 **25**）；✅ **纳入 `StartupChecker.REQUIRED_CONFIG`**（"契约锁定 23 键"不构成理由：23 是契约的产物而非约束，且 §13.6 纪律 4 已禁止业务参数用代码默认值兜底）；🔴 **读取侧由 fail-open 改为 fail-closed**（`analytics_enabled` 缺失/不可解析 → 按 `false`；`sample_rate` 缺失/越界 → 按 `0.0`；日志级别 WARN → **ERROR**）—— 理由：fail-open 会让**唯一的采集关停手段**在配置丢失时静默转为**全量采集**，与 PRD §15.2「采集受控、采样可控」直接冲突；🔴 确立一般原则「**"要不要拦"的开关可 fail-open（如 §7.12 限流遇 Redis 故障放行），"要不要采/写"的开关必须 fail-closed**」，判据 = 可恢复的功能损失 < 不可撤销的隐私损失；新增取值不变量 `sample_rate ∈ [0.0, 1.0]`，🔴 违反时 `StartupChecker` **拒绝启动**（⚠️ 与 `chat.system_prompt_max_chars` 的 WARN 规格**有意不同**：区间外的采样率没有任何合法语义）<br>② **#2（🔴 修正，不采纳 @后端 的"运行时复用校验入口形状"）§7.3.1 末新增「`30060` 载荷形状按调用者身份二分」+ §5.2 补注**：🔴 判据是**"谁能看到这段 data"**，不是"哪个错误码" —— **A 类管理端**（`@Permission(ADMIN)` / `@TenantRole` 保护：§7.3.1 / §7.4.2 / §7.4.3）**必须**给完整 `{objectType,objectId,valid,checkedObjects,violations[],warnings[]}`；🔴 **B 类终端用户路径**（SSE `error` 事件、`/conversations/**`、`/messages/**`）**仅** `code + message`（`data=null`），因为 `violations[]` 含内部对象 ID 与配置结构，下发即把租户配置拓扑泄露给任意登录用户；配套三条：`message` 为通用语义（不含 `objectId`/`rule`/字段名）、🔴 诊断不丢失（同一次失败必须 ERROR 日志记 `requestId + tenantId + agentVersion + rule + objectType:objectId`，DBA 凭 `requestId` 反查）、字段级出口唯一 = §7.3.1（这正是 G10 递归校验的意义：B 类只报"有问题"，A 类回答"哪儿有问题"）；👉 @后端 现状（运行时仅 `code + message`）✅ **正确、无需返工**，本版将其从临时口径升格为正式契约并**禁止**未来"补齐 `violations`"的反向改动；同步 §5.2 增补「`error` 事件字段恒为三项，`code=30060` 亦不得携带字段级明细」<br>③ **#3（❌ 否决"运行时收敛到 ≤4"；✅ 订正文档口径）§7.1.2 新增「查询次数口径」表**：原文"清单构造 ≤3 次"**是错的** —— 遗漏了两次不可省略且依赖前一次结果集的二级查询（`mcp_tools → mcp_servers` 取 status/endpoint/transport/凭据；`tenant_tool_grants → local_tools` 取实现体/`input_schema`/`risk_level`/timeout），不 join 则不可能 ≤3；🔴 因此这是**文档错、实现对**，订正文档而非为对齐一个错误数字去动 M3 最热的首字链路。新表固化三档：**运行时清单构造 ≤5 次**（五次批量查，序列已列明）／**校验入口 ≤4 次**（3 条 `left join`，QPS 极低故 join 收敛是加分项）／**每次工具执行前的授权点查 ≤1 次**（#4 新增，按"每次执行"计、不与清单构造叠加统计）；🔴 两入口共同的硬禁止仍是 N+1 逐个绑定单查<br>④ **#4（🔴 采纳补点查，认定为"订正实现缺口"而非新增要求）§7.6.3 新增「第 2/3 步的执行时点 = 每次工具执行前（置 `running` 之前）」裁决框 + 契约表**：本节标题自 V1.1 起即写明「**每次调用前**的强制校验顺序」，第 3 步本身就是 `granted` 点查 —— 只在清单构造时做属未完整实现既有契约，且直接违反明文 **AC-MCP-004**「取消授权后运行时仍须拒绝」；🔴 澄清 @后端 的 D-004/D-006 顾虑不成立：那两条纪律约束的是**租户识别 + 配置读取的 20ms 热路径**与**清单构造的 N+1**，而本点查发生在**异步段、首个可见帧之后**，既不在 20ms 红线内也不受首字 P95 约束，相对 MCP 一次网络往返（≤30s）可忽略；🔴 窗口不是毫秒级而是横跨整轮生成（含确认等待最长 120s + 最多 `tool.max_rounds` 轮），最坏数分钟，"紧急撤授权后还能执行数分钟"不可接受。契约固化：时点（置 `running` 前，多轮每轮各一次）／预算（🔴 ≤1 次点查，MCP 走 `mcp_tools JOIN mcp_servers`、本地 Tool 走 `tenant_tool_grants JOIN local_tools`）／两类判据（MCP 三条件 + server enabled；本地 Tool 即 §7.7.2 授权四条件）／失败处置（`pending→denied` 或 `running→denied`，一律 `30050` + `tool.grant_denied`，与状态流转同一独立短事务）／🔴 **残余窗口 = 单次工具执行时长**（本地 Tool 毫秒级；MCP ≤ `mcp.call_timeout_seconds`），判据为「撤授权后**新发起**的执行必须被拒、**已进入 `invoke`** 的那一次允许完成」，@测试 据此断言而非误判缺陷，已登记 `architecture.md` **AR-017**；🔴 明确不做：执行中中断（需第二线程，违反 ADR-008 第 8 条）、缓存点查结果（等于回到 fail-open）<br>⑤ **#5（✅ 追认并改文档）§7.8.2 ⑤ 事务边界由「confirm 请求短事务」改为「独立短事务」+ §7.14 新增第三行例外与不变量 7**：该路径**必然**以抛 `BusinessException(30055)` 收尾，同事务下 Spring 回滚会把审计一并抹掉（@后端 实测实得 **0 条**）—— "必须留痕"的 ⑤ 裁决在物理上自我否定，🔴 属**文档描述与物理现实冲突，必须改文档**；追认 `REQUIRES_NEW` 或等价实现，并固化：该路径**零业务写入**故不存在原子性需求（§7.14 第一行立意是"不得执行了业务却没审计"）／🔴 顺序必须**先提交审计、再抛 `30055`**／审计失败返 `50003` 且仍不得改动已终态行／去重点查仍在**行锁内**执行以保"至多一条"在并发下成立／🔴 对外可观测行为**零变化**（@测试 断言不变：30055 且审计恰好 1 行）；同步在 §7.14 增补**不变量 7「事务边界的选择判据」** —— 有业务写入需与审计原子绑定 → 同一事务；纯拒绝路径（零业务写入）→ 独立短事务，🔴 ⓑ 类适用清单当前**仅**此一处，新增必须先回写再实现<br>⑥ **#6（🔴 升格为全局纪律，安全漏洞级）§3 新增「`@TenantRole` 必须有程序化兜底，纯注解视为缺陷」**：`TenantRoleAspect` 由 `EyesAuthConfig` 装配，后者带 `@ConditionalOnProperty(eyes-auth.enabled=true)` —— 开关为 `false` 时（test profile 即如此，生产亦可能误配）**切面整个不注册**，`@Permission`/`@TenantRole` 退化为"看起来有准入、实际全放行"的装饰，接口直接以 `code=0` 返回租户数据且**不报任何错**（静默越权）；🔴 纪律四条：注解必须保留／方法入口必须做程序化 fail-closed 判定（`requireEnabled` → 取 uid（无 → `10003`）→ `ensureMembership` → 角色不足 → `10003`）／两条路径失败码必须相同（均 `10003`，保证开关状态不改变对外契约）／🔴 "只有注解没有兜底" = 安全等级缺陷，不因"只有测试环境会这样"而豁免；落地基线：抽 `auth/TenantRoleGuard` 统一实现并改造现有三处内联（`McpAdminController` / `ConfigValidateController` / `UsageMetricsController`）；✅ **要求静态扫描测试守护**（反射扫出的 `@TenantRole` 端点集合 ⊆ 已被兜底断言覆盖的集合，新增端点漏兜底即测试红，见 §8.3 D4）；生产侧另由 `StartupChecker.checkProductionBlockers()` 断言 `eyes-auth.enabled=true`，否则**启动失败**<br>⑦ **#7（✅ 确认无异议，维持 V1.1.3 判定）§7.12 / §5.2 标注不变**：SSE 内 `10005` 与 `tool.retryAfterSeconds` 为**契约预留、一期不可达**；🔴 @后端 **不得**为制造可达性在流内新增限流点，🔴 @测试 不写用例、不判缺陷（§8.3 H1 已登记为"允许 ⏸"）；未建流路径的 `code=10005` + `data.retryAfterSeconds` 仍为**必测**<br>⑧ **§8 新增 §8.3「M3 签署核对清单」**（@测试 逐条打勾即构成签署依据）：按 8 个主题聚合 **A** SSE 与工具事件 6 项 · **B** 高风险确认与幂等 6 项 · **C** 授权/SSRF/执行期竞态 7 项 · **D** 租户隔离与鉴权 5 项 · **E** 埋点与用量 11 项 · **F** 审计与事务边界 6 项 · **G** 配置治理与运行时兜底 11 项 · **H** 一期授权的 Deferred/不可达项 6 项（🔴 列入 H 表即"允许 ⏸"，不得判缺陷）；每行给出「判据 + 契约出处」，打勾规则 `✅/❌/⏸`，🔴 任一 `❌` 或未经授权的 `⏸` → @架构师 不予签署<br>⑨ **§8.2 键总数核对项** 23 → **25**（增列两键） |
| **V1.1.5** | **2026-08-13** | **M3 最后一轮契约订正（@测试 终验并行期提出的 G-0~G-5 逐条裁决；🔴 零新接口、零新错误码、零新 `sys_config` 键（仍 25）、零新 audit action（仍 12）、零 DDL 变更；§8.3 判据总数与编号不变，仍 58 项）：**<br>① **G-0（✅ 订正数量文字，最优先）§7.14 + §8.3 F3：审计 `action` 数量由"11 项"订正为 🔴 **12 项****。§7.14 与 `architecture.md` §11.1.1 两表本版已**逐行复核 —— 字面量与顺序完全一致、无出入**（本轮无需订正登记表本身）；错误只在 §8.3 F3 的**计数文字**（M3 期间新增 `mcp.tool_grant_revoked`（V1.1.2 G3）与 `tool.confirm_conflict`（V1.1.3 ⑤）后漏改计数）。🔴 固化断言方式：`audit/AuditActions.ALL`（实现侧已存在的 `Set<String>`，写入前强制校验）**集合恒等**于 §7.14 表的 12 个字面量，⚠️ `REASON_*` / `DIGEST_*` 常量不计入。🔴 并补纪律：新增 action 必须同时回写 §7.14 + §11.1.1 + §8.3 F3 计数<br>② **G-1（✅ 订正契约表述，🔴 明确否决 DDL 变更）§7.6.3 契约表 + §7.7.2**：`mcp_tools` / `tenant_tool_grants` / `local_tools` 三张表**无 `deleted_at` 列**（实建结构，仅 `mcp_servers` 有）。裁决订正判据 —— MCP 判据的 `deleted_at IS NULL` 🔴 **只作用于 `mcp_servers`**；本地 Tool 判据删除"两行 `deleted_at IS NULL`"（该措辞与同句"即 §7.7.2 的授权四条件"本就自相矛盾）。**否决加列**的三条理由：ⓐ 语义已由 `granted` / `status` 完整承载，且 §13.5.4 明文"`removed` 工具保留历史行置 `disabled`、🔴 不物理删除"（既然从不删行，`deleted_at` 恒 NULL = 永真条件）；ⓑ 与 §13.5.10 运维纪律冲突（已规定"调整授权一律 `UPDATE granted/status`、禁止 `DELETE+INSERT`"，再引入软删列等于给出第三种撤销手段且不被绑定悬挂检测覆盖）；ⓒ 代价（DDL + 重跑 `validate` + 改 4 处点查 SQL + 全量回归）与收益（0 个 AC 依赖软删）完全不对称，M3 终验期动 DDL 属不必要风险。配套在 `architecture.md` §13.5.4/§13.5.5/§13.5.6 按 §13.5.10 体例**显式豁免**软删约定（防 `ddl-auto: validate` 漂移与本争议重开）<br>③ **G-2（❌ 不要求复查绑定；✅ 将"绑定 = 生成期快照"升格为正式契约）§7.6.3 新增「复查范围」行 + G-2 裁决框 + §7.7.2 补注**：执行前点查**只复查授权/启用列**（`granted` / `status` / server `status`+`deleted_at`）与第 4 步 SSRF，🔴 **不复查** `agent_capability_bindings` / `agent_versions.tool_policy` / `input_schema` 变更；🔴 查询预算**维持 ≤1 次不放宽**。📌 顺带纠正一处技术判断：三表 join 在 SQL 上仍是 1 次，"突破预算"不构成理由，@后端 **无需为此返工** —— 真正理由是：ⓐ **语义自洽（决定性）**：工具定义在首帧前已交付模型，中途解绑会让模型按已知定义发起调用而执行侧拒绝，产生用户不可解释的 `30050` 且消耗 `tool.max_rounds`；一次生成内工具清单必须自洽（与 §7.5.2 "Skill 按 `ref_version` 取不可变版本"同一条生成期快照原则）；ⓑ **安全分层（决定性）**：绑定是**能力编排**，`granted`/`status`/server enabled 才是**授权闸门**，而执行前点查**已覆盖全部闸门**，复查绑定不增加任何授权维度防护、只增加不自洽的失败面。🔴 安全影响如实登记（`architecture.md` **AR-019**）：DBA 生成中解绑后**本轮剩余仍可执行**（窗口 = 本轮生成剩余时长，最坏 ≈ 确认等待 + 剩余轮次 × 单次执行），🔴 但**不是授权绕过**（该工具此刻仍 `granted=1`+`enabled`，只是不再编排给该 Agent 版本）；🔴 紧急止血的正确操作固化为运维纪律：**撤 `granted` / 停用 `status`（工具或 server）→ 下一次执行即 `30050`**，🔴 不得把"解绑"当止血手段（生效点是下一次生成）<br>④ **G-3（✅ 追认，🔴 不回退）§5.2**：`error` 事件字段形状追认为 **`code` + `message` + `retryAfterSeconds` 恰 3 项**；`retryAfterSeconds` 的 M3 说明由"**可选**"改为 🔴 **字段恒存在、值可为 `null`**（一期恒 `null`，SSE 内 `10005` 不可达见 §7.12）。理由：ⓐ §5.4.1 第 1/2 条"字段只增不改不删"是硬约束，已下发字段永久保留；ⓑ 前端已上线并按三项容忍 `null`，回退为两项属破坏性变更；ⓒ 恒定形状使 @测试 可做**精确键集合断言**（"恰 3 个键"），比"可选字段"具更强可验收性。追认 @后端 已加的 IT 断言（`error` 恰 3 字段且不含 `violations`）<br>⑤ **G-4（📋 维持二期技术债，不追加平台层通用兜底）§3 + `architecture.md` §8.2.1 纪律 8 / AR-018 ⑤**：🔴 关键事实澄清 —— **ADMIN 语义端点的程序化兜底一期已全覆盖**（§7.2.1 缓存失效的 `requirePlatformAdmin()`、§7.3.1 `localTool` 分支的 `role=ROLE_admin` 判定），故 `eyes-auth.enabled=false` 下**不存在** ADMIN 语义端点静默放行路径；AR-018 ⑤ 的实际缺口**仅剩 `USER` 级**。🔴 明确否决对 `USER` 级做等价兜底：`@Permission(USER)` 的语义是"验证 eyesUser token 真伪"，只能由 Thrift 完成，切面未装配时**根本没有可信身份来源**，程序化兜底只能"一律拒绝"，会使 test profile 全部接口不可测（244 单测 / 238 集成无法运行）—— 用不可测换一个已被启动断言覆盖的场景，不成立。🔴 关于"`prod` 启动断言是否构成足够防线"的明确结论：**对 `prod` 充分**（`checkProductionBlockers()` 断言 `eyes-auth.enabled=true`，为 `false` 即启动失败 → 生产不存在切面缺失的**运行态**，风险从"运行期静默越权"降级为"部署期启动失败"，后者不可能被忽视）；**对 `dev`/`staging` 不充分** → 本版补**文档级运维纪律**（`architecture.md` §8.2.1 纪律 8）：🔴 任何承载真实租户数据的环境**必须以 `prod` profile 启动**，非 `prod` profile 仅允许用于无真实数据的本地/CI 环境。🔴 本项**不产生代码返工**<br>⑥ **G-5（✅ 明确唯一对外失败码 = 按切面装配状态二分）§3 新增二分口径表 + §7.2.1 权限行/错误码行 + §8.2 核对项**：`@Permission(ADMIN)` 端点（一期**唯一**：`POST /api/v1/platform/cache/evict`）的平台角色不足 → 🔴 `eyes-auth.enabled=true` 的**一切真实环境**（`prod`/`dev`）恒 **`20000`**（`PermissionAspect` `@Order(10)` 在 Controller 之前拦截，请求进不到方法体）；🔴 `eyes-auth.enabled=false` 的 **test profile** 得 **`10003`**（Controller 程序化兜底）。🔴 二分是**执行顺序的物理必然**，不是口径不统一：要让生产也返 `10003` 只能把端点降级为 `@Permission(USER)` 再在方法体判 role（等于放弃切面前置防线，方向错误）；要让 test 也返 `20000` 则要求兜底伪造 SSO 语义码（无 Thrift 校验，属伪报）。🔴 **共同不变量（@测试 的真正断言对象）**：平台角色不足时**绝不 `code=0`**、不返回任何数据；两码均已在 §2.2 登记；前端动作差异可接受（该端点一期无 UI，仅运维脚本调用，§8.3 H4）。🔴 @测试 按 profile 二分断言，不得跨环境套用。**顺带订正同族歧义**：§7.3.1 / §7.1.1 总表第 2 行原写"平台作用域对象改用 `@Permission(ADMIN)`"**不可实现**（`@Permission` 是方法级注解，无法按请求体 `objectType` 分支；该端点同时服务租户与平台两类对象，注解只能取较宽的 `USER`）→ 订正为"方法体内追加平台管理员判定 → `10003`"，🔴 该端点两种 profile 下**恒 `10003`、不二分**，@后端 实现正确无需返工<br>⑦ **§8.3 定点订正 7 条判据（🔴 只改文字，不增删不重排，总数仍 58 项、编号不变，@测试 已完成的 58 项映射与 65 条用例编号全部保持有效）**：**A4**（G-3 键集合恰 3 项）、**C3**（G-1 判据只看 `granted`/`status`）、**C5**（G-2 新增 ⓑ 解绑残余不判缺陷）、**C7**（G-2 绑定解析只在清单构造期）、**D3**（G-4 二期技术债范围 + G-5 二分口径）、**F3**（G-0 12 项 + 集合恒等断言）、**H6**（追加 AR-019 为已授权的不可消除残余）<br>⑧ **自查结论**：交叉引用全部真实存在（§3 / §5.2 / §7.6.3 / §7.7.2 / §7.14 / §8.2 / §8.3 / architecture §8.2.1 / §11.1.1 / §13.5.4~6 / §13.5.10 / AR-017~019）；本版出现的 `10003` / `20000` / `30050` 均已在 §2.2 登记；🔴 `sys_config` 键总数保持 **25**（§7.1.2 未增未减） |
| **V1.2.0** | **2026-08-14** | **`sse` 传输支持 MCP 旧版「HTTP+SSE」(2024-11-05) 异步推送形态（G6 自留裁决点被真实上游触发；🔴 零新接口、零新错误码、零 DDL 变更、零新 audit action（仍 12）、`data.result` 字面量零扩充（仍 9）；新增 2 个 `sys_config` 键，27 → **29**）：**<br>① **§7.6.1 新增 G6′ 裁决（取代 G6 ②）**：✅ **支持**该形态；落点 = `SseTransport` **单次 exchange 内的形态自适应**（判据 = 首个 POST 的响应体是否含可解析 JSON-RPC 报文）。🔴 **否决新增 `sse_legacy` 传输枚举**（理由：ⓐ 该形态是 2024-11-05 规范的**正统形态**，为它另起"legacy"之名属命名颠倒；ⓑ DBA 无法从 URL 判断上游形态，让运维选枚举 = 把探测责任推给运维，必然"选错 → `30052` → 反复试"；ⓒ 需连带改 `McpServer` 常量 / §7.3.1 枚举校验 / DDL 文档，变更面更大而收益为负）。🔴 **否决"不支持、要求用户换服务"**：实测该上游 `POST /sse/{id}` → **`405`**，**不存在** Streamable HTTP 端点变体，替代路径不存在<br>② **🔴 前提纠正（G6 的否决依据本身是错的）**：G6 称"消费异步形态必须有第二个读取线程 → 违反『不新增线程池』"。事实是 JDK17 的 `HttpClient.send(...)` 本身就是 `sendAsync(...)` + 阻塞等待，`BodySubscriber` 一直跑在 `HttpClient` **自带的内部 executor** 上 —— 今天的同步 MCP 调用**已经**在用它。故 `sendAsync` + `BodyHandlers.fromLineSubscriber` **未新增任何线程池**。边界定义已固化在 `architecture.md` **ADR-008 第 8 条 V1.4.0 补注**（✅ 允许复用该内部 executor + 带超时的被动等待；❌ 禁止 `new Thread` / `Executors.new*` / `@Async` / `supplyAsync` / 无参 `get()`·`join()`；🔴 订阅者内禁止阻塞；🔴 仅限 `mcp` 包）<br>③ **🔴 `initialize` 前置兼作形态探测（G6′ ②③）**：不采用"先发目标方法、失败再补握手"—— 后者在上游强制握手时要求**重发 `tools/call`**，可能造成**重复副作用**，与 `30056`「非幂等结果未知一律不自动重试」直接冲突。前置 `initialize` 从结构上消灭该路径；代价是同步形态 `sse` 每次多一次 POST（`sse` 为兼容分支，可接受）<br>④ **§7.6.2 新增 G9′（G9 的限定修订）**：`streamable_http` **逐字不变**（仍不发 `initialize`、不维护 `Mcp-Session-Id`）；`sse` 允许 **exchange 内一次性**握手与会话，🔴 生命周期严格 ⊂ 单次 exchange。🔴 修订后不变量仍成立：**系统不持有任何跨请求的 MCP 会话状态**<br>⑤ **🔴「不长驻连接」正式定义 + deadline 预算制（G6′ ⑤⑥）**：GET 流存活期 ⊆ 单次 exchange 且 ≤ 单次调用总预算，`finally` 强制关流，🔴 禁止把 `sessionId`/流/订阅者写入任何字段·静态变量·Redis·DB·缓存，🔴 禁止跨调用复用 session。同时**订正既有实现缺口**：原"每个子请求各取一份完整 timeout"使 `sse` 最坏达 **2×~4× 预算** → 改为入口算一次 `deadline`、各步取 `remaining()`；§7.4.3 补注**翻页不得重置预算**（否则 `MAX_PAGES=50` 会把 15s 放大到 12.5 分钟）<br>⑥ **🔴 SSRF 纪律不削弱、点位零变化（G6′ ⑦）**：三处 `SsrfGuard` 校验（§7.3.1 / §7.4.2+§7.4.3 / §7.6.3 第 4 步）**完全不变**且仍在发起任何连接之前；`followRedirects=NEVER` 不变；会话端点 `sameOrigin` 保留（跨源 → `protocol_incompatible`，🔴 **不改判 `30050`**，避免安全审计混入"上游实现不规范"噪声）。🔴 **新增两条纪律**：`event: endpoint` **只认第一次出现的值**（防流内二次投毒）；本传输**只会**请求两个 URL（已校验 `endpoint` + 与之同源的 session endpoint），流内其它 URL 一律不请求<br>⑦ **§7.4.2 失败分类：字面量集合零扩充（仍恰 9 个）**。新失败模式全部落到既有字面量：等 endpoint 事件/等结果超预算 → `timeout`（`30051`）；会话端点跨源 / 开关关闭时 POST 空体 / 异步形态 `initialize` 返 `error` / 流在给结果前被关闭（🔴 **立即失败，不等超时**）/ 流超字节上限 → `protocol_incompatible`（`30052`）。完整对照表见 `architecture.md` ADR-016<br>⑧ **§7.1.2 新增 2 键（27 → 29）**：`mcp.sse_legacy_enabled`（BOOLEAN，默认 `true`，🔴 **读取 fail-closed** —— 它是"要不要发起并持有一条流"的能力开关，关闭即**完整回到 G6 行为**，构成运维不改代码的止血手段）/ `mcp.sse_stream_max_bytes`（NUMBER，默认 `4194304`，🔴 不变量 **≥ `tool.result_max_bytes`**，违反即**拒绝启动**）；两键均入 `StartupChecker.REQUIRED_CONFIG`<br>⑨ **§7.13 新增异步形态 Mock 端点**（`GET /mock-mcp/sse-legacy` 保持流 + `POST …/messages` 恒 `202` 空体）与 6 个场景（`success` / `never_push` / `close_early` / `oversize_stream` / `init_error` / `noise_then_result`）；🔴 **同步形态端点行为不变**，现有 `TRANSPORT_SSE` 用例与 AC-MCP-003 必须原样通过（`initialize` 探测收到 `-32601` 🔴 必须被忽略）<br>⑩ **§8.3 新增 I 组 9 项核对项**（🔴 A~H 组 58 项编号与总数**不变**，@测试 既有映射全部继续有效），含 🔴 泄漏专项（连续 200 次后线程数不单调增长）与 🔴 预算专项（整次 exchange ≤ 单次预算）<br>⑪ **自查结论**：本版未引入任何新错误码（`30051`/`30052`/`30053`/`30060` 均已在 §2.2 登记）；`mcp_servers.transport` 合法值仍恰 2 个；`last_check_result` `VARCHAR(32)` 无需改表；技术侧落点见 `architecture.md` **V1.4.0 / ADR-016 / AR-020** |
| **V1.2.1** | **2026-08-14** | **G6′ 内部冲突消除 + 超时预算键订正（@后端 交付后 5 点确认的契约侧落点；🔴 零新接口、零新错误码、零新 `sys_config` 键（仍 29）、零 DDL、零新 audit action（仍 12）、`data.result` 仍 9、`transport` 仍 2 值、🔴 零业务代码返工）：**<br>① **🔴 §7.6.1 G6′ ② 步骤 1 重写（最重要，消除契约内部字面冲突）**：原文「上游不支持 GET → 退化为直接 POST」与 `architecture.md` ADR-016 失败分类表「GET `3xx` → `protocol_incompatible`」「GET `401` → `auth_failed`」对**同一输入**给出两种结果。✅ 采纳 @后端 的"分类表优先"收敛：GET 非 2xx **二分** —— 【安全/鉴权语义类】`3xx` / `401` / `403` / `407` 🔴 **直接失败、绝不退化**（退化会让 `followRedirects=NEVER` 被旁路绕过，并把鉴权失败掩盖成协议不兼容、丢掉 `auth_failed` 诊断与审计）；【能力类】`404` / `405` / `5xx` / 其它 → 退化为直接 POST（既有兼容行为，🔴 不视为故障），最终分类由该次 POST 决定。🔴 GET 与 POST 必须共用**同一个**状态码分类判据<br>② **🔴 连接测试预算键订正**：§7.4.2 与 G6′ ⑤ 中「连接测试 = `mcp.connect_timeout_seconds`」**作废** → 统一为 **`mcp.discover_timeout_seconds`**。理由：该接口在实现上**就是执行一次 `tools/list`**（`McpConnectionTester → listTools`），不存在"独立握手请求"这次往返；按原文会出现"同一次 `tools/list` 在 `/test` 与 `/discover` 预算不同"的矛盾；且与 §7.6.1 G7「建连超时只能设在 `HttpClient` Bean 上」并不冲突（G7 描述的是**建连阶段**，不构成 exchange 预算）<br>③ **§7.6.1 G7 表格 + 说明块补注**：`mcp.connect_timeout_seconds` 一期**无代码消费点**，语义收窄为 ⓐ 运维不等式 `app.ai.connect-timeout-seconds ≤ 它` 的参照值、ⓑ 📋 二期独立 `initialize` 握手阶段的预算键；🔴 **仍在** `REQUIRED_CONFIG`，不得因暂无消费点而删键或移出必需集（键总数仍 **29**）<br>④ **§7.4.2 分类表两行补注**：`auth_failed` 增列 `407` 且明确"GET 建流命中即判本项、不得退化"；`timeout` 明确"GET 流被 `HttpRequest.timeout(remaining)` 打断（`HttpTimeoutException`）→ 判 `timeout`/`30051`，🔴 不得判 `protocol_incompatible`"（否则"上游一直不回"被误诊为"协议不兼容"，违反 G2 判别口径）<br>⑤ **§8.3 I 组**：新增 **I10**（GET 非 2xx 二分，含"断言上游未收到 POST"）、**I3 判据细化**（超时打断 → `timeout`），并新增 **I 组验证形态与门禁说明**：组件级测试（零 Spring / 零 DB / 真实 HTTP 栈）可**先行**给等价断言（两新键落库前 `StartupChecker` 按契约拒绝启动，Spring 型 IT 无法先跑），🔴 但 I 组签署的**前置条件**是一次 **`mvn -o verify` 全绿**记录；🔴 A~H 组 58 项编号与判据**零变化**<br>⑥ **@后端 追认项（无返工）**：`McpRpcRequest` 保持**独立 record 文件**；三处实现细节（流上 `HttpTimeoutException` → `TIMEOUT`；GET 状态码在 `BodyHandler.apply(ResponseInfo)` 阶段判定；**非 async 的** `whenComplete` 转交连接层异常）已回写 `architecture.md` ADR-016 ⑥ⓐⓑⓒ 与 ADR-008 第 8 条 V1.4.1 白/黑名单细化 |
| **V1.2.2** | **2026-08-17** | **MCP 联网搜索第 1 轮验收 2 个 P1 的契约侧落点（`docs/test-report.md` V4.0 BUG-MCP-001 / BUG-MCP-002；技术决策 = `architecture.md` **ADR-017 / ADR-018**；🔴 零新接口、零新错误码、零 DDL、零新 audit action（仍 12）、零新 SSE 事件名（仍 5）、`data.result` 仍 9、`transport` 仍 2 值；新增 3 个 `sys_config` 键（29 → **32**）+ 1 个 SSE 字段）：**<br>① **🔴 §5.2 新增 `tool.confirmExpiresInSeconds`（number \| null）**：仅 `awaiting_confirmation` 帧非空，承载**本次确认的实际剩余等待秒数**。🔴 必须新增的原因：确认等待自本版起被**单次生成总预算**收紧为 `min(tool.confirm_wait_seconds, 剩余 − 宽限)`，前端若继续按 `sys_config` 显示倒计时**会骗人**（显示 120s 而 30s 后即 `timed_out`）。🔴 前端规则 = 优先用本字段、`null`/缺失回退 `sys_config`（符合 §5.4.1 第 1/5 条，**旧前端零破坏**）；🔴 `剩余 − 宽限 ≤ 0` 时**根本不下发**该帧（不发一张必然超时的确认卡）<br>② **🔴 §5.2 新增 `done.finishReason="timeout"` 触发口径框（零新错误码）**：该取值的触发集合明确为**两类同码同 finishReason** —— ⓐ 上游无响应（首字/单轮整体超时，M1 既有 EX-014）；ⓑ **V1.2.2 新增：单次生成总预算耗尽**（`chat.generation_deadline_seconds`，🔴 **含等待用户确认的时间**）。两类均 `error(50002)` + `done(timeout, failed)` 且**已生成内容必须落库保留**。🔴 复用 `50002` 而不登记新码的理由：对用户是同一件事（本次生成未能在时限内完成、可重试），前端动作完全一致，新增码只增加登记面与前端改动而不改变行为（代价 = 告警计数混入，已由**强制 `[DEADLINE]` 日志前缀**缓解；📋 二期如需独立统计再登记 `30058`）。🔴 **反向判据（本轮 FAIL 项锚点）**：出现"连接静默关闭且**无** `done`、而用户并未关页面/切网"即为缺陷；`done` 缺失的**唯一**合法情形是真实物理断连<br>③ **🔴 §7.1.2 新增 3 键（29 → 32）+ 2 条拒绝启动不变量**：`chat.generation_deadline_seconds`（`300`，🔴 **生成最长驻留的唯一权威**，取代 `spring.mvc.async.request-timeout` 原先兼任的职责，后者降级为**纯传输层硬兜底**并提到 `600000`）/ `chat.deadline_grace_seconds`（`15`，一值两用：`SseEmitter timeout = deadline + grace` 让终帧写得出去 + 业务侧 `剩余 ≤ grace` 时不再开新工作）/ `chat.tool_usage_guideline`（平台纪律段文案，含默认文案与措辞纪律）。🔴 不变量 `deadline + grace ≤ request-timeout/1000` 与 `grace ≥ 5` **违反即拒绝启动** —— 判据同采样率（区间外**无合法语义**）：业务预算超过传输上限时"传输层必然先超时 → `done` 物理上写不出去"，§5.1 第 3 条在该配置下**必然被违反**，这正是 BUG-MCP-002 的成因<br>④ **🔴 新增 §7.5.2 ⑥「平台级工具调用纪律段」裁决框**：🔴 仅当**本次生成确实下发 tools** 时，作为**独立的第二条 system 消息**注入（租户段之后、摘要/窗口之前）⚠️ **该形态已于 V1.2.4/ADR-019 作废 → 合并进唯一 system 消息的末块**；🔴 **不计入** `chat.system_prompt_max_chars`（① 的判定对象恒为**租户配置**；若计入会把**既有满配租户**直接打成 `30060`，属不可接受的连带破坏）；🔴 仅支持占位符 `{{currentTime}}`（直接消灭实测的"按训练期知识把『最近』算成 2024 年时间戳"）；🔴 措辞必须**与具体工具无关**（否则等于把已否决的"替上游猜语义"从 schema 挪进 prompt）；🔴 它是**引导不是保证**，不得据此削弱 Schema 校验/风险确认/审计<br>⑤ **🔴 新增 §7.6.4「失败回灌的诊断来源二分」裁决框（订正实现缺口，P1-1 主修）**：事实认定 —— 编排层对**所有**失败码一律回灌固定措辞，而执行器在 `isError=true` 分支**已经**取回了上游错误正文（已过 `truncateForModel`）→ 🔴 这段可用诊断**被丢弃**，模型只知"失败"不知"哪个参数非法"，只能换写法重试（实测三轮、每轮都要用户再确认）；🔴 而**成功**路径回灌的正是同一个 content → 属**同一条链上的实现不一致**。裁决按「**这句诊断是谁说的**」二分：✅ 可回灌 = `30057`（上游 `isError` 正文）/ `30053`（本地校验字段级诊断、MCP `-32602` 的上游 `error.message`）；❌ 不可回灌 = `30052`/`30051`/`30056`/`30050`/`50003`（平台与传输侧诊断，可能含 endpoint/内网地址/堆栈），🔴 其中 `30050` 三种来源措辞必须**完全一致**（差异化 = 给出探测平台配置的信道）。🔴 处理链不变（同成功路径的 `truncateForModel`）→ **不引入新泄露面**；🔴 ADR-011 两类截断分离、`30056` 禁止自动重试、`tool.max_rounds` 封顶**全部不变**<br>⑥ **🔴 §7.8.1 ④ 确认等待上限口径订正**：原口径把 `tool.confirm_wait_seconds` 当**绝对**上限，导致"确认等待可突破生成总预算"→ 连接在等待期间被掐断（BUG-MCP-002 触发路径）；订正为 `min(confirm_wait_seconds, 剩余 − 宽限)` 且**必须下发实际上限**；🔴 收敛语义零变化（仍 `timed_out` + `30050` + `tool.confirm_timeout`）<br>⑦ **§8.3 新增 J 组 J1~J10**（🔴 A~I 组编号与判据**零变化**，@测试 既有映射全部继续有效）：含 🔴 **J2 反向断言**（常规路径不得出现 `AsyncRequestTimeoutException` / 无 `done`）、🔴 **J8 上游 schema 透传守护**（下发 schema 与库内**逐字相等** + 连续 `discover` **不产生** `mcp.tool_grant_revoked`）、🔴 **J9 纪律段不计入预算**，以及 ⚠️ **J10 模型行为观察项**（🔴 明确**不作为签署阻塞项**：判据订正为"失败后模型是否收到可自纠的诊断"，而非"模型必然一次成功"）<br>⑧ **自查结论**：本版出现的 `50002` / `50003` / `30050`~`30057` / `30060` 均已在 §2.2 登记；SSE 事件名仍恰 5 个（`meta`/`delta`/`tool`/`error`/`done`），新增能力一律以"既有事件加字段"实现（§5.4.1 第 7 条）；`error` 事件仍恰 3 键（G-3 不受影响） |
| **V1.2.3** | **2026-08-17** | **@后端 ADR-017 / ADR-018 交付后 3 点实现追认的契约侧落点（技术裁决 = `architecture.md` **V1.4.3**；🔴 零新接口、零新错误码、零新 `sys_config` 键（仍 32）、零 DDL、零新 audit action（仍 12）、零新 SSE 事件名/字段、🔴 零业务代码返工）：**<br>① **§7.6.4 二分框 `30050` 行补注 + §8.3 J7 由“三源”订正为“四源”**：不在清单内 / preflight 服务停用 / 执行前授权点查 / 执行期竞态（含 `GRANT_REVOKED`·`SSRF_REJECTED`）四个来源必须回灌**同一常量** `DENIED_FEEDBACK`，🔴 逐字不一致即判**安全缺陷**；@后端 把 preflight 分支的“工具调用被拒绝”统一过来属 🔴 **订正实现**（该纪律自 V1.2.2 即为明文契约，非新增语义）；🔴 `SSRF_REJECTED` 复用“未授权或已停用”这句不精确措辞**有意为之**，严禁以“措辞不准”为由改精确（精确即泄露）；内部区分只体现在 `tool_calls.error_code` + audit + 服务端日志<br>② **§8.3 J4 覆盖级别订正**：`剩余 − 宽限 ≤ 0 不下发确认卡` 的验收级别由 IT 降为 **单测**（`ToolFeedbackDiagnosticTest` 注入 `usableSeconds=0`）—— 该分支在「工具执行准入」就位后已是**防御性不变量**（准入要求 `usable ≥ 工具超时 ≥ 1`，本分支判据 `usable ≤ 0`，两点间仅微秒级进程内工作），IT 稳定构造只能向生产代码植入可控时钟/延时钩子 → 🔴 **不得因“缺 IT 证据”判缺陷或阻塞签署**；J4 前半句仍为 IT 级<br>③ **契约无变化，仅订正一处技术前提（详见 `architecture.md` ADR-017 ③ⓒ V1.4.3 补注）**：工具准入用的 `ToolDefinition.timeoutSeconds()` 与 `tools/call` 的 exchange 预算（`min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)`）是**同源同公式**的生成期快照，🔴 “准入偏保守”**不成立**；唯一残余偏差 = 生成中途改配置（快照 vs 实时读库，与 AR-019 同一快照原则），由 `grace ≥ 5` 启动不变量 + AR-022 的 WARN 信号兜住 → 🔴 不精确化、不新增判据 |
| **V1.2.4** | **2026-08-17** | **🔴 ADR-018 ② 的契约性订正：上游消息形态适配（`docs/test-report.md` V4.1 **BUG-MCP-004**；技术裁决 = `architecture.md` **V1.4.4 / ADR-019**；🔴 零新接口、零新错误码、零新 `sys_config` 键（仍 32）、🔴 零键值变更、零 DDL、零新 audit action（仍 12）、零新 SSE 事件名/字段）：**<br>① **🔴 §7.1.2 + §7.5.2 ⑥ ②：注入形态订正，原「独立的第二条 `system` 消息」正式作废**：真实上游（混元 OpenAI 兼容接口）硬约束 `status=400`「`messages` 中 system 角色必须位于列表的最开始」→ 含工具的生成在**进入工具调用之前**即 `error(50002)` + `done(failed)`，影响**所有**工具（实测含 `calculator`，非 WebSearchMCP 单点问题）。✅ 现口径：纪律段**合并进唯一的 `system` 消息、恒为末块**（顺序 = 租户段 → 摘要块 → 纪律段，分隔沿用既有 `SECTION_SEPARATOR`，🔴 零新增字面量、🔴 禁止另造可见分隔文案）<br>② **🔴 §7.5.2 ⑥ ③：原「不计入 `chat.system_prompt_max_chars`」原样保留（🔴 未因合并而放宽）**：🔴 **物理合并 ≠ 预算合并** —— 预算判定只吃**租户段**且发生在纪律段拼接**之前**，故合并**不需要**改动任何预算逻辑（题述「(b) 单列预算」想达到的效果与此完全相同，🔴 无需为它新造预算机制/新键）。三条明文口径：ⓐ `30060` 校验**不含**纪律段与其分隔符；ⓑ 🔴 最终 system **物理长度可以超过** `system_prompt_max_chars`（**有意为之**，不得判缺陷、不得因此截断纪律段）；ⓒ 🔴 反向不放宽 —— **租户段自身**超限仍 `30060` 且仍禁截断<br>③ **🔴 §7.5.2 注入位置块重写：新增「单一前导 `system` 不变量」**（`role=system` 至多 1 条且必须在 `index 0`，取 OpenAI 兼容生态的公共交集，🔴 不做单一 provider 适配）+ 三块顺序与**三段预算互不合并**（租户段 → `system_prompt_max_chars`；摘要块 → `context_summary_max_chars`；纪律段 → 🔴 不设预算）<br>④ **🔴 §7.5.4 新增「摘要的消息载体」框：一并订正同源既有隐患**：历史摘要自 M1 起即以**另一条 `system` 消息**注入 → 🔴 与本次 400 **同根**，且触发条件与工具无关（「会话有更早内容 + 摘要已缓存」即命中），摘要缓存 TTL 期间该会话**每轮必失败**（不可自愈，正是 §7.5.4 开头力图消灭的失败模式）→ 改为**同一条 system 内的块**；🔴 选材/触发/截断/降级规则**逐字不变**；🔴 明确否决把摘要改成 `role=user`（语义错位 + 连续 user 消息的新兼容风险 + 挤占 `context_max_chars`）<br>⑤ **🔴 §7.5.2 ① 裁决框措辞订正**：判定对象由「system 消息**整体**」精确化为 🔴 **恒为租户段**（V1.2.2 语境下两者等价，合并后必须精确表述）<br>⑥ **🔴 §8.3 J9 判据订正 + 新增 J11/J12**（🔴 A~I 组与 J1~J8/J10 编号与判据零变化）：J9 断言形态改为「**恰 1 条 system 在 `index 0`** + `endsWith(纪律段)` + `startsWith(租户段)`」并补「物理长度可超限不得判缺陷」；**J11** = 单一前导 system 不变量的**全组合矩阵**（含「长会话 + 已缓存摘要 + 有工具」三块同时存在）+ 适配层 fail-fast 反向守护（🔴 `IllegalStateException` → `error(50003)` + `done(failed)`，`done 必发`不变，异常不含正文）；**J12** = 🔴 **签署前置**的 BUG-MCP-004 回归守护（桩上游必须**复刻**上游硬约束 → >1 条 system 即 400；`calculator` 恢复 `succeeded`/`completed`；断言桩实收请求体 system 恰 1 条且以纪律段结尾 —— 🔴 反向守护「靠删纪律段变绿」）<br>⑦ **🔴 键值零变更声明**：`chat.tool_usage_guideline` 的**文案一字不改、DBA 无动作**；🔴 严禁以「上游 400」为由删除该键/清空该值/删减纪律条目（那是回退 BUG-MCP-001 的修复，且 `StartupChecker` 会因空白拒绝启动）<br>⑧ **自查结论**：本版未新增任何错误码（`50003` 早已登记于 §2.2）、未改动任何 SSE 事件名与字段、未改动任何接口路径与权限注解；`done 必发`、反硬编码、租户隔离、SSRF、审计、deadline 预算制六条红线**全部未放宽** |
| **V1.2.5** | **2026-08-18** | **🔴 用户维度对话限流与每日限额契约落地（PRD **V1.4** `REQ-LMT-003` / `REQ-QUOTA-001~005`；技术裁决 = `architecture.md` **V1.4.5 / ADR-020**；🔴 零 SSE 事件名与字段变更、零新 audit action（仍 12）、零新埋点事件名、`data.result` 仍 9、`transport` 仍 2 值）：**<br>① **🔴 §2.1 / §2.2 新增子段 `30070~30079` 与错误码 `30070 DAILY_QUOTA_EXHAUSTED`**（业务段，🔴 未占用 `20000~20999`）：`data` 必须是 §7.15.2 的**额度快照（恰 9 键）**，🔴 **禁止**携带 `retryAfterSeconds`（携带即被前端 `rateLimitStore` 误表现为秒级倒计时，而它的恢复条件是"明日租户零点"）；🔴 明确**否决**复用 `10005`（PRD §8.11.3 已产品裁决）<br>② **🔴 新增 §7.15「用户额度与限流」**（6 小节）：**§7.15.1** 新接口 `GET /api/v1/me/quota`（`@Permission(USER)`、🔴 **零参数**、匿名走标准 `20001/20002` 而 🔴 **不返回**假的 unlimited）；**§7.15.2** 额度快照 **恰 9 键**（对齐 PRD F-QUOTA-001~009）+ 🔴 `used` 严格＝已结算、`remaining` 采**含在途预占的保守口径**（`used + remaining ≤ limit` 是**有意为之**，附反向断言的取样纪律）+ 🔴 **不返回 QPM 阈值**（下发限流阈值＝暴露策略并诱导前端双实现）；**§7.15.3** 🔴 **准入五步顺序**（解析策略 → 日额度**只读**预检 → QPM 计数 → 日额度**预占** → 建消息/建流），据此结构性满足"日额度用尽不增 QPM 计数、QPM 超限不占日额度"，并明文**接受**唯一偏差（步骤 4 并发失败时 QPM 已计数，与 AC-QUOTA-004"QPM 不回退"一致，🔴 严禁实现 DECR 回退）；**§7.15.4** 计数口径表 + 🔴 **"资源已消耗证据"判定集合（ⓐ~ⓓ，@后端 不得增删）** + 🔴 **exactly-once 结算** + 🔴 标题生成/摘要刷新等平台内部派生调用**不计**；**§7.15.5** 配置分层（NULL＝继承、非法覆盖 🔴 **不得静默继承**、`effective_at` 读取时过滤 🔴 **无定时任务**、平台默认缺失/非法 🔴 **拒绝启动 + 运行期 50003**、🔴 一期无用户级白名单且**不预留空字段**）+ 失败语义（租户 `timezone` 非法 → 🔴 `50003` 且**禁止回落 UTC**；Redis 不可用 → 🔴 **降级 DB 直判而非放行**，与 §7.12 的 QPM 有意不同）；**§7.15.6** 额度日窗口（租户 IANA 当地零点、🔴 DST 允许 23h/25h、🔴 TTL 禁写死 86400、🔴 "当地 00:00 不存在"必须首尾相接）+ 🔴 **追认 QPM 分钟窗继续用 UTC 纪元**（时区只影响日历日）<br>③ **🔴 §7.12 重写**：QPM 阈值来源改为**由策略解析器传入**（🔴 QPM 与日限额必须来自**同一次**解析，否则会出现"QPM 取平台默认、日额度取租户覆盖"的错配）；🔴 **小时窗业务规则废除**并给出反向断言（QPM 放宽后连续 130 次无拒绝）；补"与日限额的优先级"行<br>④ **🔴 §7.1.2 新增 3 键（32 → 35）+ 1 处键值变更 + 1 键废弃删行**：新增 `ratelimit.qpm_enabled`（`true`）/ `ratelimit.daily_quota_enabled`（`true`）/ `ratelimit.daily_quota_limit`（`50`），三键全部入 `REQUIRED_CONFIG`，🔴 代码中禁止 `3`/`50`/`true` 字面量；`ratelimit.message_per_minute` 值 **30 → 3**（键不变）；🔴 `ratelimit.message_per_hour` **废弃并删行**（四步处置 + 🔴 明确否决"保留键但不读取"这一"配置骗人"模式，残留 Redis 小时窗键由自身 TTL ≤1h 自然回收）。🔴 新增 **2 条拒绝启动不变量**（`message_per_minute ≥ 1`、`daily_quota_limit ≥ 1` —— `≤0` 无合法语义，"关闭"的唯一合法表达是 `*_enabled=false`）与 **1 条 WARN 不变量**（`daily_quota_limit ≥ message_per_minute` —— 试用租户 daily=2 是合法调参，故只 WARN）；⚠️ 明确 `ratelimit.*` 属 **M1 键集** → 移除 `message_per_hour` 🔴 **不影响** `StartupCheckerRequiredConfigTest` 的 25 键子集断言<br>⑤ **🔴 §7.14 补注：零新增 audit action（仍恰 12 项）**：额度命中/结算是**高频运行事件**而非安全管理动作（PRD §8.11.8.1），逐次审计会淹没 `idx_action_time` 上真正的越权事件；可追溯性由 `user_daily_quota_usages` 账本 + `[QUOTA]` 运行日志 + 策略版本行承担；🔴 反向纪律：二期把额度配置做成管理端接口时**配置变更必须审计**，但仍不得为"用尽/命中"新增 action<br>⑥ **§4.3.1 补注**：`/api/v1/me` 🔴 **不新增任何额度字段**（身份建立与高频额度校准解耦，避免"为刷额度反复触发建户与 Thrift 鉴权"及两处口径）；§7.1.1 接口总表新增第 13 行（12 → **13**，并注明它是**终端用户接口**、不属"仅供实测"之列）<br>⑦ **🔴 额度快照不进 SSE `done` 帧（ADR-020 备选 E 否决）**：前端在 `done` 之后**重新拉取**权威快照（与"页面恢复前台/跨标签页/到达 `resetsAt`"复用同一条加载路径），🔴 因此本版 **SSE 事件名与字段零变更**、`done` 帧键集合与 M3 逐字一致 —— 既不给 6 个 `done` 分支各留一个"漏拼快照"的错误点，也不引入"SSE 值与查询值不一致"的排障噪声<br>⑧ **§8.3 新增 K 组 K1~K16**（🔴 A~J 组编号与判据**零变化**，@测试 既有映射全部继续有效）：含 🔴 **K4 优先级双向断言**（直接断言 Redis 分钟窗计数未增加）、🔴 **K9 并发不超发**（且明确**不得**在 Redis 降级态跑本用例判缺陷）、🔴 **K10 DST 双用例**（23h/25h + "当地 00:00 不存在"）、🔴 **K13 快照恰 9 键**、🔴 **K16 Redis 丢数据不白得额度**（删镜像键后从 DB 重建）<br>⑨ **自查结论**：新增码恰 1 个且落在业务段（🔴 未触碰耶瞳保留段）；`data` 恒 camelCase、时间恒 ISO-8601 UTC；`done 必发`、反硬编码、租户隔离、隐私（🔴 全链路不含消息正文）、deadline 预算制、单一前导 `system` 不变量六条红线**全部未放宽** |
| **V1.2.6** | **2026-08-18** | **§7.15.4 exactly-once 表述消歧（🔴 零接口变更、零新错误码、零字段变更、零 `sys_config` 变更、🔴 零业务代码返工；技术裁决 = `architecture.md` **V1.4.6 / §9.6.2**）：**<br>🔴 原 §7.15.4 写「跨进程由预占集合的原子移除保证（**移除成功才落账**）」，与 architecture §9.6.2 同节明文的「顺序固定，**DB 先写**」**互斥** —— 现统一为 **DB 先写 → 再 `ZREM` + `INCR` 镜像**，并固化三个角色：**权威闸门** = 本次生成的**进程内一次性标记**（`AtomicBoolean` CAS，唯一的"要不要落账"判定处）；🔴 `ZREM` **不是**闸门而是**释放动作 + 诊断信号**（返回 0 → 只记 WARN，不重复计数、不回退已落账 DB）；🔴 DB 唯一键**也不是**闸门（结算 SQL 是无条件 `settled_count + 1`，**非幂等**，去不了重）。<br>废止"ZREM 才落账"的理由：它存在**不可补偿的崩溃窗口**（ZREM 成功 → 崩溃 → DB 未写 = 用户白得一次生成且 `remaining` 立即恢复），而 DB 先写的对称窗口只是"预占残留 → 多占一格 `remaining` 直到 hold 过期"（🔴 保守方向且由 `ZREMRANGEBYSCORE` 自愈）；且"DB 失败 → 回补 `ZADD`"无法还原原 score。成立依据 = 载荷不变量「一个 `reservationId` 恒由唯一一个 JVM 内的唯一一条生成线程持有并结算」（id 只存活堆内、不落库/不入 Redis 值/不下发前端/不跨节点，幂等回放完全不动账）→ 🔴 "跨进程重复结算"当前**不可达**，与实例数无关。📋 二期若该不变量被打破，修法是把幂等性下沉到 DB（`reservation_id UNIQUE` 事件表同事务先插后累加），🔴 而非改回 ZREM 闸门。<br>🔴 **@前端 / @测试 零影响**：对外可观测行为（`used`/`remaining` 口径、`30070` 载荷形状、K1~K16 判据）**逐条不变**，无需改动任何用例 |

| **V1.2.7** | **2026-08-18** | **🔴 全局传输层契约缺陷裁决：建流前异常必须绕过内容协商（`docs/test-report.md` V5.0 **BUG-QUOTA-001**；技术裁决 = `architecture.md` **V1.4.8 / ADR-021 / §9.3.1**；🔴 零新接口、零新错误码、零新 `sys_config` 键（仍 35）、零 DDL、零新 audit action（仍 12）、零 SSE 事件名与字段变更、🔴 前端契约零变化且代码零改动）：**<br>① **🔴 §1.2 新增 §1.2.1「传输层不变量」两条**：ⓐ 🔴 **`Accept` 头不得改变 `/api/v1/**` 的响应形态**（`*/*` / `application/json` / `text/event-stream` 三者建流前失败必须**逐字节可比**）—— 本次缺陷正是此不变量被 Spring 内容协商悄悄打破：同一次限流，不带 `Accept` 得 `200 + code=10005`，带 `Accept: text/event-stream`（🔴 真实浏览器恒带）得 **`500 + 空体`**；ⓑ 🔴 **HTTP 状态码口径不二分** —— §1.2「一律 200」**原样适用于 SSE 端点的建流前失败**，🔴 明确否决"SSE 端点特殊、可返 4xx/5xx"这一读法（§1.5 已把非 200 例外**穷举**为 `GET /site/status`；前端 `streamRequest.ts` 的 `!response.ok → NetworkError` 是按本约定实现的**契约违反探测器**，🔴 禁止为兼容非 200 而放宽）。并写明实现侧唯一合法写法（`ResponseEntity<Result<T>>` + 显式 `Content-Type`）与 🔴 **禁止给 SSE 端点声明 `produces`**<br>② **🔴 §4.6.1 两段式判据重写（本版最关键的一处口径订正）**：判别依据由"错误码"改为 🔴 **「`meta` 是否已 flush / 响应是否已提交」** —— 🔴 **按错误码分域是错的**：`50003` / `30060` 在两侧都会出现，按码分域必然自相矛盾。同表明确 **`done` 义务**：建流前 = **无**（没有流），建流后 = **必发**<br>③ **🔴 §5.1 `done 必发` 补注义务边界**：本约束的适用前提是**流已建立**；🔴 @测试 不得因"建流前的 JSON 拒绝响应里没有 `done`"判违反（🔴 反向不放宽：`done` 缺失的唯一合法情形仍只有真实物理断连）<br>④ **§7.12 未建流行补注 + 登记 BUG-QUOTA-001**：明确该形态与 `Accept` **完全无关**；🔴 并声明该缺陷**不是限流专属** —— 同一路径的 `30070` / `10001` / `10004` / `20001~20005` / `50003` 全部同源（🔴 其中鉴权码退化为 500 空体会让前端**拿不到 code、无法跳 SSO**），故复验必须**同时**覆盖至少一个非限流码<br>⑤ **🔴 §8.3 新增 L 组 L1~L7，全部为签署前置门禁**（🔴 A~K 组编号与判据**零变化**）：**L1** MockMvc 层补 `Accept` 头复现（🔴 裁定 MockMvc **确实执行内容协商**，本项有效，不得以"不协商"为由跳过，且修复前**必须先红**）／**L2** 🔴 真实 HTTP 栈（`RANDOM_PORT` + JDK `HttpClient`）背书状态码 + `Content-Type` + 非空体（🔴 **不可由 L1 替代**：500 空体是 Tomcat `/error` 二次协商的产物，MockMvc 物理上观测不到）／**L3** 全局性证明（≥2 个码且含**非限流码**）／**L4** `Accept` 三取值一致性 + 反向断言无 406/415/5xx/空体／**L5** 🔴 反射扫描"返回 `SseEmitter` 的端点集合 ⊆ 已覆盖集合"+ 🔴 测试请求构造收敛到 `testsupport` 单一 helper 强制注头（🔴 逐用例手加头**不被接受**）／**L6** 实现纪律反向断言（无 `produces`、🔴 全库无 `completeWithError(`、两个 `void` 处理方法仍 `void`）／**L7** 🔴 前端零改动 + 探测器保留 + 桩 spec 顶部必须注明"不覆盖 L1~L4"<br>⑥ **本版确立的证据力规则（AR-029 ①，🔴 影响此后所有轮次）**：🔴 凡断言对象是 **HTTP 报文形态**（状态码 / `Content-Type` / 键集合 / 是否空体），**MockMvc 与前端 E2E 桩一律不构成证据**，必须由真实 HTTP 栈用例背书 —— 本缺陷能在 **1166 passed / 0 failed** 下存活，与 BUG-MCP-004（桩上游不校验消息形态）**同源**<br>⑦ **自查结论**：本版**零业务语义新增**；`done 必发`、响应体四字段、租户隔离、反硬编码、deadline 预算制、单一前导 `system` **六条红线全部未放宽**；🔴 未新增任何错误码（含未占用 `20000~20999`），`sys_config` 键总数仍 **35** |

> 📢 **V1.2.7 广播（🔴 传输层契约；零新接口 / 零新错误码 / 零新键 / 零 SSE 变更 / 🔴 前端零改动）**：
> - **@后端（🔴 有代码改动，但集中在 1 个生产文件）**：ⓐ 🔴 唯一必改生产文件 = `common/GlobalExceptionHandler.java`，每个**有响应体**的 `@ExceptionHandler` 返回类型改为 `ResponseEntity<Result<T>>` 并 🔴 **显式** `.contentType(MediaType.APPLICATION_JSON)`（统一走一个私有 helper，🔴 禁止逐个方法手写）；ⓑ 🔴 两个 `void` 处理方法（`AsyncRequestTimeoutException` / `AsyncRequestNotUsableException`）**保持 `void`**；ⓒ 建议同批把 `HttpMediaTypeNotAcceptableException` / `HttpMediaTypeNotSupportedException` 并入 `10001` 分支作为安全网；ⓓ 🔴 **不得**给 SSE 端点加 `produces`、**不得**按端点特判、**不得**把限流/额度改走 SSE `error` 帧、🔴 **永久禁止** `SseEmitter.completeWithError(`。
> - **@测试**：🔴 签署依据 = **§8.3 L 组 L1~L7，全部签署前置**（A~K 组零变化）。🔴 三条口径：ⓐ **MockMvc 确实做内容协商**，补 `Accept: text/event-stream` 有效且修复前**必须先红**；ⓑ 🔴 **HTTP 报文形态的断言必须由真实 HTTP 栈用例背书**，MockMvc 与 E2E 桩不构成证据（AR-029）；ⓒ 🔴 建流前失败**没有 `done` 义务**，不得据此判违反 `done 必发`。
> - **@前端**：🔴 **契约零变化、代码零改动**。修复后 `500 + 空体` 会变回 `200 + {code:10005, data.retryAfterSeconds}`，既有分支即可正常分流。🔴 **明令保留** `streamRequest.ts` 的 `!response.ok → NetworkError`（契约违反探测器），🔴 禁止改成"非 200 也解析 JSON body"。E2E 桩无需改，但 🔴 必须在 spec 顶部注明「本桩不模拟内容协商，不覆盖 L1~L4」。
> - **@UI / @产品经理**：🔴 无改动。产品侧唯一可感知变化 = 真实浏览器下限流/额度用尽终于能正确进入既有倒计时/用尽态。

> 📢 **V1.2.5 广播（用户维度限流与每日限额；🔴 零 SSE 变更、零新 action、零新埋点事件；新增 1 接口 + 1 错误码 + 3 键 + 2 表）**：
> - **@后端（🔴 有代码改动，逐项见 `architecture.md` ADR-020「实施落点」表与 §19 的 @后端 清单）**：ⓐ 🔴 新增 `quota` 包（策略解析 / 窗口计算 / Redis 预占与结算 / DB 账本 / `GET /api/v1/me/quota`）；ⓑ 🔴 `ChatController` 的准入改为**五步顺序**（§7.15.3），🔴 日额度**只读预检必须在 QPM 计数之前**、**预占必须在建消息之前**、**建消息/建流失败必须释放预占**；ⓒ `MessageRateLimiter` 🔴 **不再自行读 `sys_config`**（阈值由策略入参传入）并**删除小时窗分支**；ⓓ 🔴 结算发生在**首个"资源已消耗证据"帧 flush 之后**（ⓐ~ⓓ 集合，exactly-once）；ⓔ 3 键入 `ConfigKeys` + `REQUIRED_CONFIG`（同时**移除** `message_per_hour`）+ 2 条拒绝启动不变量 + 1 条 WARN；ⓕ 🔴 `TenantCacheKeys` 新增 2 键并加入**受保护运行时状态键**段。🔴 **五条禁止**：**禁止**代码中出现 `3`/`50` 作为阈值兜底、**禁止**租户覆盖非法时静默继承平台默认、**禁止**租户 `timezone` 非法时回落 UTC、**禁止**为额度快照给 SSE `done` 帧加字段、**禁止**为"回退 QPM"实现 DECR。
> - **@前端（🔴 有改动：1 个新接口 + 1 个新错误码 + 1 个新展示区）**：ⓐ 新增 `api/quota.ts` + `stores/quota.ts`，🔴 **匿名（`uid === null`）不得发起该请求**；ⓑ 🔴 **两态严格区分** —— `10005` 走既有 `rateLimitStore` 秒级倒计时；`30070` 🔴 **绝不进** `rateLimitStore`，改为写入 `quotaStore` 的 `exhausted` 态（用响应内快照直接落地，无需再拉一次）；ⓒ 用尽后 🔴 **保留输入与草稿、禁用发送按钮与 Enter 发送**，🔴 不得禁用复制/编辑/停止生成；ⓓ 校准时机：挂载、`visibilitychange → visible`、**每次生成 `done` 之后**、到达 `resetsAt`、`tenantId`/`uid` 变化（🔴 先清旧快照再加载，禁止短暂显示上一主体数据）；ⓔ 🔴 **禁止本地自减**作为最终事实；ⓕ 时区渲染用快照的 `timezone` + `resetsAt`（🔴 禁止用浏览器本地时区推算）；ⓖ 文案入 `locales/zh-CN.ts`（🔴 禁止 `.vue` 内联字面量）。
> - **@UI**：🔴 需要新视觉 —— Composer 状态区的**额度信息区**（`available` / `exhausted` / `unlimited` / `loading` / `error` 五态）及其与 QPM 倒计时的**层级关系**（🔴 同时满足时**日额度用尽态优先**展示，因为它阻断更久）；🔴 不得只用颜色表达状态；如需新增 Design Token 必须走 `tokens.css` 变量新增流程（🔴 不得在组件内写死色值/尺寸）。
> - **@测试**：🔴 签署依据 = **§8.3 K 组 K1~K16**（A~J 组编号与判据**零变化**）。四条口径务必注意：ⓐ 🔴 **K4 必须直接断言 Redis 分钟窗计数值**（"日额度用尽不增 QPM 计数"无法从响应体观测）；ⓑ 🔴 **K9 只在 Redis 可用下断言**（降级态的超发上界见 AR-024，🔴 不得在降级态判缺陷）；ⓒ 🔴 **K10 必须含 DST 双用例**（23h/25h + "当地 00:00 不存在"），🔴 不得以固定 24h 替代；ⓓ 🔴 **K16 必须做"删 Redis 镜像键后 `used` 不回退"**的反向断言（这是"Redis 丢数据 = 白得额度"这一漏洞的唯一守护）。
> - **@产品经理（🔴 需知会与追认 1 处手段变更，产品语义不变）**：PRD §8.11.6.4 写"通过当前发送/流式响应链路下发最新额度快照" —— 🔴 裁决改为「**`30070` 拒绝响应携带同形快照** + **生成 `done` 之后前端重新拉取权威快照**」，🔴 **不给 SSE `done` 帧加字段**（理由见 ADR-020 备选 E：`done` 有 6 个分支，逐个拼快照会新增 6 个"漏拼"错误点，且会引入"SSE 值与查询值不一致"的排障噪声）。🔴 可验收事实**完全不变**：结算后前端展示与权威快照一致、不依赖本地自减、用尽即时禁发。

> 📢 **V1.2.3 广播（3 点实现追认；🔴 零新接口、零新错误码、零新键（仍 32）、零 DDL、零新 action、零新事件名、零新字段、🔴 零业务代码返工）**：
> - **@测试（🔴 判据有 2 处变化）**：ⓐ **J4 后半句降为单测级**（`剩余 − 宽限 ≤ 0` 不下发确认卡）—— 该分支在「工具执行准入」就位后已是**防御性不变量**，IT 稳定构造需向生产代码植入时钟钩子，🔴 **不得因"缺 IT 证据"判缺陷或阻塞签署**；ⓑ **J7 由"三源"订正为"四源"**（不在清单内 / preflight 服务停用 / 执行前授权点查 / 执行期竞态），🔴 逐字不一致即判**安全缺陷**；🔴 `SSRF_REJECTED` 复用"未授权或已停用"这句不精确措辞**有意为之**，不得判为文案缺陷。其余 A~I 组与 J1~J3、J5~J6、J8~J10 判据**零变化**。
> - **@后端**：🔴 **零返工**。① 四源统一 `DENIED_FEEDBACK` 追认为**订正实现**；② 单测级覆盖**已足够**；③ 工具准入用 `definition.timeoutSeconds()` ✅ 正确且**并不保守**（与 `McpJsonRpcClient.callTimeout` 同源同公式的生成期快照），🔴 **不要**精确化（跨包签名变更仍被否决，📋 二期与 ADR-016 exchange deadline 一并做）。📋 唯一非阻塞建议 **R3**：`ToolFeedbackDiagnosticTest` 补第 4 例（执行期竞态源）。
> - **@前端 / @UI / @产品经理**：🔴 无改动。

> 📢 **V1.2.2 广播（2 个 P1 的契约裁决；🔴 零新接口、零新错误码、零 DDL、零新 action、零新事件名；新增 3 键 + 1 个 SSE 字段）**：
> - **@后端（🔴 有代码改动，逐项见 `architecture.md` ADR-017 落点表 12 项 + ADR-018 落点表 9 项）**：ⓐ 🔴 **最优先**：`SseEmitter` timeout 改为 `(chat.generation_deadline_seconds + chat.deadline_grace_seconds)*1000`，🔴 **禁止**再用 `runtime.requestTimeoutSeconds()`（它是**单轮**模型预算，把它当整流寿命正是 BUG-MCP-002 的根因）；ⓑ `ChatStreamRunner` 入口算一次 deadline，四处取 `remaining`（每轮模型 / 确认等待 / **工具执行准入** / 进入新轮），耗尽 → `error(50002)` + `done(timeout)` + `[DEADLINE]` 日志；ⓒ `GlobalExceptionHandler` 单列 `AsyncRequestTimeoutException`（**void + WARN**，🔴 不返回 `50003`）与 `AsyncRequestNotUsableException`（void + DEBUG），🔴 catch-all 语义不变；ⓓ `tool` 帧新增 `confirmExpiresInSeconds`（仅 `awaiting_confirmation` 非空）；ⓔ `ToolOrchestrator` 的失败回灌按 §7.6.4 二分（`30057`/`30053` 拼诊断，其余码固定措辞不变）；ⓕ `ContextAssembler` 注入平台纪律段（仅 catalog 非空、⚠️ **「独立 system 消息」已于 V1.2.4/ADR-019 作废 → 合并进唯一 system 消息的末块**、`{{currentTime}}` 替换、🔴 **不过** `SystemPromptBudget`）；ⓖ 3 键入 `ConfigKeys` + `REQUIRED_CONFIG` + 2 条**拒绝启动**不变量；ⓗ `application.yml` 的 `spring.mvc.async.request-timeout` → **600000**。🔴 **三条禁止**：**禁止**加工上游 `input_schema`（会连锁触发 `schemaChanged` → 自动撤授权）、**禁止**把纪律段计入 `system_prompt_max_chars`（会打挂既有满配租户）、**禁止**为"参数错误重试"跳过高风险确认（红线）。
> - **@前端（🔴 1 个新字段需消费）**：`tool` 事件在 `status=awaiting_confirmation` 时新增 **`confirmExpiresInSeconds`（number \| null）** —— 🔴 倒计时**优先**用它，`null`/缺失时才回退 `sys_config: tool.confirm_wait_seconds`（否则会出现"显示 120s 但 30s 就超时"的失真）。其余零变更：`error(50002)` + `done(finishReason=timeout, status=failed)` 是**既有**形态，按原超时态展示即可（BUG-MCP-002 修复后你会**稳定**收到该终帧，而不再是静默断流）。⚠️ 与你并行修的 BUG-MCP-003 不冲突：confirm 响应终态直接收敛仍是必要兜底（AR-022 ⑤）。
> - **@测试（签署依据）**：🔴 新增 **§8.3 J 组 J1~J10**，A~I 组编号与判据**零变化**。三条口径务必对齐：ⓐ 🔴 **J2 反向断言**：常规超时路径**不得**出现 `AsyncRequestTimeoutException`，也不得出现"无 `done` 且非用户断连"—— 传输层超时**不再是允许的路径**；ⓑ 🔴 **J8**：断言下发给模型的 schema 与 `mcp_tools.input_schema` **逐字相等**、连续 `discover` **不产生** `mcp.tool_grant_revoked`（守护"未偷偷加工 schema"）；ⓒ ⚠️ **J10**：P1-1 的复验判据**订正**为「失败后模型是否收到可自纠的诊断」（查回灌内容，可断言），🔴 **不得**把"模型 100% 不补可选参数 / 必然一次成功"当作签署阻塞项（属模型行为，不在 Albedo 控制范围）。
> - **@UI**：确认卡倒计时的**数据来源**改为服务端下发值（视觉与交互不变），无新增视觉稿需求。
> - **@产品经理（知会，无需追认，不改 PRD）**：ⓐ 单次生成有了明确上限（默认 **5 分钟，含等待用户确认的时间**），超时以"本次生成已超时，请重试"收敛且**保留已生成内容**；ⓑ 外部 MCP 工具的 schema 质量问题，平台**不做**代猜式修补（会引发"授权被自动撤销"等连锁故障），改为"把外部报错如实告诉模型让其自纠 + 平台级调用纪律引导"—— 🔴 因此"普通自然语句一次成功"是**高概率**而非**保证**。

> 📢 **V1.2.1 广播（G6′ 冲突消除 + 超时键订正；🔴 零新接口、零新错误码、零新键（仍 29）、零 DDL、零新 action、`data.result` 仍 9、🔴 零业务代码返工）**：
> - **@后端**：🔴 **无代码返工**。ⓐ GET 非 2xx **二分**（`3xx`/`401`/`403`/`407` 直接失败、其余退化 POST）已按你的实现写入契约，🔴 今后**不得**改回"一律退化"；ⓑ 连接测试预算 = **`mcp.discover_timeout_seconds`**（契约原文作废），🔴 仅需在 `McpJsonRpcClient.connectTimeout()` 的 javadoc 登记"一期无调用点 / 保留为 G7 不等式参照 + 二期握手预算"；ⓒ `McpRpcRequest` 保持独立 record；ⓓ 两项测试侧整改见 `architecture.md` 实施落点 #14（R1：补 GET `401` / `302` 两例并断言未退化 POST）与 #15（R2：线程纪律黑名单改正则 `\w+Async\s*\(` + 补 `orTimeout`/`completeOnTimeout`/`delayedExecutor`）。
> - **@测试**：I 组新增 **I10**（GET 非 2xx 二分），**I3 判据细化**（流被 `HttpRequest.timeout` 打断 → `timeout`/`30051`，不是 `30052`），并明确 **I 组签署前置 = 一次 `mvn -o verify` 全绿**（组件级测试可先行但不作为签署依据）。🔴 A~H 组 58 项编号与判据**零变化**。
> - **@前端 / @UI / @产品经理**：🔴 无改动（无接口、字段、错误码、视觉、产品语义变更）。

> 📢 **V1.2.0 广播（框架 §五；🔴 零新接口、零新错误码、零 DDL、零新 audit action）**：
> - **@后端**：🔴 有代码改动，按 `architecture.md` **ADR-016「实施落点」表 13 项**逐条执行。三条最易违反的红线：ⓐ 🔴 **不得**新增 `transport` 枚举值 / 不得改 §7.3.1 枚举校验 / 不得扩 `McpCheckResult`；ⓑ 🔴 **不得**用 `new Thread` / `Executors.new*` / `supplyAsync` / 无参 `join()`，🔴 订阅者内不得有任何阻塞操作；ⓒ 🔴 `finally` **必须**关流，且 `sessionId` 不得进入任何字段/缓存。
> - **@测试**：🔴 按 §8.3 **I1~I9** 增测，两侧都要覆盖（**同步形态不回归** + **异步形态可用**）；🔴 `initialize` 探测拿到 `-32601` 属**预期行为**，不得判缺陷。
> - **@前端 / @UI**：🔴 无改动。
> - **@产品经理（仅知会）**：MCP 生态中「2024-11-05 HTTP+SSE」形态的服务（如腾讯云 WSA 联网搜索）现已可接入；新增已知资源代价 **AR-020**。

> 📢 **广播义务（框架 §五）**：V1.1 属接口契约变更，@架构设计师 须同步通知 @前端（SSE 新字段 + confirm 交互 + 埋点白名单 + 新错误码文案）、@后端（§7 全量实现 + `ErrorCode` 新增 7 个常量 + `ConfigKeys` 新增 22 键）、@测试（§8.2 核对清单纳入 M3 用例）、@UI（高风险确认卡片与倒计时取 `tool.confirm_wait_seconds`）。
> 📢 **V1.1.1 增量广播（本版无新接口、无新错误码、无新 `sys_config` 键，但影响 DDL / 实现 / 断言口径）**：
> - @后端：🔴 按 §7.5.1 建 `skills` + `skill_versions` **两张表**（唯一键位置已变）；🔴 `local_tools` 按**单行 + 就地递增 version** 建表；🔴 SSRF 按 §7.6.3「原域名连接 + 每次重校验 + `-Dnetworkaddress.cache.ttl=10`」实现（**不要**尝试 pin IP）；🔴 `keyVersion` 恒 1、密文按 `v1:{iv}:{ct‖tag}` 解析、AAD 绑定 `mcp:{tenantId}:{mcpKey}`；🔴 `auditEventId` 返回 32 位不截断；🔴 用量聚合按 §7.11.1 口径表实现，`rateLimitedCount` 恒 0；🔴 Skill/MCP 工具授权/本地 Tool 授权**不加缓存**，直读 DB。
> - @测试：🔴 性能断言锚点改为**首个可见帧**（`delta` 或 `tool`），test-plan 需覆盖「首轮即工具调用」；🔴 denied/failed 互斥不变量按 §7.11.1 断言；🔴 `rateLimitedCount` 断言为 `0`（非 0 即缺陷）；🔴 不再断言 pin IP，改断言"每次调用前重校验 + 不跟随重定向 + 拒绝即审计"；🔴 不将三类预留缓存键纳入失效断言。
> - @前端：🔴 `auditEventId` 按 32 位字符串处理；🔴 **禁止**自造 `messageRateLimited` 事件名（未列入白名单，二期决策后再启用）；其余无改动。
>
> 📢 **V1.1.2 增量广播（G1~G12 裁决；🔴 无新接口、无新错误码、无新 `sys_config` 键、无 DDL 变更）**：
> - **@后端（返工点，精确到类/枚举）**：
>   ① 🔴 `SkillVariableResolver.resolveValues` —— 内置变量**不可被 `variable_values` 覆盖**（同名键忽略 + 保留 WARN），§7.5.3 G4；
>   ② 🔴 `McpCheckResult` 新增 `CONNECT_FAILED("connect_failed", unhealthy)`，`McpFailure.CONNECT_FAILED` 的 `checkResult` 由 `TIMEOUT` 改指新项，§7.4.2 G2（`last_check_result` 为 `VARCHAR(32)`，**不需改表**）；
>   ③ 🔴 `audit/AuditActions` 新增 `MCP_TOOL_GRANT_REVOKED = "mcp.tool_grant_revoked"`，工具发现的 `schema_changed` 降级与 `removed` 停用改写该 action（**不再复用** `tool.grant_denied`），§7.4.3 G3；
>   ④ 🔴 `SkillInjectionService` 停止直连 `platform/repository/TenantRepository`，改注入 `platform/service/TenantService`（`currentProfile()` / `profileOf()`），`architecture.md` §5.1.2 G11；
>   ⑤ 🔴 新增 2 个 `LocalToolHandler` 实现体 `datetime_now` / `calculator`（严禁 eval 类设施、不支持幂运算、阈值走 `input_schema`）+ 由 DBA 插入对应 `local_tools` 行，§7.7.1 G5；
>   ⑥ 🔴 `AgentCapabilityBinding` 的类注释把"待架构复核"改为"已裁决"，`localTool.ref_id → tenant_tool_grants.id` 为**正式契约**，§7.4.4 G1；
>   ⑦ 📋 `agentVersion` 递归校验（G10）为 **M3 签署前必须补齐**项，未补齐期间响应必须带 `warnings[] rule=referencesNotFullyChecked`，禁止伪报全量通过。
> - **@测试**：🔴 §8.2 新增 8 条核对项（G1~G9 相关）纳入 M3 用例；🔴 `calculator` 必须覆盖超长/非法字符/深嵌套/除零/代码注入表达式；🔴 `connect_failed` 与 `timeout` 分别构造断言；🔴 `mcp.tool_grant_revoked` 做数据核验；🔴 low 风险工具**不产生**审计行需反向断言；🔴 `SysConfigOverride` 必须 `try-finally` 还原（G12）。
> - **@前端**：本版无接口/错误码/字段变更，**无改动**（`display.tool_risk_labels` 已覆盖 `low/medium/high` 展示）。
> - **@产品经理（需追认，由主协调 Agent 转交）**：一期内置本地 Tool 清单 = `datetime_now` + `calculator`（属 REQ-TOL-002 一期范围收窄）；高风险确认的生产可达路径改由 MCP 工具承载。
>
> 📢 **V1.1.3 增量广播（①③④⑤⑥ + G10 + §7.12 裁决；🔴 无新接口、无新错误码、无 DDL 变更；新增 1 键 + 1 audit action）**：
> - **@后端（返工点，精确到类/常量）**：
>   ① 🔴 `sysconfig/ConfigKeys` 新增 `CHAT_SYSTEM_PROMPT_MAX_CHARS = "system_prompt_max_chars"`（group `chat`）+ 由 DBA 插入 `sys_config` 行（默认 `100000`）+ 加入 `config/StartupChecker.REQUIRED_CONFIG`（键总数 22 → **23**）；`chat/ContextAssembler` 在拼装 system 消息时**边拼边累加码点数**（`String.codePointCount`）并在超限处**立即短路** → `BusinessException(30060, rule=systemPromptBudgetExceeded)`，🔴 禁止任何截断分支；`StartupChecker` 增加 `chat.system_prompt_max_chars ≥ skill.instruction_max_chars` 的 **WARN**（不拒绝启动），§7.5.2 ①；
>   ② 🔴 `tool/ToolCallRecorder` + 状态机实现允许 `running → denied`（`errorCode=30050`），撤授权走 `AuditActions.TOOL_GRANT_DENIED`、SSRF 走 `AuditActions.MCP_SSRF_REJECTED`；🔴 **删除**把执行期竞态映射为 `failed + 30052` 的分支（`30052` 仅保留连接/传输/协议/上游鉴权原义），§7.8.1 ③；
>   ③ 🔴 `tool/ToolConfirmService`：回放路径（`replayed=true`）**不调用** `AuditWriter`（去掉 `reason` 的 `replayed:` 前缀写法），响应 `auditEventId` 置 `null`，仅记 WARN，§7.8.2 ④；
>   ④ 🔴 `audit/AuditActions` 新增 `TOOL_CONFIRM_CONFLICT = "tool.confirm_conflict"`；`ToolConfirmService` 的 `30055` 分支在**行锁内**先按 `(tenantId, action, objectType=toolCall, objectId)` 点查去重再写审计（同一 `toolCallId` 至多一条），审计失败返回 `50003` 且 🔴 **不得**改动已终态的 `tool_calls` 行，§7.8.2 ⑤ / §7.14；
>   ⑤ 🔴 `tool/ToolCatalogService` 产出的工具定义新增 `functionName` 字段（归一化规则见 §7.6.5），碰撞或 >64 字符 → `30060`（`rule=functionNameCollision` / `functionNameTooLong`）；`chat/ChatStreamRunner` 以 `Map<functionName, 定义>` 回查，🔴 删除任何 `replace("_", ":")` 之类的字符串还原逻辑；🔴 SSE / `tool_calls` / 审计一律仍记原始 `toolKey`，§7.6.5 ⑥；
>   ⑥ 🔴 `ConfigValidationService`（`objectType=agentVersion`）按 §7.3.1 G10 表补齐递归：固定 2 层 + `visited` 去重 + 环 → `30060 rule=circularReference` + ≤4 次批量查询 + 一并覆盖 system 提示预算与函数名碰撞；补齐后 `warnings[]` 不再输出 `referencesNotFullyChecked`（`includeReferences=false` 仍必须输出）；🔴 **M3 签署条件之一**；
>   ⑦ 📋 §7.12：🔴 **不要**为让 SSE 内 `10005` 可达而新增流内限流点；流内 `error.retryAfterSeconds` 一期恒 `null`。
>   🔴 **本轮 DDL 变更 = 0**（`tool_calls.decision` / `audit_logs` / `sys_config` 均为既有结构）。
> - **@测试**：🔴 §8.2 新增 7 条核对项纳入 M3 用例；重点新增四类断言 —— ⓐ **绑定多个 Skill 使 system 总长超 `chat.system_prompt_max_chars` → `30060` 且无截断**；ⓑ **`crm:lookup → crm_lookup` 归一化 + 构造两个归一化同名的 `toolKey` → `30060`**；ⓒ **`running` 后撤授权/改内网地址 → `denied`+`30050`（不是 `failed`/`30052`）且计入 `toolDeniedCount`**；ⓓ **回放不产生审计行（反向断言）+ 连续 3 次相反决定只产生 1 行 `tool.confirm_conflict`**；🔴 §7.12 的 SSE 内 `10005` 路径标为 Deferred-in-M3，**不写用例、不判缺陷**；🔴 G10 递归校验按 §7.3.1 判据断言（补齐后 `warnings` 消失）。
> - **@前端**：🔴 唯一影响 = **`replayed=true` 时 `data.auditEventId` 为 `null`**（不得依赖该字段）；🔴 收到 `30055` 仍**不重试**（否则仅产生冲突审计，服务端状态不变）；其余无接口/字段/错误码变更。
> - **@产品经理（需追认，由主协调 Agent 转交）**：无新增产品语义。仅两项**口径追认**：ⓐ system 提示总长上限为**平台侧技术护栏**（默认 10 万字符，超限拒绝而非截断），不构成对"Skill 数量"的产品限制；ⓑ SSE 内限流提示（生成中途被限流）一期**不存在**，属二期议题。
>
> 📢 **V1.1.4 增量广播（#1~#7 裁决 + §8.3 签署清单；🔴 无新接口、无新错误码、无 DDL 变更；补登 2 键，总数 23 → 25）**：
> - **@后端（返工点，精确到类/常量；🔴 本轮 DDL 变更 = 0）**：
>   ① 🔴 `sysconfig/ConfigKeys` 新增 `OBSERVABILITY_ANALYTICS_ENABLED = "analytics_enabled"` 与 `OBSERVABILITY_ANALYTICS_SAMPLE_RATE = "analytics_sample_rate"`（group `observability`）；两键**加入** `config/StartupChecker.REQUIRED_CONFIG`（键总数 22 → 23 → **25**）；`sys_config` 两行由 @后端 插入（已补插的行保留，值分别为 `true` / `1.0`，`value_type` 为 `BOOLEAN` / `NUMBER`，`is_frontend=0`）；🔴 `StartupChecker` 新增 `analytics_sample_rate ∈ [0.0, 1.0]` 校验并在越界时**拒绝启动**（⚠️ 与 `system_prompt_max_chars` 的 WARN 规格不同），§7.1.2 #1；
>   ② 🔴 `metrics/service/AnalyticsEventService` 的读取兜底由 **fail-open 改 fail-closed**：`analytics_enabled` 缺失/不可解析 → 按 `false`（全丢弃）；`analytics_sample_rate` 缺失/不可解析/越界 → 按 `0.0`（全丢弃）；日志级别 **WARN → ERROR**；响应仍 `code=0` 且 `discarded` 计入实际丢弃条数，§7.10.1 #1；
>   ③ ✅ **无需返工**：运行时 `30060` 仅 `code + message` 的现状已被本版升格为正式契约（§7.3.1 #2）。🔴 唯一新增要求 = 该路径必须补 **ERROR 日志**记 `requestId + tenantId + agentVersion + rule + objectType:objectId`（过 `LogScrubber`），供 DBA 反查；🔴 **禁止**在 SSE `error` 事件或终端用户 JSON 响应中补 `violations` 等字段级明细；
>   ④ ✅ **无需返工**：`ToolCatalogService` 运行时 5 次批量查询**符合契约**（§7.1.2 查询次数表已订正为 ≤5）；🔴 仅需在类注释登记"≤5 次批量、禁 N+1"的口径，🔴 **不要**为对齐旧文档的"≤3/≤4"改写为 join；
>   ⑤ 🔴 **必须补做（#4，认定为订正实现缺口而非新增要求）**：在 `tool/ToolOrchestrator` 置 `tool_calls → running` **之前**补一次授权点查（每次工具执行各一次，多轮循环每轮都查）—— MCP 走 `mcp_tools JOIN mcp_servers` 单行点查（`granted=1` AND `status='enabled'` AND `deleted_at IS NULL` AND server `enabled`），本地 Tool 走 `tenant_tool_grants JOIN local_tools` 单行点查（§7.7.2 授权四条件）；🔴 **≤1 次查询**，禁止拆多次、禁止缓存结果；拒绝时 `pending→denied` 或 `running→denied`，一律 `errorCode=30050` + `AuditActions.TOOL_GRANT_DENIED`，与状态流转同一独立短事务；🔴 不实现"执行中中断"（违反 ADR-008 第 8 条），§7.6.3 #4；
>   ⑥ ✅ **追认，无需返工**：`tool/ToolConfirmService` 的 `30055` 审计走**独立短事务**已被回写为正式契约（§7.8.2 ⑤ / §7.14 不变量 7）；🔴 仅需确认两点：**先提交审计再抛 `30055`**、去重点查仍在**行锁内**执行；🔴 在类注释登记该例外仅授予本路径；
>   ⑦ 🔴 **必须补做（#6，安全等级）**：抽出 `auth/TenantRoleGuard.require(TenantRoleEnum...)` 作为程序化 fail-closed 兜底的唯一实现，把 `McpAdminController.authorize()` / `ConfigValidateController` / `UsageMetricsController` 三处内联实现改为调用它（🔴 失败码仍为 `10003`，对外行为不变）；🔴 新增**静态扫描守护测试**（如 `TenantRoleGuardScanTest`）：反射扫出全部标注 `@TenantRole` 的 Controller 方法，断言其集合 ⊆ 已被兜底覆盖的集合，新增端点漏兜底即测试红；🔴 `StartupChecker.checkProductionBlockers()` 增加 `prod` profile 下 `eyes-auth.enabled=true` 的断言（否则启动失败），§3 #6 / `architecture.md` §8.2.1；
>   ⑧ 📋 §7.12（#7 维持）：🔴 仍**不得**为让 SSE 内 `10005` 可达而新增任何流内限流点。
> - **@测试（签署依据）**：🔴 以 **§8.3 M3 签署核对清单** 为签署视图逐条打勾（A~H 共 58 项），H 表内 6 项为**本文授权的 ⏸**，不得判缺陷；本版新增/变更的重点断言 —— ⓐ 删 `observability.analytics_enabled` 或 `analytics_sample_rate` 任一 → **启动失败**（E5）；ⓑ 把 `analytics_sample_rate` 置 `1.5` → **启动失败**（E7）；ⓒ 运行期破坏两键值 → 事件**全部丢弃**（`accepted=0`）+ ERROR，🔴 **不得**全量接收（E6，反向断言 fail-closed）；ⓓ SSE `error` 事件在 `code=30060` 时**不含** `violations`/`rule`/`objectId`（A4），管理端同一错误仍含完整 `violations`（G-5）；ⓔ 🔴 **撤 `mcp_tools.granted` / 撤本地 Tool 授权后下一次工具执行必须 `30050` 被拒**（C3），且**已进入 `invoke`** 的那一次允许完成 → **不判缺陷**（C5）；ⓕ `30055` 场景审计行数 = **1**（不是 0，B4）；ⓖ 🔴 在 `eyes-auth.enabled=false` 的 profile 下，`@TenantRole` 端点角色不足仍返 `10003`，出现 `code=0` 即**安全缺陷**（D3），且守护测试对"新增未兜底端点"必须变红（D4）；ⓗ 运行时清单构造断言 **≤5 次** DB 查询、校验入口 ≤4 次、执行前点查 ≤1 次（G-4）。
> - **@前端**：🔴 本版**无接口 / 字段 / 错误码变更，无改动**。仅一条口径确认：`code=30060` 的 SSE `error` 事件**恒不含**字段级明细，前端按通用错误态 + 可复制 `requestId` 展示，🔴 禁止解析或展示 `violations` 结构（§5.4.1 第 5 条已要求容忍字段缺失）。
> - **@UI**：无改动（`30060` 沿用既有"配置异常"错误态视觉）。
> - **@产品经理（需追认，由主协调 Agent 转交）**：**1 项需追认 + 1 项知会**。ⓐ 🔴 **需追认**：埋点开关与采样率改为 **fail-closed** —— 配置异常时**宁可丢埋点数据、也不超范围采集**，直接影响运营数据的完整性预期（配置误删期间数据会缺一段而非"照常全量"）；该取向是隐私优先的产品立场，请确认与 PRD §15.2 一致。ⓑ 知会（无需改 PRD）：撤销工具授权后存在一个**最长等于单次工具执行时长**的安全窗口（本地 Tool 毫秒级、MCP ≤30s），窗口内已开始的那一次调用会执行完成 —— 若产品要求"撤授权即刻中断执行中的调用"，属**新需求**，需立 REQ 并重开 ADR-008（涉及新增线程/中断机制，与现行"不新增线程池"红线冲突）。
>
> 📢 **V1.1.5 增量广播（G-0~G-5 裁决；🔴 零新接口 / 零新错误码 / 零新 `sys_config` 键（仍 25）/ 零新 audit action（仍 12）/ 零 DDL 变更；§8.3 判据总数与编号不变）**：
> - **@后端**：🔴 **本轮无代码返工、无 DDL**。四项**追认**（现状即正式契约，不得反向改动）：ⓐ `deleted_at` 只判 `mcp_servers`（G-1，🔴 不得为对齐旧文字给三张表加列）；ⓑ 执行前点查**不复查** `agent_capability_bindings`（G-2，🔴 预算维持 ≤1 次，也**不得**主动"加强"为复查绑定 —— 会破坏生成期清单自洽性）；ⓒ SSE `error` 恒 3 字段含 `retryAfterSeconds`（G-3，🔴 不得回退为 2 字段）；ⓓ `ConfigValidateController` 的 `localTool` 分支用 `@Permission(USER)` + 方法体内平台管理员判定 → `10003`（G-5，🔴 不要改成 `@Permission(ADMIN)`）。两项**仅需注释登记**（不改行为）：① `ToolOrchestrator` 点查方法注释登记"复查范围 = `granted`/`status`/server `status`+`deleted_at`；🔴 不含绑定（生成期快照，AR-019）"；② `PlatformCacheController.requirePlatformAdmin()` 注释登记"生产由 `PermissionAspect` 先以 `20000` 拦截，本兜底仅在 `eyes-auth.enabled=false` 时生效并返 `10003`（§3 二分口径）"。🔴 `AuditActions.ALL` 保持 **12 项**，新增 action 必须先回写 §7.14 + `architecture.md` §11.1.1 + §8.3 F3 计数。
> - **@测试（签署依据，🔴 本版只改判据文字，65 条用例与 58 项映射全部继续有效）**：ⓐ **F3**：audit action 断言由 11 项改 **12 项**，断言方式 = `AuditActions.ALL` **集合恒等**于 §7.14 表（清单见本版回复与 §7.14）；ⓑ **C3**：撤销判据只看 `granted` / `status`（+ `mcp_servers.deleted_at`），🔴 **不得**断言 `mcp_tools` / `tenant_tool_grants` / `local_tools` 的 `deleted_at`（列不存在）；ⓒ **C5ⓑ / C7 / H6**：🔴 生成中**解绑**后本轮仍可执行 → **不判缺陷**（AR-019 已授权），必测的是"下一次生成中该工具不再出现"；紧急止血断言走"撤 `granted`/停用 `status` → 下一次执行 `30050`"；ⓓ **A4**：`error` 事件做**精确键集合断言**（恰 `code`/`message`/`retryAfterSeconds` 三键，`retryAfterSeconds` 恒 `null`）；ⓔ **D3**：`@TenantRole` 端点仍断言 `10003`；🔴 `@Permission(ADMIN)` 端点（仅 `/platform/cache/evict`）按 profile 二分 —— test 断 `10003`、`eyes-auth.enabled=true` 环境断 `20000`，共同不变量 = **绝不 `code=0`**；`/admin/config/validate` 的 `localTool` 分支**恒 `10003`、不二分**。
> - **@前端**：🔴 **无接口 / 字段 / 错误码变更，无改动**。一条口径确认：SSE `error` 事件永久保留 `retryAfterSeconds`（一期恒 `null`），🔴 继续按"字段存在但可为 null"处理，不得依赖其非空。
> - **@UI**：无改动。
> - **@产品经理（知会，无需追认，不改 PRD）**：新增一类**已知残余窗口**（AR-019）：在生成过程中把某工具从 Agent 版本**解绑**，本轮对话剩余部分仍可能调用该工具（最长约"确认等待 + 剩余轮次 × 单次执行"），生效点为**下一次提问**。🔴 若需"解绑即刻生效"，运维应改用"撤销授权/停用工具"（下一次执行即拒）；要求"执行中立即中断"仍属新需求（同 V1.1.4 ⓑ 口径）。
