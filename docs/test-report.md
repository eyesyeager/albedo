# Albedo 每日对话额度展示与 MCP 定向验收报告

**版本**：V5.0  
**日期**：2026-08-17  
**执行人**：@测试工程师  
**轮次**：第 3 轮定向复验  
**测试类型**：Playwright MCP 真实浏览器验收 + 前端 Vitest + Playwright E2E；后端事实采用主控已完成的接口与 Maven 验证结果  
**关联需求**：REQ-LMT-003、REQ-QUOTA-001~005、REQ-MCP-001~003、REQ-CHAT-003、REQ-TOL-002、REQ-TEN-003  
**鉴权边界**：登录态、租户角色与匿名前端行为均为 **Mock 替身验证**，不代表真实 eyesUser 验签、吊销与账号状态联动已通过。

---

## 1. 本轮结论

**结论：不签署（FAIL）❌。**

每日额度前端核心实现整体通过：gift 首页与会话详情均展示额度；`resetsAt` 按 `Asia/Shanghai` 显示“明日 00:00”；正常、偏低、日额度用尽和自动化覆盖的 QPM 状态可区分；正常→偏低→用尽切换时 Composer 输入面与发送按钮 bounding box 完全不变；用尽后草稿保留、输入仍可编辑、发送按钮和 Enter 被禁用；匿名无额度请求/占位；done 后重取额度；gift/redbook 不串台；四档响应式、可访问性结构、console/network 基线通过。

但仍有两个阻塞签署的问题：

1. **BUG-MCP-004 仅部分关闭**：calculator 真实链路成功，证明原“第二条 system 导致上游 400/50002”已修复；但 `wsa-SearchPro` 虽真实执行成功，Agent 最终没有整合搜索结果，而是错误回答“遇到了参数错误”，不满足本轮明确要求的联网搜索端到端可用。
2. **真实 QPM 浏览器验证暴露 HTTP 500**：临时租户覆盖 `qpmLimit=1` 后，同分钟第二次发送没有返回契约要求的 HTTP 200 + `code=10005`，而是 HTTP 500，前端无法进入秒级倒计时态。主控此前接口验证 QPM=3 的第 4 条通过，说明问题集中在当前浏览器真实入口/异常映射或本轮运行态，需 @后端 定界。

**当前未关闭缺陷：P0=0，P1=2，P2=0，P3=0。**

---

## 2. 环境与执行纪律

| 项 | 实际环境 |
|---|---|
| OS | macOS / Darwin，Zsh |
| 后端 | `http://127.0.0.1:8080`，复用用户已启动服务 |
| gift dev | `http://localhost:5173` |
| redbook dev | `http://127.0.0.1:5173` |
| 浏览器 | Playwright MCP Chromium，真实用户点击/输入/键盘/多 origin/四档视口 |
| 鉴权 | 用户提供 uid=1001 Mock token；结论标注“Mock 替身验证” |
| 需求基线 | `docs/prd.md` V1.4、`docs/design-system.md` V1.3 §15、`docs/api-spec.md` V1.2.5 |
| 服务纪律 | **未启动、停止或重启任何服务** |
| 修改边界 | 仅更新本测试报告；未修改业务代码或测试代码 |
| 浏览器纪律 | 先 snapshot 侦察再操作；结束已执行 `browser_close` |

### 2.1 测试造数与清理

本轮只按用户授权操作：

- `user_daily_quota_usages`：gift/uid=1001 当日 `settled_count` 依次调整为 40（偏低）、50（用尽），真实生成后最终恢复为 **0**；
- `tenant_quota_policies`：插入临时 gift `qpmEnabled=true,qpmLimit=1`；完成后按纪律插入最新 **NULL 覆盖行** `V5-QPM-CLEAN` 撤销，不删除历史行；
- 未操作其他业务表，未执行 DDL，未调用 mysql MCP 写入。

清理后只读核对：

