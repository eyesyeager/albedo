# Albedo · 多租户 AI 问答系统

统一产品底座，以三级域名区分租户（如 `albedo-gift.eyescode.top` → `gift`），提供租户识别、耶瞳 SSO、站点配置、Agent 对话、管理治理与工具编排的完整闭环。

| 文档 | 说明 |
|---|---|
| `docs/prd.md` | 产品需求（唯一产品基线） |
| `docs/requirements.md` | 需求清单与覆盖矩阵 |
| `docs/architecture.md` | 技术架构（唯一技术基线） |
| `docs/api-spec.md` | **接口契约 + 错误码登记表（唯一契约来源）** |
| [`docs/spec.md`](docs/spec.md) | 需求规格速览（由 PRD 简化） |

---

## 1. 技术栈

| 端 | 技术 |
|---|---|
| 前端 | Vue 3 + TypeScript + Pinia + Vite 5 + TailwindCSS 3.4.17 + Element Plus（纯静态产物） |
| 后端 | Java 17 + Spring Boot 3.3.4 + Spring Data JPA + Hibernate 6（**单体单一 JAR**） |
| 存储 | MySQL 8.0（库 `albedo`）+ Redis 8.6.3 |
| 鉴权 | 耶瞳用户中心 SSO（`eyesAuth-spring-boot-starter:1.1.0`，`appId=albedo`，`auth-type=1`） |
| AI | OpenAI 兼容接口（混元），JDK17 `HttpClient` 消费 SSE |

---

## 2. 环境要求

- JDK 17、Maven 3.9+
- Node.js ≥ 20.19、npm ≥ 10
- 可访问 MySQL / Redis / eyesUser Thrift（地址见 `application.yml`）/ 混元接口

---

## 3. 启动步骤

### 3.1 后端

```bash
cd backend

# ① 准备配置：真实配置不入 Git，从模板复制后填入凭据
cp src/main/resources/application-example.yml src/main/resources/application.yml
#    需填：spring.datasource.*、spring.data.redis.*、eyes-auth.thrift.host、app.ai.api-key、app.crypto.secret

# ①' 单测复用真实 MySQL/Redis，同样需从模板复制测试配置
cp src/test/resources/application-test-example.yml src/test/resources/application.yml

# ② 编译与单测
mvn -q clean compile
mvn -q test

# ③ 启动（⚠️ 前置：数据库表必须已存在，见 §4）
#    🔴 必须带 -Dnetworkaddress.cache.ttl=10（见下方说明，SSRF 防护的一部分）
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dnetworkaddress.cache.ttl=10"
# 或
mvn -q -DskipTests package && java -Dnetworkaddress.cache.ttl=10 -jar target/albedo-backend.jar
```

> ⚠️ `spring.jpa.hibernate.ddl-auto: validate` —— 应用**不会自动建表**。表结构未就绪时启动会失败，这是刻意设计（避免线上结构漂移）。

> 🔴 **`-Dnetworkaddress.cache.ttl=10` 是必需启动参数，不是可选优化**（`docs/architecture.md` ADR-009 / api-spec §7.6.3）：
> MCP 的 SSRF 防护采用「调用前解析域名 → 逐 IP 校验 → **以原域名发起 HTTPS 连接**（保住 TLS 主机名校验）」的等价实现。
> 该参数把 JVM 的 DNS 缓存压到 10 秒，使"校验时解析到的 IP"与"实际连接时使用的 IP"高度一致，
> 把 DNS 重绑定（TOCTOU）窗口收敛到 ≤10s；再配合「**每次调用前都重新校验**」，攻击者必须在每一次调用上重新赢得这 10 秒竞态。
> 🔴 不加该参数不会报错，但 SSRF 残余风险会显著放大（JVM 默认对成功解析**永久缓存**）。`StartupChecker` 会在缺少该参数时打印 WARN 提示。

### 3.2 前端

```bash
cd frontend
cp .env.example .env # 首次准备环境变量（VITE_SSO_URL / VITE_SSO_CLIENT_ID），真实 .env 不入库
npm install
npm run dev          # http://localhost:5173（dev proxy 到 127.0.0.1:8080，保留原始 Host）
npm run typecheck    # vue-tsc 类型检查
npm run build        # 产出纯静态 dist/
npm run test:unit    # Vitest
npx playwright install --with-deps   # 首次运行 E2E 前
npm run test:e2e     # Playwright
```

