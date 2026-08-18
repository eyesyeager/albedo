# Albedo M1 回归与 M3 终验测试计划

**版本**：V1.1  
**日期**：2026-08-13  
**负责人**：@测试工程师  
**需求基线**：`docs/prd.md` V1.2、`docs/requirements.md` V1.2  
**契约基线**：`docs/api-spec.md` V1.1.4（唯一接口契约）  
**架构基线**：`docs/architecture.md` V1.3.3  
**设计基线**：`docs/design-system.md` V1.1  
**历史说明**：§1～§8 保留 M1 V1.0 原始计划；§9 起为本版新增的 M3（含 M2-min）终验计划。

## 1. 目标与范围

本轮验证 M1“租户识别 + 耶瞳 SSO + 已发布站点配置 + 已发布 Agent + 会话 + 流式对话”的可用闭环，并对跨租户隔离、Token/XSS 安全、统一响应契约执行一票否决检查。

### 1.1 M1 正式签署范围

- `REQ-TEN-001~004`
- `REQ-CFG-001`
- `REQ-AUTH-001~004`
- `REQ-AGT-004`
- `REQ-CON-001~002`
- `REQ-CHAT-001~002、REQ-CHAT-004`
- 横向基线：`AC-API-001/002、AC-NFR-001~004`

### 1.2 里程碑边界

用户清单中的以下场景保留稳定 TC-ID，但不计入 M1 通过率，也不得因尚无正式 REST 契约判定 M1 失败：

- 配置草稿/校验/发布/回滚、发布冲突：`REQ-CFG-002`，归 M2。
- Agent 增删改查、复制、排序、默认项、发布治理：`REQ-AGT-001~003`，归 M2；M1 只验证已发布启用 Agent 的读取和运行约束。
- 租户级消息限流：`REQ-LMT-001`，归 M3。

上述后续能力若已有 Service 自动化，可作为前置质量证据执行，但状态记为“超前覆盖”，不替代后续里程碑的正式接口/E2E 验收。

## 2. 分层策略

| 层次 | 工具与位置 | 重点 | 执行原则 |
|---|---|---|---|
| 后端单元 | JUnit 5 + Mockito + AssertJ；`backend/src/test/java/**/*Test.java` | 租户上下文、缓存键、幂等、边界、成员禁用、标题与限流 | 快速、隔离；不依赖真实外部 SSO/AI |
| 后端集成/契约 | Spring Boot Test + MockMvc；`backend/src/test/java/**/*IT.java` | HTTP 200/body.code、分页、跨租户 10004、软删、SSE 顺序与持久化 | 测试数据事务/夹具清理；ID 按 string 断言 |
| 实际接口 | `curl` + 本地 8080 | Host 差异、真实进程响应、性能、SSE 首帧、日志 | 按 `body.code` 断言；`/site/status` 是唯一非 200 例外 |
| 前端单元 | Vitest；`frontend/tests/unit/` | 20000~20005、30001 不误判、续期、URL 清理、IME、Markdown、SSE 解析 | 不依赖网络；显式验证 localStorage 和跳转 |
| E2E | Playwright MCP 探索 + `@playwright/test`；`frontend/tests/e2e/` | 两租户品牌、SSO 跳转、交互、四档响应式、Dark、可访问性、console/network | 先 snapshot 再行动；证据截图落 `__screenshots__/` |
| 数据一致性 | mysql MCP，只读 SELECT | tenant_id、软删、惰性建户、消息状态、版本不可变 | 严禁 INSERT/UPDATE/DELETE/DDL |
| 静态安全/契约 | 代码扫描 + 现有守护测试 | 手拼缓存键、危险接口、未登记码、敏感日志 | 发现契约冲突只上报 @架构师，不擅改契约 |

## 3. 覆盖率与放行目标

| 指标 | 目标/门槛 |
|---|---:|
| M1 REQ 覆盖率 | 100% |
| M1 AC 覆盖率 | 100% |
| M1 关联 EX 覆盖率 | 100%（不能实测者必须逐条说明边界） |
| P0 用例通过率 | **100%** |
| P1 用例通过率 | ≥95% |
| 跨租户安全用例覆盖率 | **100%** |
| 跨租户安全用例通过率 | **100%** |
| 后端 Service/核心工具类行覆盖目标 | ≥80%，关键隔离/鉴权分支 100% |
| 前端请求、流解析、输入与 Markdown 工具行覆盖目标 | ≥90%，鉴权/XSS 分支 100% |
| E2E | M1 核心闭环、四档视口、Light/Dark、SSO 第 1/3 层全部覆盖 |
| 未关闭缺陷 | P0/P1 = 0 |

> 真实 SSO 无自动化账号时，第 2 层“有效登录态回归”允许阻塞，但第 1 层未登录跳转和第 3 层错误码 Mock 必须完成；该阻塞会阻止 @测试签署，不得虚报通过。

## 4. 数据、环境与证据

- macOS / Darwin，Chrome（Playwright Chromium）。
- 后端 `http://127.0.0.1:8080`；前端必须 `http://localhost:5173` 与 `http://127.0.0.1:5173`。
- dev Host：`localhost:5173 → gift`、`127.0.0.1:5173 → redbook`。
- 造数只允许现有测试夹具/接口；mysql MCP 仅 SELECT。
- 接口证据记录 HTTP、`body.code`、timestamp、耗时；SSE 保存原始事件序列与首帧/首字耗时。
- E2E 截图落 `frontend/tests/e2e/__screenshots__/`；缺陷同时保存 console/network/日志。

## 5. M1 用例集

### 5.1 租户识别与跨租户隔离

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-TEN-001 | P0 | REQ-TEN-001 / AC-TEN-001 | Host=`localhost:5173` 返回 `tenantId=gift`、code=0 | curl/IT |
| TC-M1-TEN-002 | P0 | REQ-TEN-001 / AC-TEN-001 | Host=`127.0.0.1:5173` 返回 `tenantId=redbook`、code=0 | curl/IT |
| TC-M1-TEN-003 | P0 | REQ-TEN-001 / EX-001 / AC-NFR-004 | 未知 Host：API HTTP 200+30010；`/site/status` HTTP 404+noindex | curl/E2E |
| TC-M1-TEN-004 | P0 | REQ-TEN-004 / AC-TEN-006 / EX-002 | suspended：API 30011；状态页 403，无品牌/Agent/登录数据 | IT/E2E |
| TC-M1-TEN-005 | P0 | REQ-TEN-004 / EX-006 / AC-NFR-004 | enabled 但无可用配置：API 30012；状态页 503 | IT/E2E |
| TC-M1-TEN-006 | P0 | REQ-TEN-001 / AC-TEN-007 / EX-028 | prod profile 开启 dev 映射必须启动失败；生产默认关闭 | 单元/配置审查 |
| TC-M1-TEN-007 | P0 | REQ-TEN-001 / AC-NFR-002 | `trust_forwarded_host=false` 时伪造 `X-Forwarded-Host` 不生效 | curl/单元 |
| TC-M1-TEN-008 | P0 | REQ-TEN-002 / AC-TEN-002 / EX-003 | redbook 伪造 query/header `tenantId=gift` 被忽略且不报错，仍为 redbook | curl/IT |
| TC-M1-TEN-009 | P0 | REQ-TEN-003 / AC-TEN-004 / EX-004 | gift 会话 ID 在 redbook Host 访问返回 HTTP 200+10004，不泄露 gift | IT/curl |
| TC-M1-TEN-010 | P0 | REQ-TEN-003 / AC-TEN-004 | 跨租户 Agent ID 解析返回 10004 | Service IT |
| TC-M1-TEN-011 | P0 | REQ-TEN-003 / AC-TEN-004 / EX-006 | 跨租户站点配置版本 ID 不可读/写，按不存在处理 | Service IT/数据核验 |
| TC-M1-TEN-012 | P0 | REQ-TEN-003 / AC-TEN-005 / AC-NFR-002 | 缓存与幂等键均包含 tenantId；禁止手拼 Redis key/裸 `@Cacheable` | 单元/静态扫描 |
| TC-M1-TEN-013 | P0 | REQ-TEN-003 / AC-TEN-005 | 同 uid 同幂等键在两租户不共享结果；两租户 Agent 同 key 可共存 | 单元/IT |
| TC-M1-TEN-014 | P0 | REQ-TEN-003 / AC-TEN-003 / AC-NFR-001 | 两租户标题、Agent 名称与品牌数据肉眼可辨且无串租户 | curl/E2E |
| TC-M1-TEN-015 | P0 | REQ-TEN-003 / AC-TEN-005 / EX-025 | 日志链路含租户/request 维度且不含 token/消息正文 | 日志审查 |
| TC-M1-TEN-016 | P0 | REQ-TEN-003 / AC-NFR-001 | 消息限流键 tenant+uid 隔离，不跨租户共享计数 | 单元（M3 行为前置守护） |