```text
user_daily_quota_usages: gift / uid=1001 / 2026-08-17 / settled_count=0
tenant_quota_policies 最新行: gift / qpmEnabled=NULL / qpmLimit=NULL /
                                  dailyQuotaEnabled=NULL / dailyQuotaLimit=NULL /
                                  note=V5-QPM-CLEAN
```

**清理结论：测试额度计数与临时 QPM 覆盖均已恢复干净。**

---

## 3. 主控已完成的后端验证结论（纳入本报告，不重复执行）

| 验证项 | 实测结论 | 判定 |
|---|---|:---:|
| BUG-MCP-004 calculator | `pending → running → succeeded`，4096×77=315392，`done(stop,completed)`，reasoning 正常 | PASS |
| `confirmExpiresInSeconds` | 低风险 tool 帧为 `null`，符合契约 | PASS |
| QPM=3 | 第 4 条返回 HTTP 200 + `10005` + `retryAfterSeconds=33` | PASS |
| QPM 反向不变量 | 被拒请求不占日额度，`used` 停在 13 | PASS |
| `30070` | `data` 恰 9 键同形快照，无 `retryAfterSeconds` | PASS |
| 租户覆盖即时生效 | limit 50→13，无需重启、零缓存直读 | PASS |
| 跨租户额度 | gift 13/13 用尽时 redbook 仍 50/50 available | PASS |
| 租户时区 | `resetsAt=2026-08-17T16:00Z` = 北京时间次日 00:00 | PASS |
| 匿名接口 | `GET /api/v1/me/quota` → `20001` | PASS |
| 后端全量 | `mvn -o verify` BUILD SUCCESS；394 单测 passed；290 IT passed / 4 skipped | PASS |

> 上表是主控提供的已知事实；本测试工程师本轮没有重复执行后端接口或 Maven。

---

## 4. 前端浏览器定向验收明细

### 4.1 额度可见性、时区与租户隔离

| 验收项 | 浏览器实测 | 判定 |
|---|---|:---:|
| gift 首页 | Composer 下方显示“今日剩余 50 次 / 已用 0/50 / 明日 00:00（Asia/Shanghai）重置” | PASS |
| gift 会话详情 | `/c/3129` 使用同构额度轨；用尽时仍紧邻 Composer | PASS |
| 时区渲染 | 接口 `resetsAt=16:00Z`，UI 显示“明日 00:00（Asia/Shanghai）”，未显示 UTC 16:00，也未按浏览器本地时区误算 | PASS |
| 跨租户 | gift 人为 50/50 用尽时，redbook 同 uid=1001 显示 0/50、剩余 50；未残留 gift 快照 | PASS |
| 匿名 | token 清空后的新导航中，额度轨未挂载、无占位/骨架；新导航网络无 `/api/v1/me/quota` | PASS（Mock 替身） |

匿名验证说明：Playwright 会话历史中存在清 token 之前的一次 quota 请求（带 uid=1001 token）；在 token 已为 null 后重新导航 `/?anonymous-check=1`，新页面网络列表没有 quota 请求，DOM `quotaMounted=false`。

### 4.2 四态、互斥和稳定布局

1. **正常态**：50/50、remaining=50，无图标、发送可用。
2. **偏低态**：40/50、remaining=10，`remaining/limit=0.2`，出现可见图标与“今日剩余 10 次”。
3. **QPM 态**：自动化 `quota-composer.spec.ts` 在 desktop/tablet/mobile 覆盖 `10005 + retryAfterSeconds=42`，断言秒级倒计时、发送临时禁用、每日额度副信息保留、无阈值/用尽语义；真实浏览器入口因本轮 HTTP 500 未能进入该态，见 BUG-QUOTA-001。
4. **用尽态**：50/50，显示“今日额度已用完 · 已用 50/50；明日 00:00（Asia/Shanghai）重置”，Calendar 图标，发送禁用，**没有任何 `N 秒` 文案**。

