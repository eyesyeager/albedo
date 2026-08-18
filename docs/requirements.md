# Albedo 功能需求清单与覆盖矩阵

**版本**：V1.4  
**日期**：2026-08-14  
**需求基线**：`docs/prd.md` V1.4  
**维护人**：@产品经理专家  
**测试用例回填人**：@测试工程师

> 本文用于需求追踪。优先级仅表示业务价值与逻辑依赖；P0/P1/P2 均属于交付范围。每个里程碑独立完成六人签署。

---

## 1. 里程碑边界

| 里程碑 | 范围 | 完成定义 |
|---|---|---|
| M1 | 租户识别、耶瞳 SSO、已发布站点配置、已发布 Agent、会话、流式对话 | M1 已有条件通过；真实 eyesUser Thrift 内网互通仍为上线前置复验项 |
| M2 Deferred（二期） | `/admin/*`、`/platform/*` 管理后台全量，配置与 AI 资源管理 UI、前端权限渲染、管理操作审计 | 一期不实施、不计入 M3 缺陷；二期完整补做并独立六人签署 |
| M2-min（并入 M3，已完成） | M3 接口后端准入、运行时安全事件审计、独立配置校验、每次 MCP 调用前 HTTPS/SSRF、凭据离线加密、缓存失效及 Skill/MCP/Tool 最小后端配置授权 | 无管理 UI；已通过单测、集成测试、接口实测、离线工具实测和数据核验 |
| M3（已终验） | Skill 版本化与运行时消费、MCP 配置/发现/授权/运行、高风险确认、一期低风险本地 Tool、隐私优先埋点、历史建流前消息限流 | 65 条为 59 条通过 + 6 条授权 Deferred + 0 失败；M2-min + M3 REQ/AC 100% 覆盖 |
| 用户对话限流与日限额（待交付） | QPM 数据库默认 3、每日数据库默认 50、租户覆盖、租户时区重置、计数结算、前端额度展示 | REQ-LMT-003、REQ-QUOTA-001～005 及 AC-LMT-003～005、AC-QUOTA-001～016 全部通过 |

### 1.1 M1 最小可用闭环

M1 必须完成：

1. 使用 `sys_config` dev host 映射或手工 Host 头分别模拟 `gift`、`redbook`；生产环境开关必须关闭。
2. 公开页面按租户加载已发布站点配置与已发布启用 Agent。
3. 未登录访问受保护能力时整页跳耶瞳 SSO；回跳 Token 写入 localStorage 并立即从 URL 清除。
4. 请求 Header 携带 Token，`auth-type=1` 从响应头回写续期 Token；20000～20005 清 Token 并整页重登。
5. 首次访问当前租户需登录接口时，以 eyesUser `uid` 惰性建立租户成员关系。
6. 登录用户可创建、打开、分页、重命名、删除本人会话，并完成文本流式问答、停止、重试、重新生成与 Markdown 安全展示。
7. `/api/v1/**` 业务响应统一 HTTP 200、code=0 唯一成功、必带 timestamp。

### 1.2 M1 明确不做

| M1 不做 | 承接里程碑 |
|---|---|
| 租户、站点配置、Agent、Skill、MCP、本地 Tool、成员与管理审计的完整管理后台 | M2 Deferred（二期） |
| 配置草稿/预览/发布/回滚管理流程 | M2 Deferred（二期） |
| Agent/Skill/MCP/本地 Tool 的完整管理与授权界面 | M2 Deferred（二期） |
| 本地租户角色管理 UI、前端权限渲染、管理操作审计 | M2 Deferred（二期） |
| M3 新增接口后端 RBAC、运行时安全事件审计、独立配置校验、凭据离线加密、缓存失效 | M2-min（并入 M3） |
| Skill 版本化与运行时消费 | M2-min + M3 |
| MCP 后端配置、连接测试、工具发现/逐项授权与 `streamable_http` / `sse` 运行时调用 | M2-min + M3 |
| 本地 Tool 后端注册/租户授权、运行时编排与高风险逐次确认 | M2-min + M3 |
| 产品埋点与租户级消息限流 | M3 已完成 |

> M2 状态是 Deferred，不是 Cancelled；二期必须完整补做。M2-min 不设独立签署，全部纳入 M3 验收。

---

## 2. 全部功能需求清单