---

## 4. 数据库初始化（@后端 负责）

1. DDL 的**唯一来源**是 `docs/architecture.md §13.3`（M1 全量建表语句）。
2. 由 @后端 通过 **MySQL MCP** 在库 `albedo` 中实执行；禁止用 `ddl-auto: update` 代替。
3. 同时插入 `sys_config` 初始化数据（键名清单见 `docs/architecture.md §13.3` 表格）与 M1 验收数据（`gift`、`redbook` 两租户 + 已发布站点配置 + 已发布 Agent）。
4. 实体与表结构任何变更都必须同步更新 `architecture.md §13.3` 并广播 @架构师 + @测试。

---

## 5. 本地多租户 Host 映射验证（M1 主验证路径）

生产三级域名尚未就绪，M1 通过 `sys_config` 的 dev Host 映射验证租户隔离。

### 5.1 开启映射（仅非生产环境）

```sql
-- 开关（🔴 生产必须为 false，否则应用启动直接失败：DevHostMappingGuard）
INSERT INTO sys_config (config_group, config_key, config_value, value_type, description, is_frontend)
VALUES ('tenant', 'dev_host_mapping_enabled', 'true', 'BOOLEAN', 'dev Host 映射开关（仅非生产）', 0)
ON DUPLICATE KEY UPDATE config_value = 'true';

-- 映射表：key 为「带端口的原始 Host」，value 为租户号
INSERT INTO sys_config (config_group, config_key, config_value, value_type, description, is_frontend)
VALUES ('tenant', 'dev_host_mapping',
        '{"localhost:5173":"gift","127.0.0.1:5173":"redbook","localhost:8080":"gift","127.0.0.1:8080":"redbook"}',
        'JSON', 'dev Host → 租户号映射', 0)
ON DUPLICATE KEY UPDATE config_value = VALUES(config_value);
```

> 改完配置后，管理端应调用 `ConfigService.evict(...)` 失效缓存；本地可直接重启后端或等待缓存 TTL（10 分钟）。

### 5.2 验证方式

| 方式 | 命令 / 操作 | 期望 |
|---|---|---|
| 浏览器（gift） | 打开 `http://localhost:5173` | 站点标题/欢迎语来自 `gift` 的已发布配置 |
| 浏览器（redbook） | 打开 `http://127.0.0.1:5173` | 站点标题/欢迎语来自 `redbook` 的已发布配置 |
| curl 指定 Host（gift） | `curl -s -H 'Host: localhost:5173' http://127.0.0.1:8080/api/v1/site/config` | `code=0`，`data.tenantId=gift` |
| curl 指定 Host（redbook） | `curl -s -H 'Host: 127.0.0.1:5173' http://127.0.0.1:8080/api/v1/site/config` | `code=0`，`data.tenantId=redbook` |
| 未知 Host（业务 API） | `curl -s -H 'Host: unknown.example.com' http://127.0.0.1:8080/api/v1/site/config` | **HTTP 200** + `code=30010` |
| 未知 Host（站点级页面） | `curl -i -H 'Host: unknown.example.com' http://127.0.0.1:8080/site/status` | **HTTP 404**（唯一允许非 200 的端点） |
| 伪造 tenantId 无效 | `curl -s -H 'Host: 127.0.0.1:5173' 'http://127.0.0.1:8080/api/v1/site/config?tenantId=gift'` | 仍返回 `redbook`，后端记 `[SECURITY]` 日志 |
| 跨租户资源 | 用 gift 的 `conversationId` 在 redbook Host 访问 | HTTP 200 + `code=10004`（不暴露 gift 存在） |

> 生产环境使用真实 Host：在 `tenant_domains` 中绑定 `albedo-gift.eyescode.top` → `gift`、`albedo-redbook.eyescode.top` → `redbook`；若经过 Nginx/CLB，需把 `sys_config: tenant.trust_forwarded_host` 置为 `true` 并确保代理写入 `X-Forwarded-Host`。