1200px 首页状态切换 bounding box：

| 元素 | 正常 | 偏低 | 用尽 | 结论 |
|---|---|---|---|---|
| textarea | x=397, y=410.203, w=642, h=50 | 完全相同 | 完全相同 | 0px 跳动 |
| send | x=1047, y=412.203, w=44, h=44 | 完全相同 | 完全相同 | 0px 跳动 |
| quota rail | x=380, y=473.203, w=720, h=34.094 | 完全相同 | 完全相同 | 0px 跳动 |

DOM 证据：四套 `.quota-panel` 同时存在，只有一个 `.is-active`；其余 `visibility:hidden; opacity:0; aria-hidden=true`，符合稳定 Grid 方案。

### 4.3 日额度用尽交互

真实用尽态验证：

- 先输入草稿：`额度用尽后的草稿必须保留：不要清空我`；
- 切换到 50/50 并重新加载后，textarea 值原样保留；
- textarea `disabled=false`，仍可编辑；发送按钮 `disabled=true`；
- 聚焦 textarea 后模拟 Enter，URL、会话数、草稿均不变，网络无消息发送 POST；
- textarea/send 均通过 `aria-describedby="... composer-quota-rail"` 关联原因；
- 用尽态无秒级倒计时，QPM 与日额度提示未混用；
- 生成中 Composer 的停止操作在确认等待时仍为 `disabled=false`，用尽逻辑未改变停止按钮操作位；自动化继续覆盖 stop ≤1s。

### 4.4 done 后额度自动更新

真实发送 `请使用计算器算 4096×77，只给出结果。`：

```text
#264 GET  /api/v1/me/quota                    发送前 50/50
#267 POST /api/v1/conversations/new/messages HTTP 200，calculator 成功，回答 315392
#271 GET  /api/v1/me/quota                    done 后重取，显示 49/50
```

页面从“今日剩余 50 次 / 已用 0/50”自动更新为“今日剩余 49 次 / 已用 1/50”。网络顺序证明实现为 done 后重新 GET 权威快照，不依赖 SSE 附带额度帧。

### 4.5 可访问性

| 检查项 | 实测 | 判定 |
|---|---|:---:|
| live region | 额度轨存在单一 `role=status aria-live=polite aria-atomic=true`；可见倒计时与隐藏播报节点解耦 | PASS |
| 不逐秒播报 | 自动化断言 QPM tick 不进入 live region；Vitest `quota.spec.ts` 与 E2E 均通过 | PASS |
| 不抢焦点 | 状态切换无自动 focus；用尽时 textarea 可继续持有焦点；工具确认卡出现时 activeElement 未被移入卡片 | PASS |
| 不只靠颜色 | 偏低有 Gauge/Triangle，QPM 有 Clock+秒，日用尽有 Calendar+明日重置文字 | PASS |
| ARIA 原因 | textarea 与发送按钮均 `aria-describedby` 指向额度轨 | PASS |

### 4.6 响应式四档

| 视口 | 状态轨 | 页面横向滚动 | 发送命中区 | 判定 |
|---:|---|---:|---:|:---:|
| 375×812 | 两行用尽文案，`Asia/Shanghai` 视觉降级为“租户时区”；rail 343px | 375=375 | 44×44 | PASS |
| 768×900 | rail 720px，一行/自然换行 | 768=768 | 44×44 | PASS |
| 1024×900 | 主栏受侧栏影响 rail 696px，未越界 | 1024=1024 | 44×44 | PASS |
| 1440×900 | rail 固定 720px，不随外侧留白拉宽 | 1440=1440 | 44×44 | PASS |

四档均无页面级横向滚动，额度轨未挤压发送按钮。

### 4.7 console / network

