# Albedo · 智能体开发平台

面向个人用户的 AI 能力工作台（AgentOps），提供模型接入、Agent 编排与调试、MCP/Skills/内置工具资源池、AGUI 与标准对话 API 发布、全链路调用日志与工作区设置的一站式能力。

## 仓库结构

```
albedo/
├── frontend/            # React 前端工程（唯一数据来源：后端管理面 API）
├── backend/             # Python 3.12 + FastAPI 管理面与对外网关后端
├── packages/contracts/  # OpenAPI 3.1 契约快照与生成脚本
├── docs/                # 产品文档、原型截图与后端设计文档
└── README.md            # 本文件
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| `docs/智能体开发平台-产品文档.md` | 产品规格基线：信息架构、模块规则、日志规范、验收清单 |
| `docs/原型截图索引.md` + `docs/screenshots/` | 40 张原型截图与视觉基准 |
| `docs/后端系统架构设计.md` | Python / FastAPI / LangGraph 分层架构、组件选型、运行引擎与数据流向 |
| `docs/后端接口文档.md` | 端点总表、核心接口定义、错误码体系与前端命令映射 |
| `docs/后端实施计划.md` | 前后端对接改造清单、里程碑、验收标准与风险 |
| `docs/运维信息.md` | 依赖服务与凭据注入说明（不含任何明文凭据，禁止写入仓库） |
| `packages/contracts/openapi.json` | FastAPI 自动导出的 OpenAPI 3.1 管理面契约快照 |
| `backend/README.md` | 后端启动、迁移、系统配置初始化与范围边界 |

## 前端快速开始

```bash
cd frontend
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。其他脚本同样在 `frontend/` 下执行：

```bash
npm run typecheck   # TypeScript 类型检查
npm run build       # 类型检查 + 生产构建
npm run preview     # 预览生产构建
npm run contracts   # 重新导出 OpenAPI 契约快照
```

### 数据来源与系统配置

前端不存在演示模式，也没有本地持久化业务数据：所有资源、日志、统计、趋势、搜索结果与系统配置都来自 `/api/v1/**`。应用启动时校验会话并加载 `GET /api/v1/workspace/bootstrap` 全量快照，之后每次写操作都由服务端落库并即时更新状态。

业务可调参数（枚举选项、新建资源默认值、各类限额、运行时参数、内置工具目录）存放在数据库 `system_configs` 表，由 `GET/PATCH /api/v1/settings/configs` 与 bootstrap 下发，可在「系统设置 → 系统配置」中修改。凭据、数据库连接、Redis、上游 Key 等部署配置仍由 `backend/.env` 注入，不进数据库、不下发浏览器。

开发代理的后端地址可用 `ALBEDO_BACKEND_ORIGIN` 覆盖（见 `frontend/.env.example`），默认 `http://localhost:8000`；Vite 把 `/api`、`/v1` 代理到该地址。前端不需要任何业务凭据或演示 Key。

## 后端快速开始

后端采用 Python 3.12 + FastAPI + SQLAlchemy 2.0(async) + Alembic + MySQL 8.0 + Redis，详见 `backend/README.md`。

```bash
cd backend
uv sync                        # 生成 .venv 与 uv.lock
cp .env.example .env           # 按 docs/运维信息.md 填写连接串（.env 已被忽略）
python scripts/init_db.py      # 真实建表 + 写入系统配置与内置工具目录 + 逐表校验
uv run uvicorn app.main:app --reload --port 8000
```

可选后台任务：`uv run arq app.worker.WorkerSettings`（每日按保留周期清理日志并归档过期导出任务）。

验证：

```bash
# 集成测试会删除并写入固定 ID 的基线数据，必须使用独立数据库，禁止连接业务库。
cd backend && ALBEDO_TEST_DATABASE_URL='mysql+aiomysql://user:pass@127.0.0.1:3306/albedo_test' \
  PYTHONPATH=. uv run pytest -q
cd frontend && npm run typecheck && npm run build
bash packages/contracts/generate.sh --check
```

CI 见 `.github/workflows/ci.yml`，包含隔离库测试、契约快照比对、密钥/运行时产物扫描与前端构建。

`ENVIRONMENT` 默认是 `prod`（会强制校验 Secure Cookie、CSRF、演示 Key、Fernet 密钥等）；仅本地开发可显式设置 `ENVIRONMENT=dev`。dev 下未配置 `SECRET_KEY` 时会生成并复用 `backend/var/.dev-secret-key`，避免重启后已加密凭据不可解密。轮换密钥时用 `SECRET_KEY_PREVIOUS` 提供旧值，并执行 `python scripts/rotate_secrets.py --confirm` 重加密存量密文。

安全开关只能由部署期环境变量控制，不能经管理面接口修改：