> 🔴 **前后端分域名部署**（`albedo-{tenant}.eyescode.top` 前端 + `api-albedo-{tenant}.eyescode.top` 后端）：`tenant_domains` 必须为每个租户**同时写入两条**绑定（前端域名 + API 域名，`is_primary=1` 仅主域名，API 域名 `is_primary=0`），且写库后必须成对失效「Host 键 + 租户号键」缓存，否则后端拿到 `api-albedo-*` 的 Host 无法识别租户。前端 `request.ts` 会在运行时把 `albedo-{tenant}` 推导为 `api-albedo-{tenant}`（`VITE_API_DOMAIN` 留空即可）。

---

## 6. 配置放置规则（🔴 反硬编码红线）

| 内容 | 位置 |
|---|---|
| 数据库/Redis/AI/Thrift 凭据、线程池、CORS、日志 | `backend/src/main/resources/application.yml`（不提交） |
| 平台业务参数（分页默认值、消息长度上限、限流阈值、Host 开关、dev 映射…） | MySQL `sys_config`（前端经 `GET /api/v1/sys-config` 消费） |
| 租户品牌与站点文案（标题、Logo、欢迎语、占位符、页脚、主题色） | MySQL `site_config_versions`（前端经 `GET /api/v1/site/config` 消费） |
| 静态 UI 文案（"发送"、"取消"、"暂无内容"…） | `frontend/src/locales/zh-CN.ts` |
| 颜色/间距/圆角/阴影/动效时长 | `frontend/src/styles/tokens.css`（变量名为契约，值由 @UI 填） |
| 前端地址类变量（API 域名、SSO 地址与 clientId） | `frontend/.env`（🔴 不得放密钥；真实文件不入库，模板见 `.env.example`） |

```
❌ 禁止：Java/Vue 代码中出现业务魔法值、内联文案字面量、硬编码色值
❌ 禁止：application.yml 使用 ${ENV_VAR} 占位、引入 KMS/Vault
❌ 禁止：把真实凭据/密钥写入任何会被提交的文件（真实配置仅存本机 application.yml / .env）
```

---

## 7. 鉴权说明（耶瞳 SSO）

```
未登录访问受保护能力
  → 前端整页跳转 {SSO_URL}/OAuth2?clientId={VITE_SSO_CLIENT_ID}&redirectUrl=<当前页>
  → 回跳 ?authorization=<jwt> → main.ts 存 localStorage → 立即 history.replaceState 清 URL
  → 每次请求 Header: authorization
  → 响应头出现新 authorization（auth-type=1 续期）→ 立即回写 localStorage
  → body.code ∈ [20000..20005] → 清 token + 整页跳 SSO
```

```
🔴 本项目不存在：/api/v1/auth/login、/register、/logout、/auth/callback、刷新令牌接口
🔴 前端不存在 /login 路由；退出登录仅前端清 token 后跳 SSO（无后端请求）
```

> ⚠️ 重要实现说明（`docs/architecture.md` ADR-002）：`eyesAuth-spring-boot-starter:1.1.0` 基于 Spring Boot 2.6（`javax.servlet` + `spring.factories`），与 Spring Boot 3 不兼容。本项目复用其注解/常量/Thrift 客户端，鉴权切面改由 `com.eyes.albedo.auth.PermissionAspect`（jakarta 版）实现。
> **@后端 禁止**使用 `com.eyes.eyesAuth.utils.WebHelper` 与 `PermissionAdvice`，也禁止 `@Import(EyesAuthAutoConfiguration.class)`；`@Permission` 与 `UserInfoHolder` 照常使用。

### 7.1 无内网时的本地登录态调试（Mock Thrift 替身）

eyesUser 的 Thrift 端口 **不对公网开放**（接入文档 §2.2「需内网互通」）。在没有内网直连的开发机上，带有效 token 的请求会得到 `code=50002`「鉴权服务暂不可用」，界面表现为「已登录却仍显示登录入口」。

判断依据（后端日志出现下列异常即为此情形，**不是代码 Bug**）：

```
TTransportException: Frame size (1213486160) larger than max length (16384000)!
# 1213486160 = 0x48545450 = ASCII "HTTP"：对端七层网关回了 HTTP 502，被当成了 Thrift 帧长度
```

此时可启用测试替身 `backend/src/test/java/com/eyes/albedo/testsupport/MockEyesAuthServer.java`：