### 5.2 SSO、鉴权与成员

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-AUTH-001 | P0 | REQ-AUTH-001/002 / AC-AUTH-001 | 未登录触发受保护操作整页跳 SSO，含 `clientId=361925` 与当前页 `redirectUrl` | E2E |
| TC-M1-AUTH-002 | P0 | REQ-AUTH-001 / AC-AUTH-002 / EX-007 | 回跳 `authorization` 写 localStorage，立即清 URL，保留安全参数 | Vitest/E2E |
| TC-M1-AUTH-003 | P0 | REQ-AUTH-003 / AC-AUTH-003 | 受保护请求 Header 携带 authorization | Vitest/network |
| TC-M1-AUTH-004 | P0 | REQ-AUTH-003 / AC-AUTH-003 / EX-027 | 响应头新 authorization 在下一请求前回写 localStorage | Vitest/E2E Mock |
| TC-M1-AUTH-005 | P0 | REQ-AUTH-004 / AC-AUTH-004 / EX-026 | 20000~20005 逐码清 token 并整页跳 SSO，无 `/login` | Vitest/E2E Mock |
| TC-M1-AUTH-006 | P0 | REQ-AUTH-004 / AC-AUTH-004 | 20008 保留 token 且不跳转 | Vitest |
| TC-M1-AUTH-007 | P0 | REQ-AUTH-004 / AC-AUTH-004 | 注入业务码 30001：不清 token、不跳 SSO | Vitest/E2E Mock |
| TC-M1-AUTH-008 | P0 | REQ-AUTH-004 / AC-AUTH-008 | 退出清 token 和敏感临时态，跳 SSO，network 无 logout | E2E |
| TC-M1-AUTH-009 | P0 | REQ-AUTH-003 / AC-AUTH-005 | 同 uid 首访 gift/redbook 分别惰性建两条 tenant_users | IT/mysql 只读 |
| TC-M1-AUTH-010 | P0 | REQ-AUTH-003 / AC-AUTH-006 / EX-009 | disabled 成员返回 10003，不新建、不恢复、不跳 SSO | 单元/IT/mysql |
| TC-M1-AUTH-011 | P1 | REQ-AUTH-003 / AC-AUTH-007 | eyesUser ADMIN 仅平台管理员标记，租户角色仍取本地关系 | IT |
| TC-M1-AUTH-012 | P1 | REQ-CON-001 / AC-CON-001 | SSO 前草稿只在原租户 Host 恢复，跨租户不恢复 | E2E |
| TC-M1-AUTH-013 | P0 | REQ-AUTH-001 / EX-008 | eyesUser 不可用返回 HTTP 200+50002，不建立成员 | IT/Mock |

### 5.3 已发布配置与 Agent 运行约束

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-CFG-001 | P0 | REQ-CFG-001 / AC-CFG-001 | gift 标题/Logo/欢迎语/占位符/页脚来自 gift 发布配置 | curl/E2E |
| TC-M1-CFG-002 | P0 | REQ-CFG-001 / AC-CFG-001 | redbook 同字段来自 redbook 发布配置且与 gift 可辨 | curl/E2E |
| TC-M1-CFG-003 | P1 | REQ-CFG-001 / EX-006 | 配置缓存命中仍按租户隔离；错误结果不污染另一租户 | 性能/隔离 |
| TC-M1-AGT-001 | P0 | REQ-AGT-004 / AC-AGT-001 | 列表只含已发布启用 Agent，默认优先、排序稳定 | IT/E2E |
| TC-M1-AGT-002 | P0 | REQ-AGT-004 / AC-AGT-001 | 公开 Agent 响应不含 systemPrompt/model/provider/凭据 | IT/curl |
| TC-M1-AGT-003 | P0 | REQ-AGT-004 / EX-010 | 无可用 Agent 返回/呈现 30030 语义，输入与新建禁用并说明原因 | Service/E2E Mock |
| TC-M1-AGT-004 | P0 | REQ-AGT-004 / EX-011 | 停用 Agent 不能新建/继续，历史会话只读，30031/30040 | Service IT/E2E Mock |
| TC-M1-AGT-005 | P0 | REQ-CON-001 / AC-CON-002 | 会话创建时固定 agentVersion；后续发布不静默切换 | IT/mysql |

### 5.4 会话

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-CON-001 | P0 | REQ-CON-001 / AC-CON-002 | `conversationId=new` 原子创建会话并保存首条用户消息 | SSE IT/mysql |
| TC-M1-CON-002 | P0 | REQ-CON-001 / AC-CON-002 / EX-013 | 相同 Idempotency-Key 返回原结果，不重复建会话/消息 | IT/mysql |
| TC-M1-CON-003 | P1 | REQ-CON-002 / AC-CON-003 | 列表仅当前 tenant+uid+未删除，按 updatedAt/id 倒序 | IT/curl |
| TC-M1-CON-004 | P1 | REQ-CON-002 / AC-CON-003 | 分页无重复遗漏，pageSize 上限 100，越界 10001 | IT |
| TC-M1-CON-005 | P1 | REQ-CON-002 / AC-CON-004 | 详情/打开返回正确 Agent 版本与 string ID | IT |
| TC-M1-CON-006 | P1 | REQ-CON-002 / AC-CON-004 / EX-023 | 重命名置 titleSource=manual；旧 expectedVersion 返回 30020 | IT |
| TC-M1-CON-007 | P1 | REQ-CHAT-004 / AC-CON-004 | 手动改名后后续自动标题不得覆盖 | IT/mysql |
| TC-M1-CON-008 | P1 | REQ-CON-002 / AC-CON-004 | 删除为软删，立即从列表消失，再访问 10004 | IT/mysql |
| TC-M1-CON-009 | P0 | REQ-CON-002 / EX-022 | 生成中删除先取消生成，后续分片丢弃，删除 code=0 | IT |
| TC-M1-CON-010 | P0 | REQ-TEN-003 / EX-004 | 跨用户与非法 ID 同样返回 10004，不产生枚举差异 | IT |

### 5.5 流式对话

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-CHAT-001 | P0 | REQ-CHAT-001 / AC-CHAT-001 | SSE 首事件 meta，随后 delta*，末尾 done；字段与类型符合 §5 | IT/curl |
| TC-M1-CHAT-002 | P0 | REQ-CHAT-001 / AC-CHAT-001 | meta 在首个模型分片前 flush，首帧与首字分别计时 | curl/性能 |
| TC-M1-CHAT-003 | P0 | REQ-CHAT-001 / AC-CHAT-001 | delta 拼接内容与持久化 assistant content 一致 | IT/mysql |
| TC-M1-CHAT-004 | P0 | REQ-CHAT-002 / AC-CHAT-002 | 停止后前端 ≤1s 不再追加，后端状态 stopped 且保留已生成内容 | IT/E2E |
| TC-M1-CHAT-005 | P0 | REQ-CHAT-002 / AC-CHAT-003 | 重新生成 attemptNo+1、旧尝试保留、默认只显示 current | IT/mysql |
| TC-M1-CHAT-006 | P0 | REQ-CHAT-002 / AC-CHAT-003 / EX-013 | 重试/重新生成不重复用户消息 | IT/mysql |
| TC-M1-CHAT-007 | P0 | REQ-CHAT-001 / AC-CHAT-004 / EX-020 | 1 与 20000 Unicode 字符通过；空白、0、20001 返回 HTTP 200+30041 | 单元/IT/curl |
| TC-M1-CHAT-008 | P0 | REQ-CHAT-001 / EX-014 | 首字超时/上游不可用返回 50002 或流内 error+done | IT |
| TC-M1-CHAT-009 | P0 | REQ-CHAT-001/002 / EX-015 | 流中断保留部分内容并标 failed/stopped，可恢复 | IT |
| TC-M1-CHAT-010 | P0 | REQ-CHAT-001 / AC-CHAT-006 | 一个租户/Agent 模型故障不影响另一租户 | IT/隔离 |
| TC-M1-CHAT-011 | P1 | REQ-CHAT-004 / AC-CHAT-001 / EX-014 | 标题失败按确定性规则回退；手动标题不覆盖 | 单元/IT |
| TC-M1-CHAT-012 | P0 | REQ-CON-001/CHAT-002 / EX-013 | 缺 Idempotency-Key 在建流前 JSON 返回 10001 | IT/curl |

### 5.6 安全与响应契约

| TC-ID | P | 需求/验收/异常 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-SEC-001 | P0 | REQ-CHAT-001 / AC-CHAT-005 / AC-NFR-002 | Markdown `<script>` 被移除/转义且不执行 | Vitest/E2E |
| TC-M1-SEC-002 | P0 | 同上 | `onerror/onclick` 等事件属性被移除且不执行 | Vitest/E2E |
| TC-M1-SEC-003 | P0 | 同上 | `javascript:`、危险 `data:` URL 被拒绝；安全 HTTPS/站内链接保留安全 rel | Vitest/E2E |
| TC-M1-SEC-004 | P0 | REQ-CHAT-001 / AC-CHAT-005 | 代码块只展示/复制，内容不会执行 | Vitest/E2E |
| TC-M1-SEC-005 | P0 | AC-NFR-002 / EX-025 | 应用日志、console、network URL 不含 token/消息正文/堆栈/密钥 | 静态/运行日志 |
| TC-M1-API-001 | P0 | AC-API-001 / AC-NFR-004 | 全部 `/api/v1/**` 成功/业务失败 HTTP 200、数字 code、timestamp | IT/curl |
| TC-M1-API-002 | P0 | AC-API-001 | code=0 唯一成功；无 10002/40001；业务码不占 20000 段 | 单元/扫描 |
| TC-M1-API-003 | P0 | AC-API-001 | 运行中出现的错误码全部已登记于 api-spec §2.2 | 自动扫描/结果审查 |
| TC-M1-API-004 | P1 | AC-API-002 | 分页恒 `{list,total,page,pageSize}`，字段 camelCase | IT/curl |
| TC-M1-API-005 | P0 | AC-API-002 | id/uid 全为 string，时间为 ISO-8601 UTC | IT/curl |
| TC-M1-API-006 | P0 | AC-NFR-004 | 唯一允许非 200 的业务相关端点仅 `GET /site/status` | 路由扫描/curl |
| TC-M1-API-007 | P0 | AC-AUTH-008 | 不存在 `/api/v1/auth/**`、logout、refresh 与前端 `/login` | 路由/源码扫描 |

### 5.7 交互、响应式与可访问性