- 正常额度、偏低、用尽、calculator、搜索确认链路：console error=0，产品网络 404=0。
- 临时 QPM=1 的第二次真实发送产生 HTTP 500，并新增两条 console error；这是 BUG-QUOTA-001 的证据，不能计入“洁净通过”。
- 全量 Playwright 的 console hygiene 用例通过，说明既有打桩基线仍为 0 error / 0 404。

---

## 5. BUG-MCP-004 / BUG-MCP-005 复验

### 5.1 BUG-MCP-004：部分关闭

#### calculator

真实输入：`请使用计算器算 4096×77，只给出结果。`

- 工具历史：`calculator`，`status=succeeded`，`resultSummary={expression:4096 * 77,result:315392}`；
- Agent 回答：`315392`；
- reasoning 正常显示；
- 页面无 50002；
- `confirmExpiresInSeconds=null`（低风险工具，符合契约）。

**结论：原上游 system 消息 400/50002 根因已修复，calculator 路径 PASS。**

#### wsa-SearchPro

真实输入：`帮我搜索一下最近关于 DeepSeek 的新闻`

- 高风险确认卡出现，toolKey=`web_search:wsa-SearchPro`；
- 参数摘要显示 `Mode=0, Query=DeepSeek`；
- 允许执行后确认接口成功，状态进入 running；
- 历史接口最终显示 toolCall `status=succeeded,errorCode=null`，说明真实 MCP 调用已执行成功；
- 但 Agent 最终回答：`很抱歉，目前搜索DeepSeek的新闻时遇到了参数错误，无法直接获取最新资讯...`；
- reasoning 显示收到 `illegal Mode` 诊断，并计划第二次使用 `{"Mode":"0","Query":"DeepSeek"}`，但没有产生第二轮 toolCall，也没有整合任何真实搜索结果。

**结论：FAIL。** 工具 transport/执行已成功，但结果内容与模型续轮编排不一致，用户没有得到真实新闻结果，BUG-MCP-004 不能完全关闭。

### 5.2 BUG-MCP-005：前端修复通过

代码与自动化证据：

- `frontend/src/types/tool.ts` 已接收 `confirmExpiresInSeconds`；
- `frontend/src/stores/toolConfirm.ts` 明确帧值优先于全局 `tool.confirmWaitSeconds`；
- `frontend/src/utils/toolCall.ts` 统一解析；
- Vitest `toolConfirmExpiry.spec.ts` 19 条、`toolConfirmStore.spec.ts` 16 条、`toolConfirmCard.spec.ts` 21 条全部通过；
- Playwright E2E 覆盖 `confirmExpiresInSeconds` 与倒计时，desktop/tablet/mobile 全绿；
- 真实确认卡从可见约 102 秒递减至 24 秒，未出现负数，停止按钮仍可用，卡片出现未抢焦点。

本轮 MCP 读取正在进行中的 SSE response body 时超时，未能在交互工具中逐字保存最初 awaiting 帧；但前端真实 UI 与代码/自动化共同证明已不再固定 120 秒。该结论限定为：**BUG-MCP-005 前端消费与倒计时修复 PASS**。

---

## 6. 自动化全量结果

| 套件 | 命令 | 结果 | 判定 |
|---|---|---:|:---:|
| 后端 JUnit / MockMvc | 主控：`cd backend && mvn -o verify` | **394 unit passed；290 IT passed / 4 skipped；BUILD SUCCESS** | PASS（主控事实） |
| 前端 Vitest | `cd frontend && npx vitest run` | **25 files / 386 tests passed** | PASS |
| Playwright E2E | `cd frontend && npx playwright test` | **96 passed** | PASS |

**自动化合计：1,166 passed / 4 skipped / 0 failed。**

自动化全绿不覆盖真实外部搜索结果质量，也未发现本轮真实 QPM=1 浏览器入口 HTTP 500；因此不能以自动化全绿替代真实浏览器结论。

---

## 7. 缺陷报告

### BUG-MCP-004（重新定界）