```bash
cd backend
# ① 生成 test classpath（仅首次或依赖变更后需要）
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/albedo-cp.txt -Dmdep.includeScope=test
mvn -q test-compile

# ② 启动替身（默认 19011 端口、appId=albedo）
java -cp "target/test-classes:target/classes:$(cat /tmp/albedo-cp.txt)" \
     com.eyes.albedo.testsupport.MockEyesAuthServer
# 可选：--port=19011 --app-id=albedo --frozen-uids=106（命中返回 20003，用于验证前端清退）

# ③ 后端指向替身（命令行覆盖，不改任何配置文件）
mvn spring-boot:run -Dspring-boot.run.arguments="\
    --eyes-auth.thrift.host=127.0.0.1 --eyes-auth.thrift.port=19011"
```

```
🔴 替身位于 src/test，绝不参与生产打包；生产/预发必须连真实 eyesUser
🔴 替身不校验 JWT 签名，因此只能证明「token 有效时我方行为正确」，
   不能证明「伪造 token 会被拒绝」——该项必须在内网用真实服务复验
🔴 用替身得出的测试结论必须标注「Mock 替身验证」（见 docs/test-report.md §4.1）
```

---

## 8. DBA 运维手册（🔴 一期无管理 UI，配置全靠写库）

> 依据 `docs/architecture.md` **ADR-013**（一期无管理后台下的配置治理）与 PRD **DEC-010**：
> 一期**不提供任何** `/admin/*`、`/platform/*` 管理页面，租户级配置（站点配置、Agent、Skill、MCP、Tool 授权、成员角色）
> 由 DBA/开发人员**直接写库**。这意味着"保存时校验"这个应用层入口**根本不存在**，因此以下人工纪律**不是建议，是契约的一部分**。

### 8.0 🔴 三条前置纪律（在动任何一条 SQL 之前先读）

**① 🔴 承载真实租户数据的环境，必须以 `prod` profile 启动**

| profile | `eyes-auth.enabled` | 后果 |
|---|---|---|
| `prod` | 只能为 `true` | ✅ 唯一允许承载真实租户数据；置 `false` 时**应用启动即失败**（fail-closed，不给"忘了开鉴权"留缝） |
| `dev` / `staging` | 可为 `false` | ⚠️ 切面缺席 → `@Permission(USER)` 层**退化**（登录态由兜底逻辑近似判定）。🔴 **若这些环境跑真实租户数据，等于把鉴权降级**；已登记为二期技术债 `architecture.md` **AR-018 ⑤** |

```
🔴 判定口径：只要库里有真实租户数据 → 必须 prod profile（-Dspring.profiles.active=prod）。
✅ ADMIN 语义端点（/platform/cache/evict、/admin/config/validate 的 localTool 分支）
   已全部做了**程序化兜底**，enabled=false 下也不会静默放行（api-spec §3 事实登记）；
   退化风险集中在 @Permission(USER) 这一层，故不要用 dev/staging 跑真实数据。
```

**② 🔴 紧急止血的正确姿势：撤 `granted` 或把 `status` 置停用 —— 不要"解绑"**

```sql
-- ✅ 正确：撤授权（MCP 工具）→ 🔴 下一次执行即拒（30050 + 审计 tool.grant_denied）
UPDATE mcp_tools SET granted = 0, status = 'disabled'
 WHERE tenant_id = 'gift' AND tool_key = 'crm:lookup_user';

-- ✅ 正确：停用整个 MCP 服务（一刀切，该服务下所有工具立即失效）
UPDATE mcp_servers SET status = 'disabled'
 WHERE tenant_id = 'gift' AND mcp_key = 'crm';

-- ✅ 正确：撤本地 Tool 授权
UPDATE tenant_tool_grants SET granted = 0 WHERE tenant_id = 'gift' AND tool_key = 'xxx';

-- ❌ 错误（止血无效）：DELETE FROM agent_capability_bindings ...
```

| 手段 | 生效时点 | 能否用于紧急止血 |
|---|---|---|
| 撤 `granted` / 置 `status='disabled'`（工具行或 `mcp_servers`） | 🔴 **下一次工具执行**即拒 `30050` | ✅ **唯一正确手段** |
| 解绑 `agent_capability_bindings` | 🔴 **下一次提问**才生效 | ❌ 不可 —— 绑定是**生成期快照** |