- `SANDBOX_ENABLED` 默认 `false`：代码解释器与 Skill 脚本执行默认关闭，关闭时也不向模型暴露对应工具。开启前必须把后端放进容器 / seccomp / 只读文件系统隔离，应用层白名单只是纵深防御；开启后脚本通过 `RESOURCES["相对路径"]` 读取包内文本，不能使用 `open()`。
- `MCP_STDIO_ENABLED` 默认 `false`，需与 `MCP_STDIO_ALLOWED_COMMANDS`（逗号分隔的可执行文件名，如 `python3`）同时配置。stdio MCP 只接受纯文件名并按固定系统路径解析，`env` 不得覆盖 `PATH` / `LD_*` / `PYTHONPATH` 等可劫持执行的变量。
- 提示词模板只支持 `{{变量名}}`，不执行模板表达式或过滤器。
- `python scripts/backup_db.py` 生成加密备份（`.json.enc`，权限 0600），排除 `sessions` 并剔除 `runs.config_snapshot`；恢复用 `python scripts/restore_db.py <备份文件> --confirm`。

仓库包含契约、基础设施/认证/工作区快照、四类资源、Agent 与发布配置、日志与治理、LangGraph 运行引擎、真实模型与 Redis checkpointer、标准对话/AGUI/模型透传对外网关，以及 SSRF、Skill 包校验、脱敏、签名下载与并发乐观锁等安全加固。模型连接测试、MCP 工具发现、工具调用与统计趋势均为真实链路，无离线模拟。生产初始化（`backend/scripts/init_db.py`）只写入系统必需配置与内置工具目录，不写入演示业务数据；集成测试使用独立数据库并会写入固定测试基线，切勿在业务库执行（见下方验证命令）。

## 对外 API

管理面 `/api/v1/**` 使用 Cookie 会话；对外面使用 `Authorization: Bearer <Key>`，两套鉴权链完全隔离。

```bash
# 标准对话 API（Agent Token）
curl -X POST http://localhost:8000/v1/agents/{agentId}/chat \
  -H 'Authorization: Bearer <Agent Token>' -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"你好"}]}'

# AGUI 事件流（始终 SSE）
curl -N -X POST http://localhost:8000/api/agui/v1/agents/{agentId}/run \
  -H 'Authorization: Bearer <Agent Token>' -H 'Content-Type: application/json' \
  -d '{"threadId":"thd_1","runId":"run_1","messages":[{"id":"m1","role":"user","content":"你好"}]}'

# 已发布模型（OpenAI 兼容）
curl http://localhost:8000/v1/models -H 'Authorization: Bearer <Publication Key>'
```

Token 在管理面「智能体 → 发布」与「模型 → 发布版本」中签发或重置。创建/重置/轮换响应会返回完整值，之后也可在详情页通过「查看完整密钥」按需解密读取（需登录态、响应 `no-store`、写入审计日志）；bootstrap 与常规资源接口不下发明文，日志与导出仅保留后缀。

## 登录

访问 Key 由后端 `DEMO_ACCESS_KEY` 环境变量注入（本机 `backend/.env`），前端不预填、不保存任何 Key。登录请求提交到服务端用 Argon2id 校验，Redis 记失败次数，成功下发 HttpOnly Cookie 会话；bootstrap 不下发任何凭证明文，只返回 `accessKeySuffix`，模型与智能体凭证需通过登录态下的专用查看端点按需读取。生产环境会拒绝公开演示值，详见 `docs/运维信息.md`。

可在「系统设置 → 访问与安全」中修改；退出登录会撤销服务端会话，业务数据保留在数据库。

## 功能地图

| 路由 | 页面 |
| --- | --- |
| `/login` | 访问 Key 登录 |
| `/` | 概览：指标、调用趋势、资源状态、最近活动、快捷入口 |
| `/agents` | 智能体列表：搜索、状态与默认模型组合筛选、创建 |
| `/agents/:agentId` | 三栏编辑器（基础信息 / 提示词 / 模型设置 / 能力挂载 / 高级设置）、测试会话、对话历史、发布 |
| `/resources/mcp` | MCP 接入、真实连接测试与工具发现、四 Tab 详情抽屉与工具启停 |
| `/resources/skills` | Skill 拖拽上传、详情抽屉与版本记录 |
| `/resources/tools` | 内置工具查看与真实调用次数、全局启停（含影响清单确认） |
| `/resources/models` | 模型接入、真实上游探测、配置抽屉、多版本发布与版本删除 |
| `/logs` | 服务端分页筛选、执行链路详情与真实异步导出 |
| `/settings` | 常规设置、系统配置、访问 Key 与日志保留策略 |

全局组件：分组侧栏、吸顶面包屑顶栏、`⌘/Ctrl + K` 全局搜索、通知面板、帮助弹窗与右上角 Toast。

## 数据说明

- 初始化只写入系统必需数据：工作区设置行、`system_configs` 配置、管理面访问 Key 与内置工具目录；不预置任何智能体、模型、MCP、Skill、日志、趋势或通知。
- 调用趋势由 `log_entries` 按自然日真实聚合；内置工具调用次数、MCP 调用记录与成功率由每次真实工具执行写入；通知由失败事件、发布成功等真实业务事件生成。
- 日志导出在创建任务时真实生成文件并写入审计日志；下载链接使用服务端签名，有效期来自系统配置。
- 服务端分页、筛选、全局搜索、统计与执行链路全部查询 MySQL；前端不缓存业务数据到 localStorage，刷新后由 bootstrap 重新加载。
- 所有创建、编辑、启停、发布、测试、导出操作即时生效并持久化在数据库。

设计基线见 `docs/智能体开发平台-产品文档.md` 与 `docs/原型截图索引.md`。