`[P1][MCP 联网搜索] wsa-SearchPro toolCall 最终 succeeded，但 Agent 回答“遇到参数错误”且未输出任何新闻 → 上游结果包含 illegal Mode 诊断/模型续轮未再次发起工具，工具成功终态与用户可见结果不一致 → @后端 + @架构师 → backend/src/main/java/com/eyes/albedo/tool/McpToolExecutor.java:154-163；backend/src/main/java/com/eyes/albedo/tool/ToolOrchestrator.java:296-300,598-603；backend/src/main/java/com/eyes/albedo/chat/service/ContextAssembler.java:473-503`

**复现步骤**：

1. 访问 gift，注入 uid=1001 Mock token；
2. 输入“帮我搜索一下最近关于 DeepSeek 的新闻”；
3. 确认卡出现后点击“允许执行”；
4. 等待 toolCall 与回答完成；
5. 刷新 `/c/3135` 并查看 `/api/v1/conversations/3135/messages?page=1&pageSize=100`。

**期望结果**：确认后 wsa-SearchPro 返回真实搜索结果，Agent 提炼并输出近期 DeepSeek 新闻及来源。

**实际结果**：toolCall `status=succeeded,errorCode=null`；reasoning 记录 `illegal Mode`；最终只显示“遇到了参数错误”，没有真实新闻或来源。

**Console/Network 证据**：确认 `POST /api/v1/messages/5033/tool-calls/1762/confirm` HTTP 200，`data.status=running`；会话历史 HTTP 200，toolCall succeeded；页面 console error=0、network 404=0。

**截图**：`quota-search-failure-v5.png`。

**修复建议**：@后端 先核对 MCP 成功正文是否被错误归为 succeeded，以及 `Mode` 的 schema/type 与供应方真实要求；@架构师 裁决“上游返回错误语义但 transport 成功”是否应映射 30053/30057，确保模型能重试且 toolCall 终态不撒谎；补一条真实/契约测试：最终回答必须包含搜索结果而非仅 tool succeeded。

### BUG-QUOTA-001

`[P1][QPM/聊天准入] gift 临时覆盖 qpmLimit=1 后，同分钟第二次浏览器发送返回 HTTP 500，未按契约返回 HTTP 200 + code=10005，前端无法显示秒级倒计时 → QPM BusinessException 在真实 SSE Controller 入口未被业务响应映射或运行态异常逃逸 → @后端 + @架构师 → backend/src/main/java/com/eyes/albedo/chat/controller/ChatController.java:112-134；backend/src/main/java/com/eyes/albedo/chat/service/GenerationAdmission.java:57-67；backend/src/main/java/com/eyes/albedo/chat/service/MessageRateLimiter.java:115-140`

**复现步骤**：

1. 插入 gift 临时覆盖 `qpm_enabled=true,qpm_limit=1`；
2. uid=1001 在 gift 首页发送第一条并等待完成；
3. 同分钟在同一会话发送第二条；
4. 查看 Composer、console 和 network。

**期望结果**：第二条 HTTP 200，body `code=10005`、`data.retryAfterSeconds≥1`；前端显示“发送太频繁，N 秒后可继续”，草稿保留，发送临时禁用。

**实际结果**：`POST /api/v1/conversations/3136/messages` 返回 HTTP 500、空响应；额度轨仍为普通 49/50，没有秒级倒计时；console：

```text
Failed to load resource: the server responded with a status of 500
[chat] 流式生成失败 NetworkError: HTTP 500
```

**截图/日志**：Playwright snapshot `page-2026-08-17T08-39-59-248Z.yml`；network request #141；console log `.playwright-mcp/console-2026-08-17T08-38-43-671Z.log`。未重启服务、未改业务代码。

**清理**：已插入 `V5-QPM-CLEAN` NULL 行撤销覆盖，并把 gift/uid=1001 当日计数恢复 0。