| TC-ID | P | 需求/验收 | 场景与关键断言 | 层次 |
|---|:---:|---|---|---|
| TC-M1-UI-001 | P1 | UI-007 / AC-CHAT-004 | Enter 发送；Shift+Enter 换行 | Vitest/E2E |
| TC-M1-UI-002 | P0 | UI-007 / AC-CHAT-004 | composition/IME 组词 Enter 不误发（含 isComposing/keyCode 229） | Vitest/E2E |
| TC-M1-UI-003 | P1 | UI-007/008 | 空白内容禁用发送；生成中同一操作位切为停止 | E2E |
| TC-M1-UI-004 | P1 | AC-NFR-003 / DS §9 | 375/768/1024/1440 无页面横向滚动、遮挡，关键操作可达 | MCP/E2E |
| TC-M1-UI-005 | P1 | DS §4/§5 | Light/Dark 信息同构，正文/次级文字实测对比度 AA | MCP/E2E |
| TC-M1-UI-006 | P1 | AC-NFR-003 / DS §11 | Tab 焦点顺序与视觉顺序一致，焦点环可见 | MCP/E2E |
| TC-M1-UI-007 | P1 | DS §6/§11 | icon-only 按钮有 aria-label；输入有 label；Logo alt 合理 | snapshot/E2E |
| TC-M1-UI-008 | P1 | DS §8/§11 | `prefers-reduced-motion` 下无位移/缩放/循环微光，≤100ms 淡化 | E2E |
| TC-M1-UI-009 | P1 | DS §6.6/§8 | 流式 DOM 更新不抢焦点，live region polite 且节流 | E2E |
| TC-M1-UI-010 | P1 | DS §8 | 高频动效可中断，无 `transition: all`/布局属性动画 | 源码/MCP |
| TC-M1-UI-011 | P1 | DS §11 | 200% 缩放内容不截断，关键操作仍可达 | MCP |
| TC-M1-UI-012 | P1 | AC-NFR-003 | 404/403/503 状态页语义正确，模板一致且不泄露租户数据 | E2E |
| TC-M1-UI-013 | P0 | AC-NFR-002 | console 无 error；network 无不存在接口、logout、认证中转请求 | MCP/E2E |

### 5.8 性能

| TC-ID | P | 验收 | 方法与红线 |
|---|:---:|---|---|
| TC-M1-PERF-001 | P1 | AC-NFR-001 | curl 多次采样租户识别+配置读取，缓存命中 P95 ≤20ms |
| TC-M1-PERF-002 | P0 | AC-NFR-001 / AC-CHAT-001 | SSE 点击/请求至首个 delta P95 ≤5s；同时记录 meta 首帧 |
| TC-M1-PERF-003 | P1 | AC-NFR-001 | 非 AI 查询（Agent/会话列表）P95 ≤500ms、P99 ≤1s |
| TC-M1-PERF-004 | P0 | AC-CHAT-002 | 停止操作至前端停止追加 ≤1s |

## 6. 后续里程碑保留用例（本轮不计 M1）

| TC-ID | 里程碑 | REQ/AC/EX | 场景 |
|---|:---:|---|---|
| TC-M2-CFG-001 | M2 | REQ-CFG-002 / AC-CFG-002 | 草稿不影响线上，独立校验可用 |
| TC-M2-CFG-002 | M2 | REQ-CFG-002 / AC-CFG-002 / EX-005 | 发布成功切版本；校验失败 30021 且保留线上版本 |
| TC-M2-CFG-003 | M2 | REQ-CFG-002 / AC-CFG-002 | 回滚生成新版本，历史行不修改 |
| TC-M2-CFG-004 | M2 | REQ-CFG-002 / AC-ADM-001 / EX-012 | 乐观锁冲突 30020，不静默覆盖 |
| TC-M2-AGT-001 | M2 | REQ-AGT-001 / AC-AGT-002 | 增删改查、复制、排序与租户内唯一 key |
| TC-M2-AGT-002 | M2 | REQ-AGT-002 / AC-AGT-003 | 发布版本不可变，版本递增 |
| TC-M2-AGT-003 | M2 | REQ-AGT-003 / AC-AGT-002 / EX-010 | 最多一个默认 Agent，切换/停用规则 |
| TC-M2-AGT-004 | M2 | REQ-AGT-002 / AC-AGT-004 / EX-012 | 跨租户能力绑定失败、并发冲突 30020 |
| TC-M2-SKL-001 | M2 | REQ-SKL-001 / AC-SKL-001 / EX-005 | 变量校验、版本不可变 |
| TC-M2-MCP-001 | M2 | REQ-MCP-001 / AC-MCP-001 / EX-016/025 | 凭据不回显、连接失败分类 |
| TC-M2-MCP-002 | M2 | REQ-MCP-002 / AC-MCP-002 / EX-017 | 新发现工具默认禁用 |
| TC-M2-TOL-001 | M2 | REQ-TOL-001 / AC-TOL-001 / EX-018/019 | 租户不能上传代码，只能授权平台 Tool |
| TC-M2-RBAC-001 | M2 | REQ-RBAC-001 / AC-RBAC-001/002 / EX-009/024 | 逐角色后端准入与角色来源 |
| TC-M2-AUD-001 | M2 | REQ-AUD-001 / AC-AUD-001/002 / EX-024/025 | 审计完整、不可改、敏感值摘要 |
| TC-M3-LMT-001 | M3 | REQ-LMT-001 / AC-LMT-001 / EX-021 | 30/min、120/hour；10005+retryAfterSeconds；租户计数隔离 |
| TC-M3-SKL-001 | M3 | REQ-SKL-002 / AC-SKL-002 | 仅注入绑定版本，停用不越权 |
| TC-M3-MCP-001 | M3 | REQ-MCP-003 / AC-MCP-003~006 / EX-016/017/029/030 | Mock MCP 两传输、SSRF、故障、超大结果 |
| TC-M3-TOL-001 | M3 | REQ-TOL-002 / AC-TOL-002/003 / EX-018/019 | 高风险确认、非幂等不自动重试 |
| TC-M3-CHAT-001 | M3 | REQ-CHAT-003 / AC-CHAT-007 | Skill/MCP/Tool 运行时二次鉴权与摘要 |
| TC-M3-OBS-001 | M3 | REQ-OBS-001 / AC-OBS-001 / EX-025 | 埋点含租户且无正文/凭据/token |

## 7. 缺陷与回归流程

1. 失败先最小化复现并判定前端/接口/数据/鉴权层。
2. 缺陷按框架 §三使用 `❌`，必须含步骤、期望/实际、日志/截图、REQ/AC/EX、责任方建议。
3. 测试代码或明显小 Bug 可直接修复；不得修改 `api-spec.md`、`architecture.md`、`prd.md` 或 `tokens.css` 变量名。
4. 同一问题最多 3 轮；每轮修复后重跑相关测试，涉及业务代码时执行后端 `mvn verify` 或前端 `npm run typecheck && npx vitest run`。
5. 第 3 轮仍失败则标记“需升级 Boss 裁决”。

## 8. 签署门槛

仅当 P0=100%、P1≥95%、M1 REQ/AC/关联 EX 覆盖 100%、跨租户覆盖与通过均 100%、SSO 三层完成、显式 30001 不误判通过、四档与可访问性通过、无未关闭 P0/P1，且数据库核验全程只读时，@测试工程师才签署 ✅。

## 9. M3 终验范围与总体策略（含 M2-min）

### 9.1 交付边界

M3 直接承接一期交付，M2 管理后台全量为 **Deferred（二期）**，不是 Cancelled。M3 签署同时验收以下 M2-min 后端安全前置，不为一期规划任何 `/admin/*`、`/platform/*` **页面**用例：

| 主题 | 一期终验范围 |
|---|---|
| Skill | 不可变版本、Agent 版本精确绑定、变量与内置变量保护、运行时只消费绑定版本，不恢复已停用外部工具权限 |
| MCP | DB 配置、AES-256-GCM 凭据、连接测试分类、工具发现/默认禁用/逐项授权、`streamable_http` 与 `sse` JSON-RPC、保存时与每次调用前 SSRF 双点位 |
| 本地 Tool | 平台预注册、租户逐项授权、风险等级、JSON Schema、执行超时、结果上限；一期内置仅 `datetime_now`、`calculator` |
| 工具编排 | 多轮调用、`tool.max_rounds`、结果大小截断、非幂等结果未知阻断、结果回灌、`error` 后 `done`、流结束终态收敛 |
| 高风险确认 | `allow/deny`、等待超时、幂等回放、相反决定冲突、confirm/stop 竞态、SSE 断开收敛 |
| 可观测性 | 埋点白名单/稳定采样/去重/fail-closed、用量聚合、限流、运行时安全审计与禁记清单 |
| M2-min 治理 | 独立配置校验、运行时 `30060` 兜底、缓存失效、凭据离线加密、M3 接口 `@TenantRole` 程序化兜底 |

### 9.2 一期无管理 UI 的验证方式变化

一期配置维护不是 UI 验收，原“在后台点击保存/授权/发布”的步骤统一改写为：

1. **DBA/后端通过受控 SQL 写入配置或授权数据**；测试工程师只提出数据需求并记录变更单，未经批准不执行写入。
2. 调用 `POST /api/v1/admin/config/validate` 验证对象及两层引用链；失败按 HTTP 200 + `code=30060` 断言。
3. 由平台管理员调用 `POST /api/v1/platform/cache/evict`；部分失败断言 `code=30061` 与 `data.incompleteScopes[]`。
4. 调用连接测试、发现、用量等管理类接口及终端对话接口实测运行时行为。
5. 通过 MySQL **只读 SELECT** 核验配置、授权、`tool_calls`、`audit_logs`、`analytics_events` 和聚合源数据。

该路径完整覆盖“配置事实 → 独立校验 → 缓存一致性 → 运行时消费 → 数据留痕”，是 Boss 决策下的一期正式验收路径，**不算降级验收**。不存在管理页面、菜单或点击入口不得判缺陷；管理类**接口**仍必须验收。

### 9.3 分层策略与资产复用