```
🔴 为什么解绑不能止血（AR-019，已登记为契约、非缺陷）：
   能力绑定只在「清单构造期」判定一次，并作为**本轮生成的快照**交给模型；
   每次执行前的授权点查**只复查授权/启用列**（granted / status / mcp_servers.status+deleted_at），
   🔴 **不复查绑定**。因此正在进行中的那一轮对话，解绑后剩余轮次**仍可能调用该工具**
   （最长约「确认等待 120s + 剩余轮次 × 单次执行时长」），生效点是**下一次提问**。
✅ 解绑的正确用途：常规下架 / 变更能力清单（可接受下一轮生效），不是应急开关。
```

**③ 🔴 撤授权的残余窗口（AR-017，已登记的已知残余风险，不是缺陷）**

```
撤 granted / 停用 status 之后：
  ✅ **新发起**的每一次工具执行 → 立即 30050（点查 fail-closed，禁缓存、≤1 次查询）
  ⚠️ 已经进入 invoke（网络/本地调用已发出）的**那一次**会**执行完成**：
       · 本地 Tool ≈ 毫秒级
       · MCP ≤ mcp.call_timeout_seconds（默认 30s）
🔴 这是 TOCTOU 的架构必然（点查通过 → invoke 返回之间无法原子化），已登记 AR-017，
   @测试 据此断言而非判缺陷。判据 = "新发起必拒、已进入允许完成"。
🔴 一期**不实现**"执行中撤授权即刻中断"：那需要额外线程/中断机制，违反 ADR-008 第 8 条；
   若业务确需，属二期新需求。
👉 因此对**有副作用的高危工具**，止血后仍应按"最多再发生一次调用"做业务侧兜底核对。
```

### 8.1 🔴 标准操作顺序（三步，缺一即为缺陷）

```
① 改库（UPDATE / INSERT，务必带 WHERE tenant_id = ?）
        ↓
② 跑校验：POST /api/v1/admin/config/validate   ← 等价于"发布前校验"
        ↓
③ 失效缓存：POST /api/v1/platform/cache/evict  ← 把"TTL 内生效"变成"立刻生效"
```

**为什么必须三步**（缺哪一步会怎样，逐条对应 ADR-013 的反证表）：

| 缺失步骤 | 后果 |
|---|---|
| 缺 ② | 非法配置只能由**终端用户在对话中**第一个发现（违反 AC-CFG-003） |
| 缺 ③ | L1/L2 缓存在 TTL 内继续返回旧值，表现为"改了没生效"（M1 缺陷 D-003/D-006 实测踩坑） |
| 两步都做但顺序颠倒 | 先失效缓存再改库 → 中间窗口内旧值被重新加载回缓存，等于没失效 |

**② 校验命令**（也可直接用封装脚本 `backend/scripts/validate-config.sh`）：

```bash
# objectType ∈ agent | agentVersion | skill | skillVersion | mcp | localTool | toolGrant | siteConfig
curl -s -X POST 'http://127.0.0.1:8080/api/v1/admin/config/validate' \
     -H 'Host: albedo-gift.eyescode.top' \
     -H 'authorization: <租户管理员的 jwt>' \
     -H 'Content-Type: application/json' \
     -d '{"objectType":"skillVersion","objectId":"31","includeReferences":true}'
# code=0 → 合法（warnings 不阻断）；code=30060 → data.violations[] 给出字段级失败
```

**③ 缓存失效命令**（🔴 仅平台管理员 `role=ADMIN`，租户角色调用得 `10003`）：

```bash
# scope ∈ tenant | host | sysconfig | agentVersion | all；reason 必填（1~200 字，会进审计）
curl -s -X POST 'http://127.0.0.1:8080/api/v1/platform/cache/evict' \
     -H 'authorization: <平台管理员的 jwt>' \
     -H 'Content-Type: application/json' \
     -d '{"scope":"tenant","tenantId":"gift","reason":"工单 OPS-1234：更新站点文案"}'
# code=0 → data.results[] 给出各作用域实际失效条目数
# code=30061 → data.incompleteScopes[] 列出未完成作用域（🔴 必须重试，不得当成功处理）
```