**修复建议**：@后端 复现并检查 controller 返回类型与 `BusinessException(10005)` 在 `ResponseEntity<SseEmitter>` 方法中的异常解析路径；保证建流前拒绝被统一包装为 HTTP 200 JSON。@架构师 对照主控“QPM=3 第 4 条已通过”的接口路径，确认两者是否走了不同 endpoint/异常处理器。

---

## 8. 签署门槛核对

| 门槛 | 本轮结果 | 判定 |
|---|---|:---:|
| P0 用例 100% | 无 P0 失败 | PASS |
| P1 ≥95%，当前无未关闭 P1 | 仍有 BUG-MCP-004、BUG-QUOTA-001 两个 P1 | **FAIL** |
| REQ-QUOTA 前端信息与用尽交互 | 首页/详情、草稿、禁发送、时区、done 刷新均通过 | PASS |
| 四态视觉 | 正常/偏低/用尽真实通过；QPM 自动化通过但真实入口被 HTTP 500 阻断 | **FAIL** |
| Composer 无布局跳动 | 正常→偏低→用尽 bbox 0px 变化；自动化含 QPM | PASS |
| 匿名不请求 | 新匿名导航无 quota request/DOM/骨架 | PASS（Mock 替身） |
| 跨租户 | gift/redbook 独立 | PASS |
| 响应式 375/768/1024/1440 | 无横向滚动，发送 44×44 | PASS |
| 可访问性 | polite live、tick 解耦、不抢焦点、图标+文字 | PASS |
| BUG-MCP-004 | calculator 通过；搜索无真实整合结果 | **FAIL** |
| BUG-MCP-005 | 帧值消费代码/自动化/真实递减通过，无负数 | PASS |
| console/network | 常规路径 0 error/0 404；真实 QPM 路径出现 HTTP 500 + 2 errors | **FAIL** |
| 全量自动化 | 1,166 passed / 4 skipped / 0 failed | PASS |
| 测试数据清理 | gift used=0；最新策略 NULL 行恢复默认 | PASS |
| 服务纪律 | 未重启任何服务 | PASS |

---

## 9. 最终签署与协作通知

**@测试工程师签署：❌ FAIL。**

@后端 + @架构师 🔧 修复请求
- **问题描述**：wsa-SearchPro 工具终态为 succeeded，但最终回答仍称参数错误且无真实搜索结果；工具终态与用户价值不一致。
- **影响范围**：gift 高风险联网搜索、BUG-MCP-004 完整闭环。
- **期望结果**：真实搜索返回可用新闻与来源；错误正文不得被误记为成功结果，模型可按诊断正确重试。
- **优先级**：P1
- **相关文件**：`McpToolExecutor.java`、`ToolOrchestrator.java`、`ContextAssembler.java`

@后端 + @架构师 🔧 修复请求
- **问题描述**：真实浏览器同分钟 QPM 超限返回 HTTP 500，未返回 HTTP 200 + 10005，前端无法进入倒计时态。
- **影响范围**：REQ-LMT-003、REQ-QUOTA-004、所有真实用户限流体验。
- **期望结果**：建流前 QPM 拒绝严格按统一响应契约返回，并由前端显示秒级倒计时。
- **优先级**：P1
- **相关文件**：`ChatController.java`、`GenerationAdmission.java`、`MessageRateLimiter.java`

@所有专家 📢 第 3 轮测试结果
- **本轮测试**：前端 Vitest 386/386、Playwright 96/96；主控后端 684 passed / 4 skipped；合计 1,166 passed / 4 skipped。
- **前端额度**：除真实 QPM 入口外，其余定向验收通过；布局 0px 跳动、时区/草稿/匿名/跨租户/响应式均通过。
- **失败明细**：BUG-MCP-004 → @后端/@架构师；BUG-QUOTA-001 → @后端/@架构师。
- **报告**：`docs/test-report.md` V5.0。
- **需要动作**：责任方修复并回执后，定向复验真实 QPM 10005 与 wsa-SearchPro 最终搜索结果，再决定签署。