| 层次 | 工具/资产 | M3 重点 | 执行纪律 |
|---|---|---|---|
| 后端单元 | JUnit 5 + Mockito + AssertJ；`*Test.java` | SSRF/CIDR、AES-GCM、Skill 变量、函数名归一化、参数校验、截断、风险策略、限流、启动检查 | 快速隔离；禁止依赖真实 MCP/SSO |
| MockMvc 集成 | Spring Boot Test；`*IT.java` | HTTP 200/body.code、confirm 矩阵、编排状态机、审计事务、埋点/用量、RBAC、配置校验 | 夹具/事务准备数据；业务语义不使用 HTTP 4xx |
| MCP 场景 | `MockMcpServer` + `McpClientMockIT`/`McpGovernanceIT`/编排 IT | 两种传输、JSON-RPC、连接分类、故障、超时、超大结果、SSRF | 不使用真实外部凭据；`SysConfigOverride` 必须 `try-finally` 还原 |
| 前端单元 | Vitest；现有 16 个 spec（237 条） | SSE 12 字段、8 状态、confirm store/card、限流、埋点、错误映射、硬编码守护 | 未知字段/事件兼容；状态原位更新 |
| E2E | `@playwright/test`；MCP 仅用于探索取证 | 真实浏览器编排、确认、停止、可访问性、四档响应式、SSO 三层 | 需真实 SSO 登录态；使用 `storageState`；动态断言先等待 |
| SSO 边界 | 沿用 M1 `MockEyesAuthServer` Thrift 替身 | 业务鉴权、续期、惰性建户与 M3 受保护接口 | 替身不证明真实 eyesUser 互通/验签；上线前仍需内网复验 |
| 数据核验 | mysql MCP 只读 SELECT | 租户字段、授权即时生效、审计、埋点、用量、不可变版本 | 严禁 INSERT/UPDATE/DELETE/DDL；造数/清理由后端夹具或批准的 DBA 变更完成 |
| 静态守护 | 事务/租户扫描、路由/源码扫描 | `@TenantRole` 兜底、审计无 update/delete、无自造 action/key、禁 `@Transactional` 热路径回退 | 只读代码审查可先做，执行留待终验窗口 |

### 9.4 测试数据与执行前置

| 数据/环境 | 最小要求 |
|---|---|
| 租户 | `gift`、`redbook` 各有独立成员、Agent 版本、Skill、MCP、本地 Tool 授权和会话；同名/同 key 数据用于隔离反证 |
| 身份 | END_USER、TENANT_OPERATOR、TENANT_ADMIN、平台 ADMIN 各一；另有跨租户用户、无成员用户、disabled 用户 |
| 工具 | low/medium/high 各一；幂等/非幂等各一；被停用、未绑定、未授权、授权后撤销各一 |
| MCP | 内置 Mock 同时支持 `streamable_http` 与契约允许的同步响应型 `sse`；可切 DNS/TLS/auth/connect/timeout/protocol/no-tools/SSRF/大结果模式 |
| 配置 | 25 个 REQUIRED_CONFIG 键齐备；可在隔离进程中临时缺失/破坏 analytics 两键、越界采样率、缩小预算和轮次/结果阈值 |
| 登录态 | 一次人工完成耶瞳 SSO，保存到 `frontend/tests/e2e/.auth/state.json`（gitignore）；过期后重做 |
| 证据 | 原始 SSE、MockMcpServer 请求日志、MockMvc 响应、console/network、截图/trace、只读 SQL 结果、查询次数统计 |

### 9.5 用例字段与状态口径

- 下列用例每条均引用 REQ/AC 和 `api-spec.md` §8.3 条目；`P` 为签署优先级。
- 除 `/site/status` 外，业务接口成功/失败均断言 HTTP 200；成功 `code=0`，业务结果看数字 `code`。
- 工具状态以 SSE 契约 snake_case 字面量为准：`pending/awaiting_confirmation/running/succeeded/failed/timed_out/cancelled/denied`。
- H 组是契约授权的签署核对记录：允许 `⏸`，不得人为制造不可达路径；H1 仅执行“建流前 `10005`”，不执行 SSE 内 `10005`。
- 每条执行用例独立造数/回滚；涉及人工改库的步骤由 DBA/后端在批准窗口执行，@测试仅调用接口和只读核验。

## 10. M3 核心签署用例：§8.3 全 58 项映射

> 本节 **58 条 TC 与 §8.3 A1～H6 一一对应**。H 组中的 `TC-M3-DEF-*` 是签署治理记录，不是“必须触发”的运行时用例。

### 10.1 A 组：SSE 与工具事件（6 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-SSE-001 | P0 | Mock 模型首轮请求 low Tool | 发消息并保存全部 SSE；按 schema 校验每个 `tool` 帧 | `meta` 首发；事件名仅 `meta/delta/tool/error/done`；`tool` 恰含并正确解析 `toolCallId/toolType/toolKey/riskLevel/status/round/summary/argsSummary/resultSummary/truncated/errorCode/retryAfterSeconds` 12 字段，`summary` 非删除 | REQ-CHAT-003 / AC-CHAT-007 | A1 | ToolOrchestrationIT + Vitest + 原始 SSE |
| TC-M3-SSE-002 | P0 | low Tool、high Tool；心跳阈值缩短的隔离配置 | 分别执行自动工具与等待确认工具，逐帧记录状态；等待超过一次心跳周期 | low：`pending→running→succeeded/failed/timed_out`；high：`pending→awaiting_confirmation→running→终态` 或 `denied/timed_out`，每次迁移均有独立帧且不跳帧；等待期持续收到 `: ping` | REQ-TOL-002、REQ-CHAT-003 / AC-TOL-002、AC-CHAT-007 | A2 | ToolOrchestrationIT + E2E |
| TC-M3-SSE-003 | P0 | 无工具、工具清单但首轮无调用、首轮即工具调用三组稳定夹具 | 每组重复采样；从请求到首个可见帧计时 | 首字锚点为首个 `delta` 或 `tool` 取先到者，`meta/: ping` 不计；三组 P95 均 ≤5s | REQ-CHAT-003 / AC-NFR-001、AC-CHAT-007 | A3 | 性能接口实测 + 原始 SSE |
| TC-M3-SSE-004 | P0 | 构造运行时非法绑定使终端路径触发 `30060` | 发起 SSE 并解析 `error` 帧 | `error` 仅有 `code/message/retryAfterSeconds`；`code=30060`、`retryAfterSeconds=null`；不得出现 `violations/objectType/objectId/rule/checkedObjects`，随后必有 `done(status=failed, finishReason=failed)` | REQ-CFG-003、REQ-CHAT-003 / AC-CFG-004、AC-CHAT-007 | A4 | SystemPromptBudgetIT/ToolOrchestrationIT + Vitest |
| TC-M3-SSE-005 | P0 | Mock 工具返回 `30052/30053/30057` 各一 | 触发工具失败并继续读取至流结束 | 对应工具帧为 `failed` 且 `errorCode` 分别为 `30052/30053/30057`；流不被异常抛断，`error` 后必发 `done`，除物理断线外 `done` 恒发 | REQ-MCP-003、REQ-CHAT-003 / AC-MCP-006、AC-CHAT-007 | A5 | ToolRuntimeIT + MockMcpServer |
| TC-M3-SSE-006 | P0 | `toolKey=crm:lookup`，内部函数名归一化为 `crm_lookup` | 完整执行并查询 SSE、`tool_calls`、查询接口、审计 | 四处只记录原始 `toolKey=crm:lookup`；任何对外载荷均无 `functionName`/`crm_lookup` 字段；成功状态 `succeeded`、`errorCode=null` | REQ-MCP-003、REQ-CHAT-003 / AC-MCP-003、AC-CHAT-007 | A6 | ToolFunctionNamesTest + IT + 数据核验 |

### 10.2 B 组：高风险确认与幂等（6 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-CONF-001 | P0 | 本人 high Tool 调用 3 份；另备跨用户/跨租户 ID | 分别首次 `allow`、首次 `deny`、不操作等待超时；再以非本人确认 | allow：接口 `code=0`，`awaiting_confirmation→running→succeeded/failed`；deny：`code=0`，`decision=deny`、终态 `denied`、`errorCode=30050`；超时：`timed_out+30050`；非本人/跨租户均 HTTP 200 + `10004`，且不泄露存在性 | REQ-TOL-002 / AC-TOL-002 | B1 | ToolConfirmIT + E2E + 数据核验 |
| TC-M3-CONF-002 | P0 | 已首次 allow/deny 并记录审计基线 | 对相同 `toolCallId` 重复提交相同 `decision` 三次 | 每次 `code=0`、`replayed=true`、`auditEventId=null`；`tool_calls` 不再变化；`audit_logs` 行数不增加 | REQ-TOL-002 / AC-TOL-002 | B2 | ToolConfirmIT + 数据核验 |
| TC-M3-CONF-003 | P0 | 已有首次决定 | 连续 3 次提交相反 `decision` | 三次均 HTTP 200 + `code=30055`；原 `decision/status` 不变；同一 `toolCallId` 恰好 1 条 `action=tool.confirm_conflict`、`result=denied`、`errorCode=30055` | REQ-TOL-002、REQ-AUD-001 / AC-TOL-002、AC-AUD-003 | B3 | ToolConfirmIT + 数据核验 |
| TC-M3-CONF-004 | P0 | 可注入 confirm 主事务最终抛 `30055` 的场景 | 触发冲突并在响应后立即只读查询审计；另注入审计失败 | 正常：先提交独立短事务审计再返回 `30055`，审计行数=1（不是 0）；审计失败返回 `50003`，已终态 `tool_calls` 不被修改 | REQ-AUD-001 / AC-AUD-001、AC-AUD-003 | B4 | ToolConfirmIT + TransactionDisciplineScanTest |
| TC-M3-CONF-005 | P0 | 同一 high Tool 可连续产生两次调用；前后端可读取 `tool.confirm_wait_seconds` | 第一次 allow 完成后再次调用；检查代码与配置加载 | 第二次仍进入 `awaiting_confirmation`，历史 allow 不复用；等待上限只取配置；前后端源码无硬编码 `120`；超时为 `timed_out+30050` | REQ-TOL-002 / AC-TOL-002 | B5 | ToolRiskPolicyTest + ToolConfirmIT + noHardcodeGuard.spec.ts |
| TC-M3-CONF-006 | P0 | Tool 正在 `awaiting_confirmation`；可并发发 confirm/stop，并可主动关闭 SSE | ① stop；② confirm+stop 同时提交，循环 ≥20 次；③ 客户端关闭 SSE | ① ≤1s 收敛 `cancelled`，`done(status=stopped, finishReason=stopped)`；② 无死锁/永久等待，终态由行锁唯一裁决且只出现一个终态；③ 服务端 finally 立即将残留 `pending/awaiting_confirmation/running` 收敛为 `cancelled`，无非终态残留；stop 不写 `tool.confirm_denied` | REQ-CHAT-002、REQ-TOL-002 / AC-CHAT-002、AC-TOL-002 | B6 | ToolConfirmIT/ToolOrchestrationIT + E2E + 数据核验 |