```
🔴 改 sys_config 后：scope 用 sysconfig（可带 configGroup 限定分组）
🔴 改 tenants / tenant_domains / 站点配置后：scope 用 tenant（Host 键与租户号键会成对失效）
🔴 scope=all 是高危操作：reason 必须写工单号
✅ 以下三类**一期不缓存、直读 MySQL**，改库即生效，**无需**调失效接口：
   Skill 版本快照 / MCP 工具授权（mcp_tools）/ 本地 Tool 授权与能力绑定
   （api-spec §7.1.2 末尾已裁定；这不是漏项，是为了满足 AC-CFG-004「改库即生效」）
🔴 失效接口**不会**删除运行时状态键（chat:idem / chat:cancel / tool:confirm / limit:msg）——
   它们不是缓存，删除会破坏幂等回放、停止生成、工具确认与限流窗口的语义
```

### 8.1.1 域名绑定（🔴 前后端分域名：前端域名 + API 域名成对写入）

前后端分域名部署时，浏览器访问 `albedo-{tenant}.eyescode.top`，API/SSE 请求走 `api-albedo-{tenant}.eyescode.top`。
后端租户识别是「精确匹配 `tenant_domains.host`」，因此每个租户必须**同时存在两条绑定**，否则 API 请求的 Host 无法命中：

```sql
-- 前端站点域名（is_primary=1，主域名）
INSERT INTO tenant_domains (host, tenant_id, is_primary, status)
VALUES ('albedo-gift.eyescode.top', 'gift', 1, 'active');

-- API 域名（is_primary=0，供 api-albedo-{tenant} 的 Host 精确匹配）
INSERT INTO tenant_domains (host, tenant_id, is_primary, status)
VALUES ('api-albedo-gift.eyescode.top', 'gift', 0, 'active');
```

```
🔴 两条必须成对写入，缺 API 域名 → 前端所有 API 调用返回 30010（TENANT_NOT_FOUND）
🔴 host 必须是规范化值：小写、无端口、无末尾点（uk_host 全局唯一）
🔴 写库后按 §8.1 第 ③ 步失效缓存（scope=tenant），Host 键与租户号键成对失效
🔴 新增租户时同样成对写入，禁止只写前端域名
```

### 8.2 MCP 凭据加密（🔴 明文永不进生产、永不进 SQL）

依据 **ADR-012**：密文只能由 `src/test` 下的**离线工具**产出（生产 JAR 里根本没有这个类，
也**绝不提供**任何"提交明文换密文"的 HTTP 端点 —— 那等于在生产暴露一个明文入口）。

```bash
cd backend
mvn -q test-compile
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test

# 🔴 明文只从 stdin 读（不进 shell history、不进 ps 输出）
#    第 1 行 = 凭据明文，第 2 行 = application.yml 里的 app.crypto.secret
printf '%s\n%s\n' 'sk-live-xxxxxxxxxxxx' 'your-app-crypto-secret' \
  | java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
         com.eyes.albedo.tools.McpCredentialEncryptTool --tenant gift --mcp-key crm

# 输出（🔴 仅三项，绝不含明文）：
# credential_cipher=v1:AbCd...:EfGh...
# credential_last4=xxxx
# credential_key_version=1
```

把输出**原样**粘进 SQL（🔴 SQL 中不含明文，故 binlog 与 SQL 审计日志天然安全）：

```sql
UPDATE mcp_servers
   SET credential_cipher      = 'v1:AbCd...:EfGh...',   -- 离线工具输出的 credential_cipher
       credential_last4       = 'xxxx',                 -- 离线工具输出的 credential_last4
       credential_key_version = 1,                      -- 🔴 一期恒为 1
       credential_updated_at  = UTC_TIMESTAMP(3)
 WHERE tenant_id = 'gift' AND mcp_key = 'crm';          -- 🔴 必须带 tenant_id
```

随后调用连接测试确认生效（该调用**强制写审计** `mcp.connection_test`）：

```bash
curl -s -X POST 'http://127.0.0.1:8080/api/v1/admin/mcp/12/test' \
     -H 'Host: albedo-gift.eyescode.top' -H 'authorization: <租户管理员 jwt>'
# data.result ∈ success | dns_failed | tls_failed | auth_failed | timeout
#              | protocol_incompatible | no_tools_available | ssrf_rejected
# 🔴 ssrf_rejected 是唯一返回 code=30050 的情形（其余均 code=0 + data.result 承载诊断结论）
```

