# albedo 后端 · 管理面与对外网关

Python 3.12 + FastAPI + SQLAlchemy 2.0(async) + Alembic + MySQL 8.0 + Redis 的模块化单体。

系统只有一条真实链路：模型连接测试真实请求上游 `/chat/completions` 或 `/embeddings`，MCP 工具发现真实连接远端服务，Agent 运行使用 `ChatOpenAI` + LangGraph `StateGraph`，内置工具与 MCP 工具真实执行。不存在离线模式、模拟网关、模板回复模型或预置演示数据。

## 本地启动

```bash
cd backend
uv sync                     # 生成 .venv 与 uv.lock（Python 3.12）
cp .env.example .env        # 按 docs/运维信息.md 填写，凭据不得提交
python scripts/init_db.py   # 执行迁移 + 写入系统配置与内置工具目录 + 逐表校验
uv run uvicorn app.main:app --reload --port 8000
uv run arq app.worker.WorkerSettings   # 可选：后台任务与每日清理
```

前端另起终端：

```bash
cd frontend
npm install && npm run dev  # http://localhost:5173，/api 与 /v1 已代理至 8000
```

前端始终连接后端，无模式开关；`ALBEDO_BACKEND_ORIGIN` 可覆盖代理目标。

## 数据库与系统配置

- 迁移由 Alembic 管理：`uv run alembic revision --autogenerate -m "..."` / `uv run alembic upgrade head`。
- `scripts/init_db.py` 串联 `alembic upgrade head` → 幂等写入系统必需数据 → 逐表计数校验。初始化**只**写入工作区设置行、`system_configs` 配置、管理面访问 Key 与内置工具目录，不预置任何业务数据。
- `system_configs` 表存放业务可调参数：`platform`（名称、版本、域名、默认语言/时区/保留周期）、`options`（全部枚举与中文标签）、`defaults`（新建 Agent/模型/MCP/发布版本/Skill 的默认值）、`limits`（各类上限与配额）、`runtime`（运行时参数）、`builtinTools`（内置工具目录）。
- `GET /api/v1/settings/configs` 读取配置，`PATCH /api/v1/settings/configs` 修改并即时生效；`GET /api/v1/workspace/bootstrap` 也会下发 `systemConfig`。进程内缓存由 `services/system_config.py` 同步，避免热路径反复查库。
- 部署配置（数据库、Redis、`SECRET_KEY`、上游 Key、CORS、Cookie 等）只从被 `.gitignore` 忽略的 `backend/.env` 读取，不进数据库、不下发浏览器。非 dev 环境缺少真实上游凭据会在启动时拒绝。
- 所有时间以 ISO 8601 UTC 字符串存储；访问 Key 明文不落库，只保存 Argon2id hash 与后缀，bootstrap 永不下发明文。

## 约定与边界

- 统一错误体 `{error:{code,message,requestId}}`，管理面错误码不与对外码混用。
- 写操作统一返回 `{entity, log, ...}`：实体与本次系统审计日志一并返回，供前端写回状态。
- 会话权威存储在 MySQL `sessions` 表，Redis 用于失败计数、限流与加速；Redis 不可用时限流降级为进程内计数。
- `app/adapters/gateway.py` 是唯一的外部服务网关：模型探测按类型选端点并校验真实响应结构，MCP 发现失败返回明确错误而非模拟工具。
- `app/runtime/tools.py` 只注册真实实现；工具不可用时返回 `status:"error"`，绝不伪装成功。Skill 按三级渐进式披露加载：L1 由 `SKILL.md` frontmatter 的 `description` 作为触发面常驻 system prompt，L2 命中时加载版本冻结的 `SKILL.md` 正文，L3 按需读取包内资源、或在受限进程沙箱执行包内脚本（脚本源码不进上下文，只回传输出）。L2 与 L3 资源读取不占 `maxToolCalls`（L3 脚本执行仍按一次工具调用计数），L2 在同一会话内去重，分层事件写入 `skill_load_records`。脚本沙箱沿用 `code_interpreter` 的 AST 静态拦截 + `RLIMIT` 降级实现，**非容器级隔离**；无 `SKILL.md` 的旧式包仍按手填使用说明运行。
- Skill 包以腾讯云 COS 为权威存储（`SKILL_STORAGE=cos`），`app/services/skill_storage.py` 统一负责上传、删除与读取；`var/skill-cache` 只保存不可变内容的本地只读缓存，供 L3 沙箱按路径使用。数据库只存相对 key 与 `cos://` URI，可多副本部署；dev 环境缺 COS 凭据时降级本地目录并告警。历史本地包用 `scripts/migrate_skills_to_cos.py` 迁移。
- 单元测试用 `tests/fakes.py` 的确定性对话模型验证图编排，它只在 pytest 中使用，不参与任何生产代码路径。