### 10.3 C 组：授权、SSRF 与执行期竞态（7 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-AUTHZ-001 | P0 | 未授权、未绑定、已停用、`toolPolicy=disabled` 四组工具 | 分别触发调用 | 均不执行实现体；`tool_calls.status=denied`、`errorCode=30050`；SSE `tool(denied)+error(30050)+done`；各写 `action=tool.grant_denied` | REQ-TOL-001/002、REQ-CHAT-003 / AC-TOL-001/002、AC-CHAT-007 | C1 | ToolRuntimeIT + 数据核验 |
| TC-M3-SSRF-001 | P0 | 可批准写入的合法 HTTPS、环回/私网/元数据/重定向 endpoint；Mock 支持连接分类 | ① 保存/校验非法地址；② 合法保存后由 DBA 改为内网并直接运行；③ 逐一触发连接测试分类 | 保存/校验非法配置 `30060` 且不发网络；运行时改库仍 `running→denied`、`30050`、审计 `mcp.ssrf_rejected`；3xx 不跟随；JVM `networkaddress.cache.ttl=10`；连接结果字面量覆盖 `success/dns_failed/tls_failed/auth_failed/connect_failed/timeout/protocol_incompatible/no_tools_available/ssrf_rejected`，健康状态仅 `healthy/unhealthy` | REQ-MCP-001/003、REQ-CFG-003 / AC-MCP-001/003/004/006、AC-CFG-004 | C2 | SsrfGuardTest + McpGovernanceIT + MockMcpServer |
| TC-M3-AUTHZ-002 | P0 | 已完成清单构造并停在确认等待；MCP、本地 Tool 各一 | DBA/夹具撤 `mcp_tools.granted`、撤本地授权，再 allow 执行；多轮下一轮重复 | 下一次执行前均执行 ≤1 次点查并拒绝；不得进入实现体；终态 `denied`、`errorCode=30050`，审计 `tool.grant_denied` | REQ-MCP-003、REQ-TOL-002 / AC-MCP-004、AC-TOL-002、AC-CHAT-007 | C3 | ToolOrchestrationIT/ToolRuntimeIT + 数据核验 |
| TC-M3-AUTHZ-003 | P0 | 可在 preflight 后、invoke 前制造撤权或内网地址竞态 | 卡点后变更授权/endpoint 并放行线程 | 状态逐帧包含 `running→denied`，最终 `errorCode=30050`；不得出现 `failed/30052`；用量只计 `toolDeniedCount+1`，不计 `toolFailedCount` | REQ-MCP-003、REQ-OBS-001 / AC-MCP-004、AC-OBS-001 | C4 | ToolOrchestrationIT + UsageMetricsIT |
| TC-M3-AUTHZ-004 | P1 | MCP 调用已进入 `invoke`，可观测调用开始时点 | invoke 后撤授权，等待本次结束，再发下一次调用 | 已进入 invoke 的本次允许在单次执行时长内 `succeeded/failed/timed_out`，**不判缺陷**；撤权后新发起的下一次必须 `denied+30050` | REQ-MCP-003 / AC-MCP-004 | C5 | 并发 IT + MockMcpServer |
| TC-M3-AUTHZ-005 | P1 | 已授权 MCP 工具；Mock 可变更 schema 或移除工具 | 执行 discover 触发 `schema_changed` 与 `removed` | 原授权降为 `granted=false/status=disabled`，历史行不物理删除；审计 action=`mcp.tool_grant_revoked`、reason=`schemaChanged/toolRemoved`，不得误记 `tool.grant_denied` | REQ-MCP-002 / AC-MCP-002、AC-AUD-003 | C6 | McpGovernanceIT + 数据核验 |
| TC-M3-BIND-001 | P0 | Agent 绑定 Skill、MCP Tool、本地 Tool；另有 grant 行重建/版本变化夹具 | 解析运行清单并分别变更版本/当前行/grant 行 | Skill 严格按 `ref_version` 取不可变版本；MCP/local 读取当前行，local `ref_version` 只作审计快照；grant 行被 delete+insert 导致引用悬挂时 fail-closed，运行时 `30060` 而非误绑 | REQ-SKL-001/002、REQ-MCP-003、REQ-TOL-001 / AC-SKL-001/002、AC-CHAT-007 | C7 | SkillInjectionIT + ToolRuntimeIT + 数据核验 |

### 10.4 D 组：租户隔离与鉴权（5 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-TEN-001 | P0 | gift/redbook 同名资源；当前 Host 固定为 gift | 在 body/query/header 伪造 `tenantId=redbook`，并访问 redbook 资源 ID | tenantId 伪造被忽略并记安全日志；仍使用 gift；跨租户资源统一 HTTP 200 + `10004`，响应不含 redbook 或资源存在性 | REQ-TEN-002/003 / AC-TEN-002/004/005 | D1 | 各 Controller IT + 日志审查 |
| TC-M3-TEN-002 | P0 | 两租户完整 M3 数据 | 双向读取/操作工具、埋点、用量、MCP test/discover、config validate | gift↔redbook 全部不可见；越权资源 `10004`；埋点/用量强制由 Host/上下文补 `tenantId`，请求体伪造值无效；只读 SQL 中所有落库行 tenant_id 正确 | REQ-TEN-003、REQ-OBS-001 / AC-TEN-004/005、AC-OBS-001 | D2 | IT + 接口实测 + 数据核验 |
| TC-M3-RBAC-001 | P0 | `eyes-auth.enabled=false` 隔离 profile；无身份、END_USER、角色不足身份 | 调用每个 `@TenantRole` 端点与平台缓存接口 | 无身份/角色不足均 HTTP 200 + `10003`，不得 `code=0`；合法 TENANT_OPERATOR/ADMIN 仅访问当前租户；平台接口仅平台 ADMIN；prod 若关闭 eyes-auth 则启动失败 | REQ-RBAC-001 / AC-RBAC-001/002/003 | D3 | M3EndpointEvidenceIT + 配置启动测试 |
| TC-M3-RBAC-002 | P0 | 可枚举全部 Controller 方法 | 反射扫描 `@TenantRole` 方法并与程序化 guard 证据集比较；加入测试专用漏兜底端点验证守护有效性 | 扫描集合 ⊆ 已兜底断言集合；测试专用漏兜底样本必须使守护失败，证明不是空断言；对外失败码为 `10003` | REQ-RBAC-001 / AC-RBAC-003 | D4 | 静态扫描 Test |
| TC-M3-TEN-003 | P0 | 异步工具/审计/埋点可注入快照与线程复用 | 正常执行并静态扫描异步段；再以缺失快照提交任务 | 业务判据使用不可变快照 uid/tenantId；异步段无 `UserInfoHolder`/Servlet 请求读取；缺快照立即拒绝并 ERROR，不产生无租户写入或跨租户数据 | REQ-TEN-003、REQ-AUD-001、REQ-OBS-001 / AC-TEN-005、AC-AUD-001、AC-OBS-001 | D5 | TenantIsolationScanTest + IT + 数据核验 |

### 10.5 E 组：埋点与用量（11 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-OBS-001 | P0 | 合法事件、额外未知字段、含 token/正文/credential 的事件各一 | 批量上报并只读核验 | 合法项计 `accepted`；未知字段静默过滤；任一禁止字段命中则整条计 `discarded`，不落库，安全日志不回显命中值；接口始终 HTTP 200 + `code=0` | REQ-OBS-001 / AC-OBS-001 | E1 | AnalyticsEventIT + 数据核验 |
| TC-M3-OBS-002 | P1 | 带 query/hash 的 pagePath；非法 errorCode；跨租户 conversationId/agentId | 上报并读取落库结果 | `pagePath` 仅保留 path；未登记 `errorCode` 置 null；跨租户关联 ID 置 null 且请求仍 `code=0`，不得返回 `10004` 或泄露目标存在性 | REQ-OBS-001、REQ-TEN-003 / AC-OBS-001、AC-TEN-005 | E2 | AnalyticsEventIT + 数据核验 |
| TC-M3-OBS-003 | P1 | 固定 `clientEventId`，采样率介于 0 与 1 | 首次、重复、同 ID 多次重放并重启服务后再上报 | 首次按稳定采样计 `accepted` 或 `discarded`；相同 ID 采样结论稳定；已接收 ID 重复计 `duplicated` 且不重复落库；响应三计数之和等于输入数 | REQ-OBS-001 / AC-OBS-001 | E3 | AnalyticsEventIT |
| TC-M3-OBS-004 | P1 | 匿名事件开关 true/false 两组 | 无 token 上报同一白名单事件 | 开关 true：落库 `uid=NULL`、`loginState=anonymous`；开关 false：全部 `discarded`、`accepted=0`；均 `code=0` | REQ-OBS-001 / AC-OBS-001 | E4 | AnalyticsEventIT + 数据核验 |
| TC-M3-OBS-005 | P0 | 独立启动进程；25 键基线可由夹具临时移除 | 分别缺失 `observability.analytics_enabled`、`analytics_sample_rate` 启动 | 两种场景均**拒绝启动**，不得使用代码默认值继续；两键均在 REQUIRED_CONFIG，完整基线可启动 | REQ-CFG-003、REQ-OBS-001 / AC-CFG-003、AC-OBS-001 | E5 | StartupCheckerRequiredConfigTest + 隔离启动测试 |
| TC-M3-OBS-006 | P0 | 运行中可临时置空/破坏 analytics 两键并在 finally 还原 | 各破坏一键后批量上报 N 条 | HTTP 200 + `code=0`；`accepted=0`、`discarded=N`，ERROR 日志存在；不得 fail-open 全量接收，数据库无新增事件 | REQ-OBS-001 / AC-OBS-001 | E6 | AnalyticsEventIT + SysConfigOverride + 数据核验 |
| TC-M3-OBS-007 | P0 | 独立启动进程 | 分别把 sample_rate 设为 `1.5`、`-0.2`、不可解析值后启动 | 全部**拒绝启动**，不是 WARN 后继续；边界 `0.0/1.0` 可启动 | REQ-CFG-003、REQ-OBS-001 / AC-CFG-003、AC-OBS-001 | E7 | StartupCheckerRequiredConfigTest |
| TC-M3-OBS-008 | P1 | 可注入 DB 批量写失败/服务异常 | 上报合法事件 | 埋点异常不传播到主流程；接口 HTTP 200 + `code=0`，失败项计 `discarded`；对话/SSE 不受影响 | REQ-OBS-001 / AC-OBS-001 | E8 | AnalyticsEventIT |
| TC-M3-USAGE-001 | P0 | 构造发生过 `10005` 的消息限流数据但不自造埋点事件 | 查询 day/hour 用量 | `rateLimitedCount` **恒为 0**；任何非 0 即缺陷；代码/库中无 `messageRateLimited` 自造事件和自造 sys_config 键 | REQ-LMT-001、REQ-OBS-001 / AC-LMT-001、AC-OBS-001 | E9 | UsageMetricsIT + 静态扫描 |
| TC-M3-USAGE-002 | P0 | 覆盖工具各终态与非终态、消息/会话/token 夹具 | 按 day/hour 查询并用只读 SQL 独立重算 | 各字段与 §7.11.1 数据源一致；`denied + failed + succeeded + cancelled + 非终态 = toolCallCount`；执行期 `denied+30050` 只进 toolDeniedCount | REQ-OBS-001 / AC-OBS-001 | E10 | UsageMetricsIT + 数据核验 |
| TC-M3-USAGE-003 | P0 | 两租户、多个 uid、含正文数据；31/32 天区间 | 查询 day/hour 聚合并检查响应 | 仅当前租户聚合计数；无正文、uid 列表、其他租户数据；day/hour 均 `code=0`；跨度 31 天通过，>31 天 HTTP 200 + `10001` | REQ-OBS-001、REQ-TEN-003 / AC-OBS-001、AC-TEN-005 | E11 | UsageMetricsIT + 接口实测 |