| REQ-ID | 功能名称 | 优先级 | 里程碑 | 简述 | 关联 AC-ID | 关联 EX-ID |
|---|---|:---:|:---:|---|---|---|
| REQ-TEN-001 | 可信 Host 租户识别 | P0 | M1 | 精确匹配可信 Host；一期以 dev host 映射验证 gift/redbook，生产必须关闭映射 | AC-TEN-001、AC-TEN-007、AC-NFR-004 | EX-001、EX-028 |
| REQ-TEN-002 | 不可篡改租户上下文 | P0 | M1 | 租户身份仅由 Host 决定，忽略客户端 tenantId | AC-TEN-002 | EX-003 |
| REQ-TEN-003 | 全链路租户隔离 | P0 | M1 | 数据、关联、缓存、对象、检索、任务、日志、审计、指标均按租户隔离 | AC-TEN-003、AC-TEN-004、AC-TEN-005、AC-NFR-001 | EX-004、EX-006、EX-025 |
| REQ-TEN-004 | 租户启停与配置降级 | P0 | M1 | 暂停显示站点级 403；配置失败回退上一版本，无版本为站点级 503 | AC-TEN-006、AC-NFR-004 | EX-002、EX-006 |
| REQ-CFG-001 | 已发布站点配置读取 | P0 | M1 | 按租户展示标题、Logo、欢迎语、输入提示、页脚等已发布配置 | AC-CFG-001 | EX-006 |
| REQ-ADM-001 | 管理后台全量 | P0 | 二期 Deferred | `/admin/*`、`/platform/*` 覆盖租户/域名、站点、Agent、Skill、MCP、Tool、成员角色、审计及完整治理状态 | AC-ADM-001、AC-ADM-002、AC-RBAC-004、AC-AUD-004 | EX-005、EX-009、EX-012、EX-024 |
| REQ-CFG-002 | 配置草稿、发布与回滚管理 | P1 | M2 Deferred（二期） | 二期支持草稿、预览、校验、发布、失败保旧版本与历史回滚 | AC-CFG-002、AC-ADM-001 | EX-005、EX-012 |
| REQ-CFG-003 | 独立配置校验与运行时兜底 | P0 | M2-min 已完成 | Agent/Skill/MCP/Tool 改库后可独立校验；运行时非法配置 code=30060，禁止 NPE/500 | AC-CFG-003、AC-CFG-004 | EX-031、EX-032 |
| REQ-CFG-004 | 缓存失效接口 | P0 | M2-min 已完成 | 仅平台管理员调用；覆盖 config_version、Agent 快照、sys_config L1/L2；必须审计 | AC-CFG-005 | EX-024、EX-033 |
| REQ-AUTH-001 | 耶瞳 SSO 授权与回跳 | P0 | M1 | 整页跳 `OAuth2?clientId=361925`；原页接收 Token，存本地后立即清 URL | AC-AUTH-001、AC-AUTH-002 | EX-007、EX-008 |
| REQ-AUTH-002 | 注册入口与授权闭环 | P0 | M1 | 登录与注册均使用耶瞳 SSO 统一入口，完成后回原页面 | AC-AUTH-001、AC-CON-001 | EX-007、EX-008 |
| REQ-AUTH-003 | Token 鉴权、续期与惰性建户 | P0 | M1 | Header 携带 Token，响应头续期；首次需登录请求以 uid 建立租户成员关系 | AC-AUTH-003、AC-AUTH-005、AC-AUTH-006、AC-AUTH-007 | EX-009、EX-027 |
| REQ-AUTH-004 | 鉴权失效与退出 | P0 | M1 | 20000～20005 或主动退出时清 Token 并整页跳 SSO，无后端 logout | AC-AUTH-004、AC-AUTH-008 | EX-026、EX-027 |
| REQ-AGT-001 | Agent 管理 UI 与增删改查 | P1 | M2 Deferred（二期） | 二期管理角色仅管理本租户 Agent，支持搜索、筛选、复制和排序 | AC-AGT-002、AC-ADM-001、AC-ADM-002 | EX-012 |
| REQ-AGT-002 | Agent 发布治理与启停管理 | P1 | M2 Deferred（二期） | 二期发布形成不可变版本，校验失败不影响线上，停用后历史只读 | AC-AGT-003、AC-AGT-004 | EX-005、EX-011、EX-012 |
| REQ-AGT-003 | 默认 Agent 管理 | P1 | M2 Deferred（二期） | 二期每租户最多一个默认 Agent | AC-AGT-002 | EX-010、EX-012 |
| REQ-AGT-004 | 已发布 Agent 展示与运行约束 | P0 | M1 | 只展示已发布启用 Agent；停用后不可新建或继续对话 | AC-AGT-001 | EX-010、EX-011 |
| REQ-SKL-001 | Skill 版本化后端能力 | P1 | M2-min 已完成 | DB 配置形成不可变版本并由 Agent 版本精确引用，无管理 UI | AC-SKL-001 | EX-031 |
| REQ-SKL-002 | Skill 运行时消费 | P1 | M3 已完成 | 仅消费 Agent 版本绑定的 Skill 版本，不恢复已停用外部工具权限 | AC-SKL-002、AC-CHAT-007 | EX-011、EX-031 |
| REQ-SKL-003 | Skill 管理 UI 与发布治理 | P1 | M2 Deferred（二期） | 二期补做 Skill 管理、草稿、校验、发布与历史追溯 | AC-ADM-002 | EX-005、EX-012 |
| REQ-MCP-001 | MCP 后端配置、凭据保护与连接测试 | P0 | M2-min 已完成 | DB 维护地址/传输/鉴权/超时；AES-GCM 密文入库，凭据永不回显 | AC-MCP-001 | EX-016、EX-025、EX-032 |
| REQ-MCP-002 | MCP 工具发现与逐项授权后端能力 | P0 | M2-min 已完成 | 发现后新增工具默认禁用；DB 按租户逐项授权 | AC-MCP-002 | EX-016、EX-017 |
| REQ-MCP-003 | MCP 运行时完整链路 | P0 | M3 已完成 | 两种传输；连接/发现/每次调用前 HTTPS/SSRF；发现、授权、调用由 Mock 验收 | AC-MCP-003、AC-MCP-004、AC-MCP-005、AC-MCP-006、AC-CHAT-007 | EX-016、EX-017、EX-029、EX-030、EX-031 |
| REQ-MCP-004 | MCP 凭据离线加密工具 | P0 | M2-min 已完成 | 输出供 SQL 使用的 AES-GCM 密文；标准输出、日志、临时文件不含明文 | AC-MCP-007 | EX-032 |
| REQ-MCP-005 | MCP 管理 UI | P1 | M2 Deferred（二期） | 二期补做配置、凭据替换、连接测试、发现与授权页面 | AC-ADM-002 | EX-016 |
| REQ-TOL-001 | 本地 Tool 后端注册与租户授权 | P0 | M2-min 已完成 | 一期仅内置 `datetime_now`、`calculator`，均 low 且无业务副作用；租户不得上传代码 | AC-TOL-001 | EX-018、EX-019、EX-031 |
| REQ-TOL-002 | 工具风险确认与本地 Tool 运行时编排 | P0 | M3 已完成 | MCP 未显式配置风险时默认 high；高风险逐次确认由 Mock MCP 验证；一期本地 Tool 均 low | AC-TOL-002、AC-TOL-003、AC-CHAT-007 | EX-018、EX-019、EX-036 |
| REQ-TOL-003 | 本地 Tool 管理 UI | P1 | M2 Deferred（二期） | 二期补做平台注册与租户授权页面 | AC-ADM-002 | EX-018 |
| REQ-TOL-004 | 有副作用的本地 Tool | P1 | 二期 Deferred | 接入真实业务系统，覆盖确认、幂等、结果核验、撤销/补偿、审计与越权阻断 | AC-TOL-004 | EX-019、EX-036 |
| REQ-CON-001 | 新建与打开会话 | P0 | M1 | 首次发送原子创建会话并绑定 tenantId、uid、Agent 版本 | AC-CON-001、AC-CON-002 | EX-009、EX-013 |
| REQ-CON-002 | 会话列表、分页、改名与删除 | P1 | M1 | 仅管理本人当前租户会话，稳定分页，支持改名和软删除 | AC-CON-003、AC-CON-004 | EX-004、EX-022、EX-023 |
| REQ-CHAT-001 | 文本发送与流式响应 | P0 | M1 | 发送文本、持久化用户消息、流式展示并保证最终一致 | AC-CHAT-001、AC-CHAT-004、AC-CHAT-005、AC-CHAT-006 | EX-014、EX-015、EX-020 |
| REQ-CHAT-002 | 停止、失败重试与重新生成 | P0 | M1 | 停止后保存已有内容；重试和再生成不重复用户消息 | AC-CHAT-002、AC-CHAT-003 | EX-013、EX-014、EX-015、EX-022 |
| REQ-CHAT-003 | Skill/MCP/Tool 运行时总编排 | P0 | M3 已完成 | 绑定按生成期快照；授权/状态在 invoke 前复查；invoke 后撤权本次可在超时内完成，后续拒绝 | AC-CHAT-007、AC-MCP-003、AC-TOL-002、AC-TOL-003 | EX-016、EX-017、EX-018、EX-019、EX-029、EX-030、EX-031、EX-036、EX-037 |
| REQ-CHAT-004 | 上下文与标题 | P1 | M1 | 对话历史超限时摘要后窗口回退；标题失败使用确定规则 | AC-CON-004、AC-CHAT-001 | EX-014、EX-015 |
| REQ-CHAT-005 | system 提示总长护栏 | P0 | M3 已完成 | 默认上限 100,000 字符；超限模型调用前 code=30060，禁止静默截断 | AC-CHAT-008、AC-CFG-004 | EX-031、EX-035 |
| REQ-RBAC-001 | M3 接口后端角色准入 | P0 | M2-min 已完成 | M1 `@TenantRole` 覆盖全部 M3 租户级接口；后端为最终准入 | AC-RBAC-001、AC-RBAC-002、AC-RBAC-003 | EX-009、EX-024 |
| REQ-RBAC-002 | 前端菜单与页面权限渲染 | P1 | M2 Deferred（二期） | 二期管理 UI 按角色显示入口与操作 | AC-RBAC-004 | EX-009 |
| REQ-AUD-001 | 运行时安全事件审计 | P0 | M2-min 已完成 | 指定事件完整留痕、不可篡改；敏感值只记摘要；安全动作审计失败时失败关闭 | AC-AUD-001、AC-AUD-002、AC-AUD-003 | EX-024、EX-025 |
| REQ-AUD-002 | 管理操作审计 | P1 | M2 Deferred（二期） | 二期补做管理后台全部关键操作审计 | AC-AUD-004 | EX-024、EX-025 |
| REQ-OBS-001 | 产品埋点与运行指标 | P1 | M3 已完成 | 含租户维度且不记录敏感正文；开关/采样率异常时 fail-closed 全丢弃 | AC-OBS-001、AC-OBS-002 | EX-025、EX-034 |
| REQ-OBS-002 | 历史 QPM 限流用量权威数据源 | P1 | 二期 Deferred | 后端 QPM 拒绝点侧写 `messageRateLimited` 到 `analytics_events`，聚合 `rateLimitedCount`；不采用前端上报，不作为每日额度来源 | AC-OBS-003 | EX-039 |
| REQ-LMT-001 | 建流前租户级消息限流 | P1 | M3 已完成（历史基线） | 历史默认 30 次/分钟、120 次/小时；自 REQ-LMT-003 生效后阈值和小时窗由新需求替代 | AC-LMT-001、AC-OBS-003 | EX-021、EX-038、EX-039 |
| REQ-LMT-002 | SSE 流内限流 | P1 | 二期 Deferred | 对已建流生成实施限流并以数字错误码和 done 事件合法收敛 | AC-LMT-002 | EX-038 |
| REQ-LMT-003 | 用户级 QPM 与租户配置覆盖 | P0 | 新需求待交付 | 平台数据库默认 3 次/分钟；租户可覆盖；按 tenantId+uid 隔离；废弃小时窗；沿用 10005 | AC-LMT-003～005 | EX-021、EX-041、EX-046 |
| REQ-QUOTA-001 | 生成尝试计数与幂等结算 | P0 | 新需求待交付 | 生成前失败释放；开始生成后的完成/停止/断流/失败计一次；幂等重放不重复计 | AC-QUOTA-004～009 | EX-042～045 |
| REQ-QUOTA-002 | 租户日历日限额与重置 | P0 | 新需求待交付 | 平台数据库默认 50 次/日；租户可覆盖；按租户时区零点重置；并发不超额 | AC-QUOTA-001～003、AC-QUOTA-009、AC-QUOTA-015 | EX-040、EX-041、EX-048、EX-049 |
| REQ-QUOTA-003 | 当前用户额度查询与实时同步 | P1 | 新需求待交付 | 初始查询、结算、拒绝、页面恢复与跨日均返回当前租户当前 uid 权威快照 | AC-QUOTA-010、AC-QUOTA-013、AC-QUOTA-016 | EX-047、EX-048 |
| REQ-QUOTA-004 | Composer 额度展示与用尽交互 | P1 | 新需求待交付 | 展示剩余、已用/总量、重置时间；用尽保留输入和草稿并禁用发送 | AC-QUOTA-010～013 | EX-040、EX-047、EX-048 |
| REQ-QUOTA-005 | 平台默认与租户覆盖配置 | P0 | 新需求待交付 | QPM/日限额启停、阈值、生效时间来自数据库；缺失/非法不得代码兜底 | AC-LMT-004、AC-QUOTA-014～015 | EX-046、EX-049 |