```
🔴 AAD 绑定的副作用（ADR-012，最容易踩的坑）：
   密文与「租户号 + mcp_key」绑定（AAD = "mcp:{tenantId}:{mcpKey}"）。
   因此 tenant_id 或 mcp_key 一旦变更，**必须用新值重新加密并写库**，
   否则解密失败会表现为 30060（校验入口）/ 30052（运行时调用）。
   ✅ 这也是特性：把 A 租户的密文复制到 B 租户会直接解密失败，从加密层杜绝"密文搬运"式越权。
🔴 credential_last4 只供人工核对，禁止用于任何比较/校验/鉴权逻辑；明文 <8 位时一律写 '****'。
🔴 app.crypto.secret 泄露 = 全部凭据泄露：文件权限 600、不提交 Git、不进日志。
```

### 8.3 MCP 工具发现与授权（🔴 新工具默认禁用）

```bash
# ① 发现（先 dryRun 看比对结果，确认无误再落库）
curl -s -X POST 'http://127.0.0.1:8080/api/v1/admin/mcp/12/discover' \
     -H 'Host: albedo-gift.eyescode.top' -H 'authorization: <租户管理员 jwt>' \
     -H 'Content-Type: application/json' -d '{"dryRun":true}'
curl -s -X POST 'http://127.0.0.1:8080/api/v1/admin/mcp/12/discover' \
     -H 'Host: albedo-gift.eyescode.top' -H 'authorization: <租户管理员 jwt>' \
     -H 'Content-Type: application/json' -d '{}'
```

```sql
-- ② 逐个授权（一期无授权接口，由 DBA 写库；🔴 必须逐个，禁止批量 granted=1）
UPDATE mcp_tools
   SET granted = 1, status = 'enabled', granted_by = <你的 uid>, granted_at = UTC_TIMESTAMP(3)
 WHERE tenant_id = 'gift' AND tool_key = 'crm:lookup_user';

-- ③ 绑定到 Agent 版本（🔴 不绑定的工具永不进模型可调用清单）
INSERT INTO agent_capability_bindings
       (tenant_id, agent_version_id, capability_type, ref_id, ref_version, sort_order)
VALUES ('gift', 5, 'mcpTool', <mcp_tools.id>, 1, 0);
```

```
🔴 新发现工具一律 granted=0 + status='disabled'：发现 ≠ 授权。
🔴 schema_changed 的已授权工具会被**自动降级**为 granted=0/disabled 并写审计 ——
   这是为了封堵"先以无害 Schema 拿到授权，再偷换参数"的提权路径。
   看到降级后请**重新审阅新 Schema** 再决定是否重新授权，🔴 不要机械地把 granted 改回 1。
🔴 removed 的工具保留历史行并置 disabled（不物理删除），以保住 tool_calls 的可追溯性。
🔴 risk_level 未知一律 high，租户**不可下调**（该列由平台侧规则给出）。
🔴 撤销授权 / 紧急止血请看 §8.0 ②③：正确姿势是撤 granted 或置 status 停用
   （下一次执行即 30050），**不要**解绑 agent_capability_bindings（下一次提问才生效，AR-019）；
   且已进入 invoke 的那一次会执行完成（≤30s，AR-017）。
```

### 8.4 Skill 与本地 Tool 写库要点

```sql
-- Skill：主体表 + 版本表两张；🔴 已发布版本行不可再改（改历史行会让旧会话行为漂移）
INSERT INTO skills (tenant_id, skill_key, name, description, status, current_version, version)
VALUES ('gift', 'greeter', '问候语', '', 'enabled', 1, 0);
INSERT INTO skill_versions (tenant_id, skill_id, version, instruction, variables_schema,
                            output_constraint, status, published_at)
VALUES ('gift', <skills.id>, 1, '你好 {{city}} 的用户',
        '[{"name":"city","required":true,"defaultValue":"深圳"}]', '', 'published', UTC_TIMESTAMP(3));

-- 绑定：🔴 ref_version 必须是**精确版本号**，禁止 0 / 负数（"最新"语义已被明令禁止）
INSERT INTO agent_capability_bindings
       (tenant_id, agent_version_id, capability_type, ref_id, ref_version, variable_values, sort_order)
VALUES ('gift', 5, 'skill', <skills.id>, 1, '{"city":"广州"}', 0);
```