### 10.6 F 组：审计与事务边界（6 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-AUD-001 | P0 | 缓存失效/连接测试/发现等非流式操作可注入审计失败 | 执行业务后让审计写失败；扫描 Repository/实体 | 返回 HTTP 200 + `50003`，业务写入与审计同事务全部回滚；`AuditLogRepository` 不暴露 update/delete，实体字段 `updatable=false`，无修改/删除接口 | REQ-AUD-001、REQ-CFG-004 / AC-AUD-001/003、AC-CFG-005 | F1 | AuditWriterIT + TransactionDisciplineScanTest + 静态审查 |
| TC-M3-AUD-002 | P0 | 流式安全事件可注入审计失败 | 触发工具拒绝/确认并使独立短事务审计失败 | 该次工具调用落 `failed`，`errorCode=50003`；SSE 仍发送 `tool(failed)→error(50003)→done(failed)`，连接不被审计异常中断 | REQ-AUD-001、REQ-CHAT-003 / AC-AUD-001、AC-CHAT-007 | F2 | ToolOrchestrationIT + AuditWriterIT |
| TC-M3-AUD-003 | P0 | 可触发全部安全事件 | 执行后聚合 distinct action，并扫描常量/调用点 | action 集合与 `api-spec.md` §7.14、`architecture.md` §11.1.1 的登记表**逐行完全一致**；不得出现自造 action，且必须包含 `tool.confirm_conflict`、`mcp.tool_grant_revoked`；若文档计数文字与登记表行集合不一致，以登记表集合为断言并立即报 @架构师 文档缺陷 | REQ-AUD-001 / AC-AUD-003 | F3 | AuditContractTest + 数据核验 |
| TC-M3-AUD-004 | P0 | 在输入/消息/工具结果中植入六类敏感标记 | 触发审计并扫描所有审计文本列 | 不含凭据/密文/IV/Tag/Token、消息或 Skill/system 正文、完整工具入参结果、完整个人信息、endpoint/内网/堆栈/SQL、其他租户标识与存在性；违规任一即安全缺陷 | REQ-AUD-001 / AC-AUD-002 | F4 | AuditSanitizerTest + 数据核验 |
| TC-M3-AUD-005 | P0 | 触发带 `auditEventId` 的确认/连接测试/缓存失效 | 校验响应并按 ID 查询审计 | `auditEventId` 匹配 `^[0-9a-f]{32}$`，未截断；与 `audit_logs.event_id` 完全一致；回放场景例外为 null | REQ-AUD-001 / AC-AUD-001 | F5 | ToolConfirmIT/McpGovernanceIT/PlatformCacheEvictIT + 数据核验 |
| TC-M3-AUD-006 | P0 | low 风险纯函数 `datetime_now/calculator` 成功执行 | 记录前后审计行数并查询 tool_calls | 工具 `succeeded`、`errorCode=null`；`tool_calls` 有可追溯行，但 `audit_logs` **不新增**工具成功审计；不得自造 `tool.executed` | REQ-TOL-002、REQ-AUD-001 / AC-TOL-002、AC-AUD-003 | F6 | ToolRuntimeIT + 数据核验 |

### 10.7 G 组：配置治理与运行时兜底（11 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-CFG-001 | P0 | 已批准 DBA 配置变更；平台 ADMIN；可注入某一 L2 失效失败 | 按“改库→config validate→cache evict→运行接口→只读核验”执行；再制造部分失败 | 正常各接口 `code=0`，后续不读旧值；L1+L2 同清，tenant scope 的 Host/tenantId 键成对失效；部分失败 HTTP 200 + `30061`，`incompleteScopes[]` 精确列未完成项且不得伪报成功 | REQ-CFG-003/004 / AC-CFG-003/005 | G-1 | ConfigValidateIT + PlatformCacheEvictIT + 数据核验 |
| TC-M3-CFG-002 | P0 | 已存在 idem/cancel/confirm/limit 运行时键 | 调用 `scope=all` 缓存失效并继续原业务流程 | `chat:idem`、`chat:cancel`、`tool:confirm`、`limit:msg` 均未删除；幂等、停止、确认、限流语义继续成立；接口 `code=0` 或仅对真正缓存失败返回 `30061` | REQ-CFG-004 / AC-CFG-005 | G-2 | PlatformCacheEvictIT + Redis 键只读核验 |
| TC-M3-CFG-003 | P0 | Agent 绑定三类资源；二层非法、循环引用、includeReferences true/false 夹具 | 调用 config validate | true：固定 2 层递归，二层非法返回 `30060` 且 `violations[]` 指向二层；环返回 `30060`、`rule=circularReference`；三类全查后 warnings 无 `referencesNotFullyChecked`；false 时该 warning **仍在** | REQ-CFG-003 / AC-CFG-003 | G-3 | AgentVersionRecursiveValidateIT |
| TC-M3-CFG-004 | P1 | 开启 SQL 计数器；多绑定数据量梯增 | 分别调用校验入口、构造运行清单、执行单次工具 | 校验入口 ≤4 次查询；运行时清单 ≤5 次批量查询；每次执行前授权点查 ≤1 次；数据量增加不形成 N+1 | REQ-CFG-003、REQ-CHAT-003 / AC-CFG-003、AC-CHAT-007、AC-NFR-001 | G-4 | AgentVersionRecursiveValidateIT/ToolRuntimeIT + 查询计数 |
| TC-M3-CFG-005 | P0 | 同一非法配置可由管理接口与终端运行触发 | 先调 config validate，再发终端消息 | 管理端 HTTP 200 + `30060` 且完整 `violations[]/warnings[]/checkedObjects`；终端 SSE 仅 `code=30060+通用 message`，无明细；同次 ERROR 日志含 `requestId+tenantId+agentVersion+rule+objectType:objectId` 且已脱敏 | REQ-CFG-003 / AC-CFG-003/004 | G-5 | ConfigValidateIT + ToolOrchestrationIT + 日志审查 |
| TC-M3-CFG-006 | P0 | 多 Skill 变量替换后总长度可跨预算边界 | 测预算-1、等于预算、预算+1 | 前两组允许进入模型；预算+1 在进模型前 HTTP/SSE `30060`、管理校验 `rule=systemPromptBudgetExceeded`；拼装结果**不截断**、不静默丢 Skill/constraint | REQ-CFG-003、REQ-SKL-002 / AC-CFG-003/004、AC-SKL-002 | G-6 | SystemPromptBudgetTest/IT |
| TC-M3-CFG-007 | P0 | `crm:lookup`、>64 字符 key、两个归一化碰撞 key | 构造工具清单并尝试运行 | `crm:lookup→crm_lookup` 内部映射成功；>64 返回 `30060 rule=functionNameTooLong`；碰撞返回 `30060 rule=functionNameCollision`；拒绝截断/hash 后缀/字符串逆推 | REQ-MCP-003、REQ-CFG-003 / AC-MCP-003、AC-CFG-004 | G-7 | ToolFunctionNamesTest + ToolRuntimeIT |
| TC-M3-ORCH-001 | P0 | 可调小 `tool.max_rounds` 与 `tool.result_max_bytes`；Mock 连续请求工具/返回大结果 | 触发超轮次和超大结果，并观察下一轮模型输入 | 超轮次不再发新 tool 帧，`error(30054)→done(status=failed,finishReason=failed)`；大结果工具 `succeeded`、`truncated=true`，只将脱敏截断摘要以 `role=tool` 回灌，后续可继续 `delta` | REQ-CHAT-003、REQ-MCP-003 / AC-CHAT-007、AC-MCP-006 | G-8 | ToolOrchestrationIT + MockMcpServer |
| TC-M3-CFG-008 | P0 | Agent/Skill/MCP/Tool 各一种非法配置 | 分别调用终端运行路径 | 均在进模型/执行工具前以已登记数字码 `30060` 失败；不得 NPE、字符串错误码、未分类 500；SSE 仍收敛 `done(failed)` | REQ-CFG-003、REQ-CHAT-003 / AC-CFG-004、AC-CHAT-007 | G-9 | ToolRuntimeIT/SkillInjectionIT + 契约扫描 |
| TC-M3-LOCAL-001 | P0 | local_tools 含两个内置项和一个清单外项 | 执行 datetime/calculator 正常与专项输入；再调用清单外项 | 仅 `datetime_now/calculator` 可用；清单外行返回 `30060`；calculator 覆盖超长、非法字符、深嵌套、除零、代码注入、幂运算，失败映射 `30053/30057` 且绝不执行 eval | REQ-TOL-001/002、REQ-CFG-003 / AC-TOL-001/002、AC-CFG-004 | G-10 | BuiltinLocalToolsTest + ToolRuntimeIT |
| TC-M3-MCP-001 | P0 | 内置 Mock MCP 两种传输；配置覆盖基线快照 | 分别完成 test→discover→grant→tools/call→结果回灌；执行后比对配置 | `streamable_http` 与同步响应型 `sse` 均按 JSON-RPC 2.0 成功，工具最终 `succeeded`；只回 202/需独立 GET 的 sse 判 `protocol_incompatible/30052`；所有 SysConfigOverride 在 finally 还原，无配置残留 | REQ-MCP-001/002/003、REQ-CHAT-003 / AC-MCP-001～006、AC-CHAT-007 | G-11 | McpClientMockIT/McpGovernanceIT + MockMcpServer |