## 真实数据管线

- 调用趋势 `/api/v1/stats/trends` 由 `log_entries` 按自然日聚合，不再读取预置趋势表。
- 内置工具调用次数、MCP 调用记录与成功率由 `services/tool_usage.py` 在每次工具执行后真实落库。
- 通知由 `services/notify.py` 在失败审计、模型发布成功等真实事件写入。
- 日志导出创建任务时即真实生成脱敏文件并写审计日志，签名有效期与过期天数来自系统配置；流式对外调用结束后回填日志终态。
- 服务端分页、筛选、全局搜索、统计与执行链路全部查询 MySQL。

## 对外网关

| 路由 | 说明 |
| --- | --- |
| `POST /v1/agents/:id/chat` | Agent Token 鉴权，JSON 或 `stream=true` 的逐 token 增量 SSE（基于 `RuntimeEngine.run_stream`） |
| `POST /api/agui/v1/agents/:id/run` | AGUI 事件流，事件缓存 TTL 来自系统配置，支持 `Last-Event-ID` 回放 |
| `POST /v1/chat/completions` | 发布版本 Key 鉴权，OpenAI 兼容透传，参数/Header 合并与版本 QPM |
| `POST /v1/embeddings` | Embedding 发布版本透传 |
| `GET /v1/models` | 当前 Key 对应且启用的调用标识 |

- 鉴权：按 Key 后缀筛选 + Argon2 校验；跨 Agent 访问返回 `agent_access_denied`，未发布返回 `agent_not_published`。
- 配额：固定窗口（窗口长度来自系统配置，Redis 降级进程内计数），超限 `rate_limit_exceeded`。
- 上游凭证取 `upstream_model_key`（不是发布 Key）；禁止覆盖 `Host`/`Content-Length` 等 Header；上游错误统一映射。
- 模型上游 Key、发布版本 Key、Agent Token 可通过 `GET /api/v1/{models,agents,publications}/:id/credential` 按需解密查看：需登录态、响应 `no-store`、写入系统操作日志；bootstrap 与常规资源读接口仍只返回 `credentialSuffix`。

## 安全加固

- **SSRF**：`app/core/network.py` 在 DNS 解析后校验所有 IP，阻断回环、私网、链路本地、保留段与云元数据。
- **Skill 包**：`services/skill_package.py` 校验 ZIP 结构、路径穿越、符号链接、目录深度、解压体积/文件数（Zip Bomb）与 manifest；上限来自系统配置，并交叉校验声明大小与实际字节数。
- **脱敏**：日志/导出对 `Authorization: Bearer`、`sk-*`、`api_key`、`accessKey` 等字段统一打码。
- **下载签名**：导出任务返回 HMAC 签名 URL（TTL 来自系统配置），篡改或过期返回 403。
- **并发保存**：Agent 更新为 `UPDATE ... WHERE version = expected` 原子条件更新，并发请求一个成功、一个 `409 stale_version`。
- **沙箱**：`code_interpreter` 用独立受限进程 + `RLIMIT` + AST 静态拦截实现，非容器级隔离；`log_entries` 未做按月分区。

## 验证

```bash
cd backend && PYTHONPATH=. uv run pytest -q
cd frontend && npm run typecheck && npm run build
bash packages/contracts/generate.sh --check
```