```
🔴 变量纪律（api-spec §7.5.3，违反会在**进入模型之前**以 30060 失败）：
   · 正文里用到的 {{x}} 必须在 variables_schema 里声明（内置 tenantId/locale/timezone/nowIso 除外）
   · required=true 的变量必须能从「绑定取值 → defaultValue」拿到值
   · 🔴 不得声明内置保留名（tenantId / locale / timezone / nowIso）
   · 变量值按纯文本注入，值里的 {{y}} 不会被二次展开（防注入，无需自行转义）
🔴 修改已发布 Skill 的正确做法：**新增一个版本 + 改绑定的 ref_version**，
   而不是 UPDATE 历史版本行 —— 后者会静默改变所有历史会话的行为（违反 AC-SKL-002）。

🔴 本地 Tool（local_tools 是**平台表**，无 tenant_id）：
   · 单行表：同一 tool_key 永远只有一行，升级时**就地** UPDATE … SET version = version + 1
   · 🔴 只登记声明式元数据，禁止任何可执行代码；实现体是平台内置 Java 组件
   · ⚠️ 一期平台**尚无**内置实现体清单（待产品/架构裁定）：
     此时往 local_tools 写行会在清单构造阶段以 30060 拒绝（fail-closed，属预期行为）
   · tenant_tool_grants.config 只能放非代码配置，出现脚本/表达式片段会被判 30060
```

### 8.5 审计核验（一期只写不查）

`/admin/audit` 查询接口 Deferred 至二期，一期核验直接查库：

```sql
-- action ∈ tool.grant_denied | tool.confirm_allowed | tool.confirm_denied | tool.confirm_timeout
--        | mcp.ssrf_rejected | mcp.connection_test | mcp.credential_changed
--        | tenant.cross_probe | platform.access_grant_issued | platform.cache_evict
SELECT event_id, action, result, object_type, object_id, error_code, occurred_at
  FROM audit_logs
 WHERE tenant_id = 'gift' AND action = 'mcp.ssrf_rejected'
 ORDER BY occurred_at DESC LIMIT 20;
```

```
🔴 event_id 是 32 位小写 hex（无连字符），与接口返回的 auditEventId **完全一致**（禁止截断），
   可用于把"接口响应"与"审计记录"一一对账。
🔴 审计里只有摘要与 digest，没有明文：这是设计，不是数据缺失（AC-AUD-002）。
```

---

## 9. 生产部署要点

```nginx
# ① 前端站点域名：只托管静态产物
server {
  listen 443 ssl http2;
  server_name albedo-gift.eyescode.top albedo-redbook.eyescode.top;
  add_header Strict-Transport-Security "max-age=31536000" always;

  root /var/www/albedo/dist;          # 前端静态产物
  location / { try_files $uri $uri/ /index.html; }
}

# ② API 域名：反代后端（租户识别靠 api-* 的 Host）
server {
  listen 443 ssl http2;
  server_name api-albedo-gift.eyescode.top api-albedo-redbook.eyescode.top;

  location /api/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;                 # 🔴 = api-albedo-gift.eyescode.top，后端精确匹配 tenant_domains
    proxy_set_header X-Forwarded-Host $host;     # 🔴 需 sys_config: tenant.trust_forwarded_host = true
    proxy_http_version 1.1;
    proxy_buffering off;                         # 🔴 SSE 必须关闭缓冲
    proxy_read_timeout 300s;
  }

  location /site/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Host $host;
  }
}
```

> 🔴 前后端分域名后，`tenant_domains` 需为每个租户绑定前端域名与 API 域名两条记录（见 §8.1.1）。前端无需配置 API 地址，`request.ts` 运行时把 `albedo-{tenant}` 推导为 `api-albedo-{tenant}`。

上线检查（阻断项）：

```
□ sys_config: tenant.dev_host_mapping_enabled = false（prod profile 下为 true 时应用启动即失败）
□ 全站 HTTPS + HSTS；eyesUser Thrift 端口不对公网开放
□ application.yml 未进入 Git；凭据未出现在日志
□ Nginx 对 SSE 路径 proxy_buffering off，且透传 Host
□ 前端产物无 console.log、无硬编码色值/文案
```