### 10.8 H 组：一期授权 Deferred / 不可达签署记录（6 条）

| TC-ID | P | 前置条件 | 核对步骤 | 预期结果/签署状态 | REQ-AC | §8.3 | 验证方式 |
|---|:---:|---|---|---|---|:---:|---|
| TC-M3-DEF-001 | P1 | 限流阈值可由隔离夹具触发 | 仅触发**建流前**限流；静态确认流内无新增限流点 | 建流前标准 JSON：HTTP 200 + `code=10005` + `data.retryAfterSeconds≥1`；SSE 内 `10005` 与 `tool.retryAfterSeconds` 记 `⏸（H1 授权）`，**不构造、不判缺陷** | REQ-LMT-001 / AC-LMT-001 | H1 | MessageRateLimiterIT + 静态审查 |
| TC-M3-DEF-002 | P2 | 已执行 TC-M3-USAGE-001 | 核对二期方案未落地 | `rateLimitedCount` 一期按 E9 实测恒 0；其非零实现方案记 `⏸（H2 授权）`，不得自造事件/估算值 | REQ-LMT-001、REQ-OBS-001 / AC-LMT-001、AC-OBS-001 | H2 | 签署核对记录 |
| TC-M3-DEF-003 | P2 | 架构缓存登记表 | 扫描三类预留键读写与 cache evict 断言范围 | Skill/MCP/local 授权缓存与 TTL 键一期禁用，记 `⏸（H3 授权）`；本期直读 DB，不把预留键纳入失效缺陷 | REQ-CFG-004 / AC-CFG-005 | H3 | 静态扫描 + 签署核对 |
| TC-M3-DEF-004 | P2 | PRD V1.2 决策 | 检查测试范围和产品路由 | 无管理 UI 记 `⏸（H4 授权）`；§7 管理类接口仍按 IT/接口实测/数据核验完成；不得出现页面测试缺口 | M2-min 全部 REQ / 对应 AC | H4 | 范围审查 |
| TC-M3-DEF-005 | P2 | PRD DEC-008/Boss 决策 | 核对 M2 REQ 状态 | M2 管理后台全量保持 Deferred（二期），记 `⏸（H5 授权）`；不得记 Cancelled 或作为 M3 缺陷 | M2 Deferred REQ / AC-ADM-001/002、AC-RBAC-004、AC-AUD-004 | H5 | 需求追溯审查 |
| TC-M3-DEF-006 | P2 | 已执行 C5 与 SSRF 双点位 | 核对残余风险边界 | 已进入 invoke 的一次调用窗口与 DNS TTL=10 内重绑定窗口记 `⏸（H6 授权）`；窗口后新调用仍必须拒绝；不要求即时中断执行中调用 | REQ-MCP-003 / AC-MCP-004 | H6 | 风险接受记录 + C5 证据 |

## 11. M3 补充专项用例（7 条）

### 11.1 凭据离线加密（1 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果（具体码/状态） | REQ-AC | 关联 §8.3 | 验证方式 |
|---|:---:|---|---|---|---|---|---|
| TC-M3-MCP-ENC-001 | P0 | 离线工具、测试密钥、gift/redbook MCP key | 对相同明文加密两次；解密验证；复制密文到其他 tenant/mcpKey；扫描 stdout/stderr/临时文件/库/API/日志 | 算法 AES-256-GCM，密文均匹配 `v1:{base64url(iv)}:{base64url(ciphertext+tag)}` 且两次不同；AAD=`mcp:{tenantId}:{mcpKey}`；跨租户/key 复制解密失败，校验入口 `30060 rule=invalidCipher`，运行调用不得泄露明文；任何输出、数据库、接口、日志、审计均无明文/密文片段/IV/Tag | REQ-MCP-001/004 / AC-MCP-001/007 | F4、G-9 | CredentialCipherTest + 离线工具实测 + 数据核验 |

### 11.2 前端交互、响应式与可访问性（6 条）

| TC-ID | P | 前置条件 | 步骤 | 预期结果 | REQ-AC/设计基线 | 验证方式 |
|---|:---:|---|---|---|---|---|
| TC-M3-UI-001 | P1 | 可依次注入 8 种 tool status | 逐状态截图、无障碍快照并核对配置文案 | `pending/awaiting_confirmation/running/succeeded/failed/timed_out/cancelled/denied` 均以**图标+文字+辅助色**辨识，不只靠颜色；状态文案来自 `display.tool_status_labels`，风险文案来自 `display.tool_risk_labels` | REQ-TOL-002 / AC-TOL-002；DS §14.2/14.8 | toolStatusRender.spec.ts + E2E |
| TC-M3-UI-002 | P1 | Composer/正文已有焦点；high confirm 到达 | 观察焦点与 VoiceOver；等待多个倒计时 tick、临界提醒、终态 | 卡片 `role=group` 且**不抢焦点**；单一 `aria-live=polite` 首次只播报一次，倒计时不逐秒播报，临界最多一次；running/心跳不重复播报；状态收敛仅在焦点将丢失时修复 | REQ-TOL-002 / AC-TOL-002；DS §14.3.3/14.8 | toolConfirmCard.spec.ts + E2E/VoiceOver |
| TC-M3-UI-003 | P1 | confirm 卡可操作 | Tab、Shift+Tab、Enter、Space、Esc；提交后快速重复点击 | Tab 顺序“拒绝→允许执行→后续控件”；Enter/Space 只触发当前按钮；无默认允许；Esc **不提交、不拒绝**，仅移焦到停止按钮；提交后双按钮锁定、防重复，等待 SSE 原位收敛 | REQ-TOL-002 / AC-TOL-002；DS §14.3.3 | toolConfirmCard.spec.ts + E2E |
| TC-M3-UI-004 | P1 | 长 toolKey、长摘要、动态 remaining、200% 缩放 | 在 375/768/1024/1440 逐档走查并检测 scrollWidth | 四档均无页面级横向滚动；375 卡片不吸底且不遮 Composer，按钮双列/空间不足改单列且 ≥44px；阅读列 720px 约束正确；长内容只在组件内换行/省略，safe-area 生效 | AC-NFR-003；DS §14.6/14.8 | Playwright MCP resize + E2E 截图 |
| TC-M3-UI-005 | P1 | 浏览器模拟 `prefers-reduced-motion: reduce` | 触发卡片进入、状态切换、spinner、倒计时、摘要展开 | 全部降级为 ≤100ms 交叉淡化或直接出现；无位移/缩放/旋转/循环；倒计时静态轨道+文本；功能和焦点顺序不变 | AC-NFR-003；DS §14.7/14.8 | E2E + CSS 审查 |
| TC-M3-UI-006 | P1 | 正常 motion | DevTools/源码扫描并快速反转状态 | 动画仅 `transform/opacity`（颜色可短过渡）；无 `transition: all`，不动画 height/width/top/left/margin/padding；倒计时用 `scaleX`，状态反转可中断且从当前视觉值继续，will-change 仅动画期存在 | AC-NFR-003；DS §14.7 | noHardcodeGuard.spec.ts + 源码/性能走查 |

## 12. M1 强制回归清单（防 M3 破坏既有闭环）