---

## 3. 横向产品基线

以下规则适用于全部 REQ，不单独分配新的功能 REQ：

| 基线 | 规则 | 关联验收 |
|---|---|---|
| API 响应 | `/api/v1/**` 成功与业务失败一律 HTTP 200；code=0 唯一成功；必带 timestamp | AC-API-001、AC-NFR-004 |
| 分页与字段 | 分页为 `{list,total,page,pageSize}`；对外 JSON 为 camelCase | AC-API-002 |
| SSO 错误段 | 20000～20999 归耶瞳保留，业务严禁占用 | AC-AUTH-004、AC-API-001 |
| 页面级例外 | 未知 Host 404、租户暂停 403、配置异常 503 是站点页面，不是业务 API | AC-NFR-004 |
| Token 安全 | Token 只存 localStorage、只走 Header，不落 URL、日志、埋点、审计 | AC-AUTH-002、AC-NFR-002 |
| 生产隔离 | dev host 映射在生产必须关闭 | AC-TEN-007 |
| MCP 验收 | M3 使用内置 Mock MCP，不使用真实外部凭据 | AC-MCP-003、AC-MCP-006 |
| 无管理 UI | 一期不存在 `/admin/*`、`/platform/*` 页面；M2-min 只能用单测、集成、接口/离线工具实测与数据核验 | AC-ADM-002、全部 M2-min AC |
| 配置安全 | 直接写库后必须可独立校验，运行时非法配置 HTTP 200 + code=30060，禁止 NPE/500/字符串码 | AC-CFG-003、AC-CFG-004 |
| MCP 运行前校验 | 连接测试、发现、每次调用前均校验 HTTPS/SSRF | AC-MCP-004 |
| 凭据保护 | AES-GCM 密文入库、永不回显；离线工具不得泄露明文 | AC-MCP-001、AC-MCP-007 |
| 缓存一致性 | 仅平台管理员可失效 config_version、Agent 快照、sys_config L1/L2；调用必须审计 | AC-CFG-005、AC-AUD-003 |
| 本地 Tool 一期清单 | 仅 `datetime_now`、`calculator`，均 low；真实副作用本地 Tool 二期补做 | AC-TOL-001、AC-TOL-002、AC-TOL-004 |
| 高风险默认值 | MCP 工具未显式配置合法风险等级时按 high，必须逐次确认 | AC-TOL-002、AC-CHAT-007 |
| system 提示预算 | `chat.system_prompt_max_chars` 默认 100,000；超限 30060 拒绝，不截断 | AC-CHAT-008、AC-CFG-004 |
| 授权与绑定时效 | 绑定为生成期快照；撤权/停用在 invoke 前阻断，invoke 后本次残余窗口可完成 | AC-CHAT-007 |
| 埋点隐私 | 开关/采样率异常全部丢弃，允许形成数据缺口，不得全量采集回退 | AC-OBS-001、AC-OBS-002 |
| 限流链路边界 | 仅建流前执行 QPM 与日限额准入；SSE 流内限流仍 Deferred；旧 rateLimitedCount 口径不替代每日额度快照 | AC-LMT-003～005、AC-QUOTA-001～016、AC-LMT-002、AC-OBS-003 |
| 额度反硬编码 | QPM=3、日限额=50 均为平台数据库默认，租户可覆盖；代码不得在缺键时兜底 | AC-LMT-003～004、AC-QUOTA-014～015 |
| 额度时区 | 每日额度按租户 IANA 时区相邻当地零点重置，存储 UTC | AC-QUOTA-003 |
| 额度隐私 | 快照只返回可信 Host 当前租户与认证 uid；不接受目标 tenantId/uid | AC-QUOTA-002、AC-QUOTA-013、AC-QUOTA-016 |
| ADMIN 失败码 | eyes-auth 开启为 20000；仅 test 关闭为 10003；均 HTTP 200 + 数字 code | AC-RBAC-001～003 |