| 回归包 | 必跑 TC/场景 | 红线 |
|---|---|---|
| 跨租户隔离 | `TC-M1-TEN-001～016` 全 16 条，另联动 `TC-M1-CON-010` | 覆盖率与通过率均 100%；任何 `tenantId` 伪造无效；跨租户统一 `10004` |
| SSO 三层 | 第 1 层未登录跳转 `TC-M1-AUTH-001`；第 2 层有效登录态 `TC-M1-AUTH-002～004/008/012`；第 3 层 `20000～20005` 与 30001 不误判 `TC-M1-AUTH-005～007` | 真实登录态必需；沿用 M1 Mock Thrift 仅替代内网服务，不能证明真实互通/验签；上线前真实 eyesUser 内网复验仍是前置 |
| 响应契约 | `TC-M1-API-001～007` + M3 全部新接口 | `/api/v1/**` HTTP 恒 200、数字 code、`timestamp`、camelCase、ID string；无 `10002/40001`、无 `/login`、logout、Cookie、OAuth2/PKCE |
| 会话/流式 | `TC-M1-CON-001～010`、`TC-M1-CHAT-001～012`、`TC-M1-SEC-001～005` | M3 不得破坏幂等、停止、重新生成、最终一致性、Markdown/XSS、meta 首发和 done 必发 |
| 前端基础交互 | `TC-M1-UI-001～013` | IME 不误发、焦点/200% 缩放补齐 M1 条件项、console 无新增 error、四档无横向滚动 |
| 性能门禁 1 | `TC-M1-PERF-001`：缓存命中租户识别+配置读取 P95 ≤20ms | 按 ADR-005 口径：稳态 0 MySQL + ≤1 Redis；跨公网开发环境同时记录等价结构判据 |
| 性能门禁 2 | 修订后的 `TC-M1-PERF-002`：无工具、工具但首轮不调用、首轮即工具三链路 | P95 ≤5s；新锚点=首个可见帧 `delta/tool` 取先到者；禁止只监听 delta；meta/:ping 不计 |
| 性能门禁 3 | `TC-M1-PERF-004`：流式输出中 stop + 确认等待中 stop | 两类均 ≤1s 停止追加/收敛；确认等待必须唤醒并 `cancelled` |
| 性能门禁 4 | `TC-M1-PERF-003` + M3 新非 AI 接口 | P95 ≤500ms；MCP test/discover 用环回 Mock 口径；真实外部网络不计但受 mcp 超时约束 |

> 🔴 性能专项防回退：D-002/D-004/D-006 已证明“顺手加 `@Transactional(readOnly=true)`”“顺手增加 Redis 读取”“把 L1 热路径退回 L2/DB”会直接击穿 20ms。终验必须同时跑 `TenantResolverCacheTest`、`ConfigServiceLocalCacheTest` 并审查 TenantFilter/TenantResolver/ConfigService 的业务改动。

## 13. §8.3 映射完整性与用例统计

### 13.1 58 项映射索引

| 组 | §8.3 条目 → TC-ID | 映射率 |
|---|---|---:|
| A | A1→TC-M3-SSE-001；A2→002；A3→003；A4→004；A5→005；A6→006 | 6/6 |
| B | B1→TC-M3-CONF-001；B2→002；B3→003；B4→004；B5→005；B6→006 | 6/6 |
| C | C1→TC-M3-AUTHZ-001；C2→TC-M3-SSRF-001；C3→TC-M3-AUTHZ-002；C4→003；C5→004；C6→005；C7→TC-M3-BIND-001 | 7/7 |
| D | D1→TC-M3-TEN-001；D2→002；D3→TC-M3-RBAC-001；D4→002；D5→TC-M3-TEN-003 | 5/5 |
| E | E1→TC-M3-OBS-001；E2→002；E3→003；E4→004；E5→005；E6→006；E7→007；E8→008；E9→TC-M3-USAGE-001；E10→002；E11→003 | 11/11 |
| F | F1→TC-M3-AUD-001；F2→002；F3→003；F4→004；F5→005；F6→006 | 6/6 |
| G | G-1→TC-M3-CFG-001；G-2→002；G-3→003；G-4→004；G-5→005；G-6→006；G-7→007；G-8→TC-M3-ORCH-001；G-9→TC-M3-CFG-008；G-10→TC-M3-LOCAL-001；G-11→TC-M3-MCP-001 | 11/11 |
| H | H1→TC-M3-DEF-001；H2→002；H3→003；H4→004；H5→005；H6→006 | 6/6 |
| **合计** | **58 个 §8.3 条目均映射到唯一 TC-ID** | **58/58（100%）** |

### 13.2 数量与优先级

| 范围 | 总数 | P0 | P1 | P2 |
|---|---:|---:|---:|---:|
| §8.3 核心签署用例 | 58 | 45 | 8 | 5 |
| M3 补充专项（AES + 前端/无障碍） | 7 | 1 | 6 | 0 |
| **M3 新增合计** | **65** | **46** | **14** | **5** |

> H 组 TC 虽有编号，但 H2～H6 是授权范围核对而非可执行功能路径；只有 H1 的“建流前 10005”执行，SSE 内 10005 明确不执行。

## 14. 覆盖率、执行顺序与签署门槛

### 14.1 执行顺序

1. 静态守护与单元测试：租户/事务/RBAC 扫描、SSRF/AES、Skill、函数名、参数与截断、启动检查。
2. MockMvc 集成：A～G 核心链路；失败后先按接口/数据/鉴权分层定位。
3. MockMcpServer：两种传输、连接分类、故障、超时、大结果、授权竞态。
4. 经批准的 DBA 三步配置流程：改库 → 校验 → 缓存失效；随后接口与只读数据核验。
5. 前端 Vitest 后执行真实 SSO 登录态 E2E、四档/VoiceOver/Reduced Motion。
6. 最后执行 M1 全量回归与四道性能门禁；任何修复触及共享热路径时重跑门禁 1～4。

### 14.2 放行指标

| 指标 | M3 签署门槛 |
|---|---:|
| M2-min + M3 REQ 覆盖率 | 100% |
| M2-min + M3 AC 覆盖率 | 100% |
| `api-spec.md` §8.3 映射率 | 58/58（100%） |
| P0 用例通过率 | **100%** |
| P1 用例通过率 | ≥95% |
| 跨租户安全用例覆盖率/通过率 | **100% / 100%** |
| M1 强制回归 | P0 100%，四道性能门禁全部通过 |
| SSO | 三层全部完成；真实 SSO storageState 可用；Mock Thrift 边界如实标注 |
| 响应/错误码契约 | 100%；无 `10002/40001`、字符串业务码或业务 HTTP 4xx |
| 未关闭缺陷 | P0=0、P1=0 |
| 数据纪律 | mysql MCP 全程只读；所有写入来自测试夹具/后端测试接口/经批准 DBA 变更 |

### 14.3 一票否决与签署规则

以下任一成立，@测试工程师不签署：

- §8.3 任一 A～G 项为 `❌`；或出现未被 H1～H6 明确授权的 `⏸`。
- 任一 P0 未通过、跨租户安全未 100% 通过、存在未关闭 P0/P1。
- `rateLimitedCount != 0`；运行时 `30060` 向终端泄露 `violations`；撤权后新调用仍执行；`@TenantRole` 在 eyes-auth 关闭时返回 `code=0`。
- 审计失败中断 SSE、confirm 回放写审计、冲突审计不是恰好 1 行、审计可 update/delete 或出现敏感值。
- 四道性能门禁任一回退，或四档响应式/键盘/Reduced Motion 未完成。
- 未经批准写库，或通过 mysql MCP 执行任何 INSERT/UPDATE/DELETE/DDL。

## 15. 风险、协作请求与版本记录

### 15.1 M3 最高风险 Top 5

| 排名 | 风险 | 为什么最易出错 | 主防线 TC |
|---:|---|---|---|
| 1 | 清单构造后撤权仍执行 | 清单与执行跨越模型生成/确认等待，多轮窗口可达数分钟 | TC-M3-AUTHZ-002、003、004 |
| 2 | confirm/stop/断线竞态与审计事务 | Future 唤醒、行锁、短事务和 SSE finally 交叉，易死锁、残留非终态或丢审计 | TC-M3-CONF-003、004、006 |
| 3 | SSRF 只在保存时检查 | DBA 旁路改库、DNS/重定向会绕过单点校验，且错误状态易误映射成 `failed/30052` | TC-M3-SSRF-001、TC-M3-AUTHZ-003/004 |
| 4 | 配置/埋点 fail-open 与内部结构泄露 | analytics 键缺失可能变全量采集；复用管理端 `violations` 会泄露配置拓扑 | TC-M3-OBS-005～007、TC-M3-SSE-004、TC-M3-CFG-005 |
| 5 | M3 改动拖垮 M1 热路径与首字 | 顺手事务/Redis/DB 读取会重演 D-002/D-004/D-006；只监听 delta 会误判首轮工具 | TC-M1-PERF-001～004、TC-M3-SSE-003、TC-M3-CFG-004 |

### 15.2 执行阶段前置请求

@Boss / @后端 / @架构师 ❓ M3 终验前置
- **问题**：终验需要受控造数、真实 SSO 登录态、隔离配置启动与 DBA 旁路改库窗口。
- **背景**：撤授权、SSRF 双点位、analytics fail-closed、递归校验和缓存失效无法只靠现有只读数据完整覆盖。
- **期望回复**：
  1. 提供 gift/redbook 的 M3 标准夹具、四类角色与 low/medium/high、幂等/非幂等工具数据；
  2. 提供可人工完成一次耶瞳 SSO 的账号/浏览器窗口，并允许保存本地 `storageState`；
  3. 明确真实 eyesUser 内网复验环境与时间；若本轮仍不可达，批准沿用 M1 Mock Thrift 边界并保留上线前置；
  4. 批准由 DBA/后端执行指定 SQL 的隔离窗口，覆盖撤授权、endpoint 改内网、analytics 两键缺失/越界、二层非法/环引用；@测试不直接写库；
  5. 提供独立后端进程/端口与隔离 Redis 前缀，供启动失败、并发与性能测试，避免影响返工进程；
  6. 确认 MockMcpServer 的 DNS/TLS/auth/connect/timeout/protocol/no-tools/大结果模式均可切换；
  7. 批准终验窗口执行构建、JUnit/IT、Vitest、Playwright、性能采样及只读 SQL；本计划编写阶段未执行这些动作。

### 15.3 基线一致性提示

`api-spec.md` §8.3 F3 的文字写“11 项”，而 `api-spec.md` §7.14 / `architecture.md` §11.1.1 当前 action 登记表逐行可见的集合数量需由 @架构师最终确认。TC-M3-AUD-003 不猜测数量，按**登记表精确集合**断言；任何未登记 action 仍直接判缺陷。

### 15.4 版本记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.0 | 2026-08-12 | M1 全量回归测试计划 |
| V1.1 | 2026-08-13 | 增量新增 M3（含 M2-min）终验范围、§8.3 全 58 项映射、7 条补充专项、M1 回归门禁、一期无管理 UI 的接口+数据核验路径与签署门槛 |