---

## 4. 需求覆盖矩阵

> “测试用例 ID”列由 @测试工程师回填；不得以空白表示无需测试。
> 用例定义见 `docs/test-plan.md`，实测结果见 `docs/test-report.md`。M2 用例 Deferred 至二期；M2-min 与 M3 用例统一使用 `TC-M3-*` 编号并随 M3 执行，具体编号由 @测试工程师同步。

| REQ-ID | 验收标准 AC-ID | 里程碑 | 测试用例 ID |
|---|---|:---:|---|
| REQ-TEN-001 | AC-TEN-001、AC-TEN-007、AC-NFR-004 | M1 | TC-M1-TEN-001、TC-M1-TEN-002、TC-M1-TEN-003、TC-M1-TEN-006、TC-M1-TEN-007 |
| REQ-TEN-002 | AC-TEN-002 | M1 | TC-M1-TEN-008 |
| REQ-TEN-003 | AC-TEN-003、AC-TEN-004、AC-TEN-005、AC-NFR-001 | M1 | TC-M1-TEN-009～016、TC-M1-CON-010 |
| REQ-TEN-004 | AC-TEN-006、AC-NFR-004 | M1 | TC-M1-TEN-004、TC-M1-TEN-005、TC-M1-UI-012 |
| REQ-CFG-001 | AC-CFG-001 | M1 | TC-M1-CFG-001、TC-M1-CFG-002、TC-M1-CFG-003 |
| REQ-ADM-001 | AC-ADM-001、AC-ADM-002、AC-RBAC-004、AC-AUD-004 | 二期 Deferred | 二期补充；一期不执行 |
| REQ-CFG-002 | AC-CFG-002、AC-ADM-001 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-CFG-003 | AC-CFG-003、AC-CFG-004 | M2-min 已完成 | TC-M3-CFG-001～002 |
| REQ-CFG-004 | AC-CFG-005、AC-AUD-003 | M2-min 已完成 | TC-M3-CFG-003 |
| REQ-AUTH-001 | AC-AUTH-001、AC-AUTH-002 | M1 | TC-M1-AUTH-001、TC-M1-AUTH-002、TC-M1-AUTH-013 |
| REQ-AUTH-002 | AC-AUTH-001、AC-CON-001 | M1 | TC-M1-AUTH-001、TC-M1-AUTH-012 |
| REQ-AUTH-003 | AC-AUTH-003、AC-AUTH-005、AC-AUTH-006、AC-AUTH-007 | M1 | TC-M1-AUTH-003、TC-M1-AUTH-004、TC-M1-AUTH-009、TC-M1-AUTH-010、TC-M1-AUTH-011 |
| REQ-AUTH-004 | AC-AUTH-004、AC-AUTH-008 | M1 | TC-M1-AUTH-005、TC-M1-AUTH-006、TC-M1-AUTH-007、TC-M1-AUTH-008、TC-M1-API-007 |
| REQ-AGT-001 | AC-AGT-002、AC-ADM-001、AC-ADM-002 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-AGT-002 | AC-AGT-003、AC-AGT-004 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-AGT-003 | AC-AGT-002 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-AGT-004 | AC-AGT-001 | M1 | TC-M1-AGT-001、TC-M1-AGT-002、TC-M1-AGT-003、TC-M1-AGT-004 |
| REQ-SKL-001 | AC-SKL-001 | M2-min 已完成 | TC-M3-SKL-001 |
| REQ-SKL-002 | AC-SKL-002、AC-CHAT-007 | M3 已完成 | TC-M3-SKL-002、TC-M3-CHAT-001 |
| REQ-SKL-003 | AC-ADM-002 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-MCP-001 | AC-MCP-001 | M2-min 已完成 | TC-M3-MCP-001 |
| REQ-MCP-002 | AC-MCP-002 | M2-min 已完成 | TC-M3-MCP-002 |
| REQ-MCP-003 | AC-MCP-003、AC-MCP-004、AC-MCP-005、AC-MCP-006、AC-CHAT-007 | M3 已完成 | TC-M3-MCP-003～006、TC-M3-CHAT-001 |
| REQ-MCP-004 | AC-MCP-007 | M2-min 已完成 | TC-M3-MCP-007 |
| REQ-MCP-005 | AC-ADM-002 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-TOL-001 | AC-TOL-001 | M2-min 已完成 | TC-M3-TOL-001 |
| REQ-TOL-002 | AC-TOL-002、AC-TOL-003、AC-CHAT-007 | M3 已完成 | TC-M3-CONF-001～006、TC-M3-AUTHZ-001～004、TC-M3-LOCAL-001、TC-M3-MCP-001 |
| REQ-TOL-003 | AC-ADM-002 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-TOL-004 | AC-TOL-004 | 二期 Deferred | 二期补充；一期仅由 Mock MCP 验证副作用语义 |
| REQ-CON-001 | AC-CON-001、AC-CON-002 | M1 | TC-M1-CON-001、TC-M1-CON-002、TC-M1-AGT-005、TC-M1-CHAT-012 |
| REQ-CON-002 | AC-CON-003、AC-CON-004 | M1 | TC-M1-CON-003～010 |
| REQ-CHAT-001 | AC-CHAT-001、AC-CHAT-004、AC-CHAT-005、AC-CHAT-006 | M1 | TC-M1-CHAT-001、TC-M1-CHAT-002、TC-M1-CHAT-003、TC-M1-CHAT-007、TC-M1-CHAT-008、TC-M1-CHAT-010、TC-M1-SEC-001～004、TC-M1-UI-001、TC-M1-UI-002 |
| REQ-CHAT-002 | AC-CHAT-002、AC-CHAT-003 | M1 | TC-M1-CHAT-004、TC-M1-CHAT-005、TC-M1-CHAT-006、TC-M1-CHAT-009、TC-M1-PERF-004 |
| REQ-CHAT-003 | AC-CHAT-007、AC-MCP-003、AC-TOL-002、AC-TOL-003 | M3 已完成 | TC-M3-AUTHZ-001～005、TC-M3-BIND-001、TC-M3-CONF-001～006 |
| REQ-CHAT-004 | AC-CON-004、AC-CHAT-001 | M1 | TC-M1-CON-007、TC-M1-CHAT-011 |
| REQ-CHAT-005 | AC-CHAT-008、AC-CFG-004 | M3 已完成 | TC-M3-CFG-006、TC-M3-SSE-004 |
| REQ-RBAC-001 | AC-RBAC-001、AC-RBAC-002、AC-RBAC-003 | M2-min 已完成 | TC-M3-RBAC-001～002 |
| REQ-RBAC-002 | AC-RBAC-004 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-AUD-001 | AC-AUD-001、AC-AUD-002、AC-AUD-003 | M2-min 已完成 | TC-M3-AUD-001～002 |
| REQ-AUD-002 | AC-AUD-004 | M2 Deferred（二期） | 二期补充；一期不执行 |
| REQ-OBS-001 | AC-OBS-001、AC-OBS-002 | M3 已完成 | TC-M3-OBS-001～008 |
| REQ-OBS-002 | AC-OBS-003 | 二期 Deferred | TC-M3-USAGE-001 已证明一期恒 0；非零数据源二期补充 |
| REQ-LMT-001 | AC-LMT-001、AC-OBS-003 | M3 已完成（历史） | TC-M3-DEF-001（建流前部分通过，流内授权 Deferred）、TC-M3-USAGE-001 |
| REQ-LMT-002 | AC-LMT-002 | 二期 Deferred | 二期补充；当前不构造 SSE 内 10005 |
| REQ-LMT-003 | AC-LMT-003～005 | 新需求待交付 | @测试回填；必须覆盖边界值 3/4、租户覆盖、跨租户及小时窗废弃 |
| REQ-QUOTA-001 | AC-QUOTA-004～009 | 新需求待交付 | @测试回填；必须覆盖生成前失败、生成后失败、停止、断流、幂等、重试与并发 |
| REQ-QUOTA-002 | AC-QUOTA-001～003、AC-QUOTA-009、AC-QUOTA-015 | 新需求待交付 | @测试回填；必须覆盖 50/51、gift/redbook、跨日、DST、并发与配置变更 |
| REQ-QUOTA-003 | AC-QUOTA-010、AC-QUOTA-013、AC-QUOTA-016 | 新需求待交付 | @测试回填；接口、SSE/发送更新、页面恢复、主体切换与越权 |
| REQ-QUOTA-004 | AC-QUOTA-010～013 | 新需求待交付 | @测试回填；首页/详情展示、QPM/限额区分、用尽禁发与匿名 |
| REQ-QUOTA-005 | AC-LMT-004、AC-QUOTA-014～015 | 新需求待交付 | @测试回填；数据库默认、租户部分覆盖、非法配置和生效时间 |

### 4.1 M3 交付清单与验证方式（含 M2-min）

> 标记“无管理 UI”的条目只能采用数据核验 + 接口/离线工具实测，并辅以单测或集成测试；不存在 `/admin/*`、`/platform/*` 页面不是缺陷。

| REQ-ID | 优先级 | 里程碑 | 验收标准编号 | 验证方式 | 无管理 UI 说明 |
|---|:---:|:---:|---|---|---|
| REQ-CFG-003 | P0 | M2-min 已完成 | AC-CFG-003、AC-CFG-004 | 单测 + 集成 + 接口实测 + 数据核验 | 是；DBA 改库后校验，非法配置运行时 code=30060 |
| REQ-CFG-004 | P0 | M2-min 已完成 | AC-CFG-005 | 集成 + 接口实测 + 数据核验 | 是；仅平台管理员调用缓存失效接口 |
| REQ-SKL-001 | P1 | M2-min 已完成 | AC-SKL-001 | 单测 + 集成 + 数据核验 | 是；验证不可变版本及 Agent 版本引用 |
| REQ-SKL-002 | P1 | M3 已完成 | AC-SKL-002、AC-CHAT-007 | 集成 + E2E + 数据核验 | 配置无 UI；终端对话行为可 E2E |
| REQ-MCP-001 | P0 | M2-min 已完成 | AC-MCP-001 | 单测 + 集成 + 接口实测 + 数据核验 | 是；DB 写密文，连接测试走接口 |
| REQ-MCP-002 | P0 | M2-min 已完成 | AC-MCP-002 | 集成 + 接口实测 + 数据核验 | 是；发现和逐项授权通过 DB/接口验证 |
| REQ-MCP-003 | P0 | M3 已完成 | AC-MCP-003～006、AC-CHAT-007 | 单测 + 集成 + E2E + 数据核验 | 配置无 UI；Mock MCP 全链路可 E2E |
| REQ-MCP-004 | P0 | M2-min 已完成 | AC-MCP-007 | 单测 + 离线工具实测 + 数据核验 | 是；验证 AES-GCM 密文及明文零泄露 |
| REQ-TOL-001 | P0 | M2-min 已完成 | AC-TOL-001 | 集成 + 接口实测 + 数据核验 | 是；平台注册和租户授权均直接写库 |
| REQ-TOL-002 | P0 | M3 已完成 | AC-TOL-002、AC-TOL-003、AC-CHAT-007 | 单测 + 集成 + E2E + 数据核验 | 本地 Tool 仅 low；高风险确认由 Mock MCP 生产路径验证 |
| REQ-CHAT-003 | P0 | M3 已完成 | AC-CHAT-007、AC-MCP-003、AC-TOL-002、AC-TOL-003 | 集成 + E2E + 数据核验 | 已覆盖绑定快照、invoke 前撤权及 invoke 后残余窗口 |
| REQ-CHAT-005 | P0 | M3 已完成 | AC-CHAT-008、AC-CFG-004 | 单测 + 集成 | 预算-1/等于通过，+1 code=30060；不截断 |
| REQ-RBAC-001 | P0 | M2-min 已完成 | AC-RBAC-001～003 | 单测 + 接口扫描 + 集成 | 是；验证 `@TenantRole` 覆盖及平台接口准入 |
| REQ-AUD-001 | P0 | M2-min 已完成 | AC-AUD-001～003 | 集成 + 接口实测 + 数据核验 | 是；直接核验安全事件审计数据 |
| REQ-OBS-001 | P1 | M3 已完成 | AC-OBS-001、AC-OBS-002 | 单测 + 集成 + 数据核验 | 配置异常 accepted=0，隐私 fail-closed；无管理查询 UI |
| REQ-LMT-001 | P1 | M3 已完成 | AC-LMT-001、AC-OBS-003 | 单测 + 集成 + 接口实测 | 建流前 10005 + retryAfterSeconds 已通过；流内与非零用量 Deferred |

### 4.2 横向基线用例（不隶属单一 REQ）

| 基线 AC | 里程碑 | 测试用例 ID |
|---|:---:|---|
| AC-API-001、AC-API-002 | M1 | TC-M1-API-001～005 |
| AC-NFR-001（性能红线） | M1 | TC-M1-PERF-001～004 |
| AC-NFR-002（安全与日志） | M1 | TC-M1-SEC-005、TC-M1-TEN-012、TC-M1-TEN-015、TC-M1-UI-013 |
| AC-NFR-003（响应式与可访问性） | M1 | TC-M1-UI-004～011 |
| AC-NFR-004（站点级非 200 例外） | M1 | TC-M1-TEN-003～005、TC-M1-API-006、TC-M1-UI-012 |

---

## 5. 验收标准反向索引

### 5.1 M1 AC 索引

| AC-ID | 覆盖主题 | 关联 REQ |
|---|---|---|
| AC-TEN-001 | dev host 两租户识别 | REQ-TEN-001 |
| AC-TEN-002 | 伪造 tenantId 无效 | REQ-TEN-002 |
| AC-TEN-003 | 租户内唯一性 | REQ-TEN-003 |
| AC-TEN-004 | 跨租户资源不存在 | REQ-TEN-003 |
| AC-TEN-005 | 全链路租户维度完整 | REQ-TEN-003 |
| AC-TEN-006 | 租户暂停阻断 | REQ-TEN-004 |
| AC-TEN-007 | 生产关闭 dev host 映射 | REQ-TEN-001 |
| AC-AUTH-001 | SSO 整页跳转 | REQ-AUTH-001、REQ-AUTH-002 |
| AC-AUTH-002 | Token 入本地并清 URL | REQ-AUTH-001 |
| AC-AUTH-003 | Header 鉴权与续期回写 | REQ-AUTH-003 |
| AC-AUTH-004 | 20000～20005 清退重登 | REQ-AUTH-004 |
| AC-AUTH-005 | 多租户惰性建户 | REQ-AUTH-003 |
| AC-AUTH-006 | 禁用成员不自动恢复 | REQ-AUTH-003 |
| AC-AUTH-007 | eyesUser ADMIN 平台管理员认定 | REQ-AUTH-003 |
| AC-AUTH-008 | 前端退出，无后端 logout | REQ-AUTH-004 |
| AC-CFG-001 | 租户站点配置生效 | REQ-CFG-001 |
| AC-AGT-001 | 已发布启用 Agent 可用 | REQ-AGT-004 |
| AC-CON-001 | SSO 后原租户恢复草稿 | REQ-CON-001、REQ-AUTH-002 |
| AC-CON-002 | 首次发送与幂等 | REQ-CON-001 |
| AC-CON-003 | 会话隔离分页 | REQ-CON-002 |
| AC-CON-004 | 标题与删除 | REQ-CON-002、REQ-CHAT-004 |
| AC-CHAT-001 | 流式最终一致 | REQ-CHAT-001、REQ-CHAT-004 |
| AC-CHAT-002 | 停止生成 | REQ-CHAT-002 |
| AC-CHAT-003 | 重新生成不重复 | REQ-CHAT-002 |
| AC-CHAT-004 | 输入校验 | REQ-CHAT-001 |
| AC-CHAT-005 | Markdown 安全 | REQ-CHAT-001 |
| AC-CHAT-006 | 模型故障隔离 | REQ-CHAT-001 |
| AC-API-001 | HTTP 200、code、timestamp | 全部 M1 API 需求 |
| AC-API-002 | 分页与 camelCase | REQ-CON-002 及全部分页 API |

### 5.2 M2 Deferred（二期）AC 索引

| AC-ID | 覆盖主题 | 关联 REQ |
|---|---|---|
| AC-CFG-002 | 二期配置发布与回滚 | REQ-CFG-002 |
| AC-AGT-002 | 二期 Agent 管理与默认项 | REQ-AGT-001、REQ-AGT-003 |
| AC-AGT-003 | 二期 Agent 发布治理 | REQ-AGT-002 |
| AC-AGT-004 | 二期跨租户绑定失败 | REQ-AGT-002 |
| AC-ADM-001 | 二期后台状态与冲突 | REQ-ADM-001、REQ-CFG-002、REQ-AGT-001 |
| AC-ADM-002 | 二期完整管理后台；一期页面/入口不存在 | REQ-ADM-001、REQ-AGT-001、REQ-SKL-003、REQ-MCP-005、REQ-TOL-003 |
| AC-RBAC-004 | 二期前端权限渲染 | REQ-RBAC-002 |
| AC-AUD-004 | 二期管理操作审计 | REQ-AUD-002 |
| AC-TOL-004 | 真实有副作用本地 Tool 全治理链路 | REQ-TOL-004 |
| AC-LMT-002 | SSE 流内限流合法收敛 | REQ-LMT-002 |

### 5.3 M2-min AC 索引

| AC-ID | 覆盖主题 | 关联 REQ |
|---|---|---|
| AC-SKL-001 | Skill 变量校验、不可变版本与 Agent 引用 | REQ-SKL-001 |
| AC-MCP-001 | AES-GCM 密文与明文零回显 | REQ-MCP-001 |
| AC-MCP-002 | MCP 发现、默认禁用与逐项授权 | REQ-MCP-002 |
| AC-MCP-007 | 凭据离线加密工具 | REQ-MCP-004 |
| AC-TOL-001 | 平台 Tool 注册与租户授权后端数据 | REQ-TOL-001 |
| AC-RBAC-001 | M3 接口逐角色准入 | REQ-RBAC-001 |
| AC-RBAC-002 | 平台/租户角色来源 | REQ-RBAC-001 |
| AC-RBAC-003 | `@TenantRole` 覆盖与缓存接口平台权限 | REQ-RBAC-001 |
| AC-AUD-001 | 运行时安全审计完整与不可篡改 | REQ-AUD-001 |
| AC-AUD-002 | 安全审计敏感值摘要 | REQ-AUD-001 |
| AC-AUD-003 | 指定运行时安全事件全覆盖 | REQ-AUD-001、REQ-CFG-004 |
| AC-CFG-003 | 独立配置校验入口 | REQ-CFG-003 |
| AC-CFG-004 | 运行时非法配置 code=30060 | REQ-CFG-003 |
| AC-CFG-005 | 平台缓存失效接口 | REQ-CFG-004 |

### 5.4 M3 AC 索引

| AC-ID | 覆盖主题 | 关联 REQ |
|---|---|---|
| AC-SKL-002 | Skill 版本化运行时消费 | REQ-SKL-002 |
| AC-MCP-003 | DB 配置后 Mock MCP 两种传输完整链路 | REQ-MCP-003、REQ-CHAT-003 |
| AC-MCP-004 | 连接、发现、每次调用前 SSRF 拒绝 | REQ-MCP-003 |
| AC-MCP-005 | 新工具默认禁用与 DB 逐项授权 | REQ-MCP-003 |
| AC-MCP-006 | Mock MCP 故障覆盖 | REQ-MCP-003 |
| AC-TOL-002 | MCP 高风险默认 high、逐次确认；Mock MCP 验证副作用阻断 | REQ-TOL-002、REQ-CHAT-003 |
| AC-TOL-003 | Mock MCP 非幂等副作用不自动重试 | REQ-TOL-002、REQ-CHAT-003 |
| AC-CHAT-007 | 生成期绑定快照、invoke 前授权复查与 invoke 后残余窗口 | REQ-SKL-002、REQ-MCP-003、REQ-TOL-002、REQ-CHAT-003 |
| AC-CHAT-008 | system 提示预算超限拒绝、不截断 | REQ-CHAT-005、REQ-CFG-003 |
| AC-OBS-001 | 埋点隔离、隐私与合法配置采集 | REQ-OBS-001 |
| AC-OBS-002 | 埋点配置异常 fail-closed | REQ-OBS-001 |
| AC-OBS-003 | 一期 rateLimitedCount 恒 0；二期后端权威事件 | REQ-OBS-002、REQ-LMT-001 |
| AC-LMT-001 | 历史仅建流前租户级消息限流 | REQ-LMT-001 |

### 5.5 用户限流与每日限额 AC 索引

| AC-ID | 覆盖主题 | 关联 REQ |
|---|---|---|
| AC-LMT-003 | 数据库默认 QPM=3、边界 3/4、10005 与等待恢复 | REQ-LMT-003 |
| AC-LMT-004 | 租户 QPM 覆盖与 gift/redbook 隔离 | REQ-LMT-003、REQ-QUOTA-005 |
| AC-LMT-005 | 小时窗废弃、只做建流前准入 | REQ-LMT-003 |
| AC-QUOTA-001 | 数据库默认 50 次/日与第 51 次独立业务拒绝 | REQ-QUOTA-002 |
| AC-QUOTA-002 | 同一 uid 跨租户额度隔离 | REQ-QUOTA-002、REQ-QUOTA-003 |
| AC-QUOTA-003 | 租户当地零点、跨日与 DST | REQ-QUOTA-002 |
| AC-QUOTA-004 | 生成前失败释放、QPM 不回退 | REQ-QUOTA-001 |
| AC-QUOTA-005 | 生成后完成/失败/断流均结算 | REQ-QUOTA-001 |
| AC-QUOTA-006 | 主动停止计数边界 | REQ-QUOTA-001 |
| AC-QUOTA-007 | Idempotency-Key 不重复消息与扣量 | REQ-QUOTA-001、REQ-CHAT-002 |
| AC-QUOTA-008 | 重试/重新生成按新尝试计数 | REQ-QUOTA-001、REQ-CHAT-002 |
| AC-QUOTA-009 | 最后额度并发不超额 | REQ-QUOTA-001、REQ-QUOTA-002 |
| AC-QUOTA-010 | 首页/详情展示及权威同步 | REQ-QUOTA-003、REQ-QUOTA-004 |
| AC-QUOTA-011 | 用尽保留输入、禁用发送及提额恢复 | REQ-QUOTA-004 |
| AC-QUOTA-012 | QPM 与日限额文案/状态区分 | REQ-LMT-003、REQ-QUOTA-004 |
| AC-QUOTA-013 | 匿名不展示、主体切换清旧快照 | REQ-QUOTA-003、REQ-QUOTA-004 |
| AC-QUOTA-014 | 缺失/非法配置禁止代码兜底 | REQ-QUOTA-005 |
| AC-QUOTA-015 | 配置覆盖、生效时间与上下调行为 | REQ-QUOTA-002、REQ-QUOTA-005 |
| AC-QUOTA-016 | 当前租户当前用户隐私与脱敏观测 | REQ-QUOTA-003 |

### 5.6 全局非功能 AC 索引

| AC-ID | 覆盖主题 | 适用范围 |
|---|---|---|
| AC-NFR-001 | 性能与零跨租户泄露 | M1/M2-min/M3；二期 M2 恢复后同样适用 |
| AC-NFR-002 | Host、越权、缓存、XSS、Token 泄露、SSRF 安全 | M1/M2-min/M3；二期 M2 恢复后同样适用 |
| AC-NFR-003 | 浏览器、桌面、平板、移动兼容性 | M1/M3 终端页面；二期 M2 管理 UI 恢复后适用 |
| AC-NFR-004 | 页面级非 200 与业务 API HTTP 200 边界 | M1/M2-min/M3；二期 M2 恢复后同样适用 |

---

## 6. 异常场景反向覆盖

| EX-ID | 主要关联 REQ |
|---|---|
| EX-001 | REQ-TEN-001 |
| EX-002 | REQ-TEN-004 |
| EX-003 | REQ-TEN-002 |
| EX-004 | REQ-TEN-003、REQ-CON-002 |
| EX-005 | REQ-CFG-002、REQ-AGT-002、REQ-SKL-001 |
| EX-006 | REQ-TEN-003、REQ-TEN-004、REQ-CFG-001 |
| EX-007 | REQ-AUTH-001、REQ-AUTH-002 |
| EX-008 | REQ-AUTH-001、REQ-AUTH-002 |
| EX-009 | REQ-AUTH-003、REQ-CON-001、REQ-RBAC-001 |
| EX-010 | REQ-AGT-003、REQ-AGT-004 |
| EX-011 | REQ-AGT-002、REQ-AGT-004、REQ-SKL-002 |
| EX-012 | REQ-CFG-002、REQ-AGT-001、REQ-AGT-002、REQ-AGT-003、REQ-SKL-001 |
| EX-013 | REQ-CON-001、REQ-CHAT-002 |
| EX-014 | REQ-CHAT-001、REQ-CHAT-002、REQ-CHAT-004 |
| EX-015 | REQ-CHAT-001、REQ-CHAT-002、REQ-CHAT-004 |
| EX-016 | REQ-SKL-002、REQ-MCP-001、REQ-MCP-002、REQ-MCP-003、REQ-CHAT-003 |
| EX-017 | REQ-MCP-002、REQ-MCP-003、REQ-CHAT-003 |
| EX-018 | REQ-TOL-001、REQ-TOL-002、REQ-CHAT-003 |
| EX-019 | REQ-TOL-001、REQ-TOL-002、REQ-CHAT-003 |
| EX-020 | REQ-CHAT-001 |
| EX-021 | REQ-LMT-001、REQ-LMT-003 |
| EX-022 | REQ-CON-002、REQ-CHAT-002 |
| EX-023 | REQ-CON-002 |
| EX-024 | REQ-CFG-004、REQ-RBAC-001、REQ-AUD-001 |
| EX-025 | REQ-TEN-003、REQ-MCP-001、REQ-MCP-004、REQ-AUD-001、REQ-OBS-001 |
| EX-026 | REQ-AUTH-004 |
| EX-027 | REQ-AUTH-003、REQ-AUTH-004 |
| EX-028 | REQ-TEN-001 |
| EX-029 | REQ-MCP-003、REQ-CHAT-003 |
| EX-030 | REQ-MCP-003、REQ-CHAT-003 |
| EX-031 | REQ-CFG-003、REQ-SKL-001、REQ-SKL-002、REQ-MCP-003、REQ-TOL-001、REQ-CHAT-003、REQ-CHAT-005 |
| EX-032 | REQ-CFG-003、REQ-MCP-001、REQ-MCP-004 |
| EX-033 | REQ-CFG-004、REQ-AUD-001 |
| EX-034 | REQ-OBS-001 |
| EX-035 | REQ-CHAT-005、REQ-CFG-003 |
| EX-036 | REQ-TOL-002、REQ-TOL-004、REQ-CHAT-003 |
| EX-037 | REQ-CHAT-003 |
| EX-038 | REQ-LMT-001、REQ-LMT-002 |
| EX-039 | REQ-OBS-002、REQ-LMT-001 |
| EX-040 | REQ-QUOTA-002、REQ-QUOTA-004 |
| EX-041 | REQ-LMT-003、REQ-QUOTA-001、REQ-QUOTA-002 |
| EX-042 | REQ-QUOTA-001 |
| EX-043 | REQ-QUOTA-001 |
| EX-044 | REQ-QUOTA-001、REQ-CHAT-002 |
| EX-045 | REQ-QUOTA-001、REQ-CHAT-002 |
| EX-046 | REQ-QUOTA-005 |
| EX-047 | REQ-QUOTA-003、REQ-QUOTA-004 |
| EX-048 | REQ-QUOTA-002、REQ-QUOTA-003、REQ-QUOTA-004 |
| EX-049 | REQ-QUOTA-002、REQ-QUOTA-005 |

---

## 7. 里程碑签署入口条件

| 里程碑 | 进入签署的必要条件 |
|---|---|
| M1 | 已有条件通过；上线前仍须在内网复验真实 eyesUser Thrift 互通与伪造 Token 拒绝 |
| M2 Deferred（二期） | 一期不进入签署；二期恢复后，全部管理后台、前端权限渲染和管理操作审计 AC 通过，再由六位专家独立签署 |
| M3（含 M2-min） | **已满足**：REQ/AC 覆盖 100%；65 条为 59 通过 + 6 授权 Deferred + 0 失败；无未关闭 P0/P1、无高危安全问题；无管理 UI 能力已用单测、集成、接口实测、离线工具和数据核验完成 |
| 用户对话限流与日限额 | REQ-LMT-003、REQ-QUOTA-001～005 全部完成；AC-LMT-003～005、AC-QUOTA-001～016 全部有测试结论且无失败；跨租户、并发、跨日、幂等、失败/停止/重试、前端与隐私用例 100% 通过 |

M3 由产品经理专家、架构设计师、UI 设计师、前端开发专家、后端开发专家、测试工程师六位分别签署；@产品经理专家已于 2026-08-13 签署。Deferred 的 M2 不计入一期 M3 缺陷，但二期恢复后仍须由固定六位独立签署。

### 7.1 二期必须补做（业务优先级顺序）

| 顺序 | REQ | 必须补做 | 一期缺口 |
|---:|---|---|---|
| 1 | REQ-ADM-001、REQ-CFG-002、REQ-AGT-001～003、REQ-SKL-003、REQ-MCP-005、REQ-TOL-003 | 管理后台全量：租户/域名、站点配置、Agent、Skill、MCP、本地 Tool、成员角色、审计查询 | 一期全部由 DBA/开发改库与接口维护 |
| 2 | REQ-RBAC-002 | RBAC 前端菜单、页面、操作级渲染 | 一期仅后端准入，无管理端可见性治理 |
| 3 | REQ-AUD-002 | 管理操作审计及查询 | 一期仅运行时安全事件审计 |
| 4 | REQ-TOL-004 | 至少一个真实有副作用本地 Tool 的完整治理 | 一期本地 Tool 仅两个 low 工具，副作用由 Mock MCP 验证 |
| 5 | REQ-LMT-002 | SSE 流内限流 | 一期只在建流前限流 |
| 6 | REQ-OBS-002 | `rateLimitedCount` 权威数据源 | 一期恒 0；二期采用后端侧写 `messageRateLimited`，不采用前端上报 |
| 7 | M2 配置治理增强 | system 提示预算预览、埋点配置预检/告警、绑定生效时点提示 | 一期依赖后端 fail-closed 和运维手册 |

---

## 8. M3 口径追认登记

| # | 结论 | 需求状态 / 验收口径 | 二期 Deferred |
|---:|---|---|---|
| 1 | 追认：一期本地 Tool 仅 `datetime_now`、`calculator` | REQ-TOL-001/002 已完成；AC-TOL-001/002 按 low 本地工具 + Mock MCP 验证 | REQ-TOL-004 |
| 2 | 追认：高风险生产路径由 MCP 承载，缺省 `riskLevel=high` | AC-TOL-002、AC-CHAT-007 已完成 | 管理 UI 展示/校验风险等级 |
| 3 | 追认：真实副作用场景一期由 Mock MCP 承担 | AC-TOL-002/003 验证方式修订，原安全意图不变 | 真实副作用本地 Tool 与真实外部接入复验 |
| 4 | 追认：埋点配置异常 fail-closed | REQ-OBS-001、AC-OBS-002 已完成；允许运营数据缺口 | 管理配置预检与异常告警 |
| 5 | 追认：system 提示默认 100,000 字符，超限拒绝不截断 | REQ-CHAT-005、AC-CHAT-008 已完成 | 管理端预算预览与超限定位 |
| 6 | 追认：invoke 后撤权本次可完成 | REQ-CHAT-003、AC-CHAT-007 已完成；下一次必须拒绝 | 无；若要求即时中断须另立项并重开 ADR-008 |
| 7 | 追认：能力解绑下一次提问生效 | REQ-CHAT-003、AC-CHAT-007 已完成；立即阻断用撤 `granted`/停 `status` | 运维手册与管理端生效时点提示 |
| 8 | 追认：一期无 SSE 流内限流 | REQ-LMT-001 已完成；AC-LMT-001 只验建流前 | REQ-LMT-002、AC-LMT-002 |
| 9 | 修正：一期 `rateLimitedCount=0`；二期选择后端权威事件 | AC-OBS-003 一期反向断言已完成，不得伪造 | REQ-OBS-002：后端侧写 `messageRateLimited`，不采用前端上报 |
| 10 | 追认：ADMIN 在 eyes-auth 开启返 20000，测试关闭返 10003 | REQ-RBAC-001、AC-RBAC-001～003 已完成；HTTP 200 + 数字 code 不变 | 无 |

---

## 9. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| V1.2 | 2026-08-13 | M2 管理后台全量标记 Deferred（二期）；M2-min 并入 M3；新增 REQ-CFG-003、REQ-CFG-004、REQ-SKL-003、REQ-MCP-004、REQ-MCP-005、REQ-TOL-003、REQ-RBAC-002、REQ-AUD-002；重划 M3 AC 与无 UI 验证方式 |
| V1.3 | 2026-08-13 | M3 终验状态同步；追认一期本地 Tool 清单、MCP 默认 high 与 Mock 副作用验证、埋点 fail-closed、system 提示护栏、invoke 残余窗口、生成期绑定快照、建流前限流、rateLimitedCount 恒 0；新增已完成 REQ-CHAT-005；新增二期 REQ-ADM-001、REQ-TOL-004、REQ-OBS-002、REQ-LMT-002；二期 rateLimitedCount 采用后端权威事件方案 |
| V1.4 | 2026-08-14 | 新增 REQ-LMT-003、REQ-QUOTA-001～005 与 AC-LMT-003～005、AC-QUOTA-001～016；数据库默认 QPM=3、日限额=50，平台默认+租户覆盖，租户时区零点重置，小时窗废弃，明确计数/幂等/前端/隐私边界 |
