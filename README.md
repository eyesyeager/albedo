# Albedo · 通用企业智能体开发平台

面向企业的 AI 智能体应用开发与运营平台（AgentOps），提供模型接入、Agent 应用、知识库（RAG）、MCP/Skill 工具、AGUI 协议对外接口与运营看板的一站式能力。

## 技术栈

| 层 | 选型 |
|----|------|
| 前端 | Vue 3.5 + TypeScript + Vite 6 + vue-router 4 + axios |
| 后端 | Java 17 + Spring Boot 3.3 + MyBatis-Plus 3.5 + MySQL 8.0 + Redis |
| AI 运行时 | OpenAI 兼容协议（腾讯混元 TokenHub / DeepSeek / OpenAI 等），自研 ReAct 循环 + SSE 事件流 |
| 认证 | eyesUser 单点登录（`eyesAuth-spring-boot-starter`，`@Permission` 鉴权） |
| 检索引擎 | 用户自配置向量库（Milvus/Qdrant/pgvector/Elasticsearch/自定义）与 ES，平台不内置实例（本地降级检索兜底） |

## 目录结构

```
albedo/
├── docs/                      # 需求文档 PRD v0.3 + 5 份技术设计 + SSO/Reranker/运维文档
├── backend/                   # [后端] Spring Boot 服务
│   ├── pom.xml
│   ├── scripts/schema.sql     # 全量表 DDL（幂等，可重复执行）
│   └── src/main/
│       ├── java/com/albedo/   # common/config/auth/model/agent/kb/mcp/skill/overview/log
│       └── resources/application.yml
├── prototype/                 # [前端] Vue3 管理台（高保真 UI，已接入真实 API）
│   ├── .env / .env.example
│   └── src/{api,views,components,store,...}
└── README.md
```

## 快速启动

### 1. 后端

前置：JDK 17、Maven 3.9+，本地已 `mvn install` `eyesAuth-spring-boot-starter:1.1.0`，并确保可访问 MySQL/Redis。

```bash
cd backend
# 初始化数据库（幂等）
mysql -h <mysql_host> -P <port> -u <user> -p < scripts/schema.sql
# 启动（默认连接运维信息中的 MySQL/Redis，eyesUser SSO 开启）
mvn spring-boot:run
# 或打包运行
mvn -DskipTests package && java -jar target/albedo-server.jar
```

后端默认监听 `8080`。

> **本地开发降级**：若 eyesUser Thrift（`119.29.213.134:9011`）不可达，可用 `--eyes-auth.enabled=false` 启动，鉴权回退到 `albedo.auth.dev-uid`（默认 10247），便于本地联调。

### 2. 前端

```bash
cd prototype
npm install
cp .env.example .env   # 按需修改 VITE_API_DOMAIN
npm run dev            # 默认 http://localhost:5173
```

## 关键配置（`backend/src/main/resources/application.yml`）

- **数据源**：`spring.datasource.*`（MySQL）
- **缓存**：`spring.data.redis.*`
- **演示 LLM**：`albedo.bootstrap.demo-llm-*`（首次登录自动接入一个演示模型）
- **密钥加密**：`albedo.secret-key`（AES-GCM base64，生产务必更换）
- **SSO**：`eyes-auth.*`（`app-id=albedo`、`auth-type=1`、`thrift.host/port`）

前端环境变量（`prototype/.env`）：`VITE_API_DOMAIN`（后端地址）、`VITE_SSO_URL`（eyesUser 授权页）、`VITE_CLIENT_ID`（361925）。

## 登录认证（eyesUser SSO）

1. 未登录访问受保护页面 → 前端 302 跳转 `https://user.eyescode.top/OAuth2?clientId=361925&redirectUrl=...`；
2. 用户授权后回跳携带 `?authorization=<jwt>` → 前端解析并存入 `localStorage`，清理 URL；
3. 后续请求经 axios 拦截器在 `authorization` 请求头携带 token；
4. 后端 `@Permission(PermissionEnum.USER)` 经 eyesAuth starter 校验 token，`UserInfoHolder.getUid()` 取当前用户做资源隔离（权限仅 NO/USER）；
5. `type=1` 单 token 模式下，前端从响应头回写续期 token；错误码 `20000~20005` 统一清 token 跳登录。

## 对外 AGUI 协议（应用发布后）

```
POST /agui/apps/{app_id}/sessions              # 创建会话（返回 session_id）
POST /agui/apps/{app_id}/chat                  # 对话（SSE 事件流）
GET  /agui/apps/{app_id}/sessions/{sid}/messages
POST /agui/apps/{app_id}/sessions/{sid}/interrupt
```

- 鉴权：`Authorization: Bearer <JWT>`，业务方用应用 Jwt Key（HS256）自签，平台仅验签；
- 限流：按应用 QPM（`application.qpm`）Redis 固定窗口计数；
- 会话完全匿名，以 `app_id + session_id` 隔离；
- 事件：`RUN_STARTED/FINISHED/ERROR`、`TEXT_MESSAGE_START/CONTENT/END`、`TOOL_CALL_START/ARGS/END`、`STEP_STARTED/FINISHED`。

管理侧 RESTful 接口统一前缀 `/v1/**`（eyesUser 鉴权）：`/v1/apps`、`/v1/models`、`/v1/knowledge-bases`、`/v1/vector-stores`、`/v1/elasticsearch-configs`、`/v1/mcp-servers`、`/v1/skills`、`/v1/operation-logs`、`/v1/overview`、`/v1/me`。统一响应 `{ code, message, data, requestId }`。

## 降级实现说明（MVP）

- **LLM**：OpenAI 兼容协议直连（腾讯混元等），真实推理；模型健康检查为直连 HTTP 探测（回显请求/响应/耗时）。
- **向量库 / Elasticsearch / OCR**：平台不内置实例，提供适配层与本地降级（知识库检索降级为本地关键词匹配），保证无真实实例时全链路可启动、可跑通。
- **MCP**：Streamable HTTP JSON-RPC（initialize/tools/list/tools/call）适配；SSE 传输或目标不可达时优雅降级。

## 重要提示：腾讯混元 Key 已失效

运维信息中的混元 API Key 为旧平台 Key，混元已迁移至 **TokenHub**（旧模型 `hunyuan-turbo` 等已下线）。当前默认演示模型为 `hy3-preview`（base_url `https://tokenhub.tencentmaas.com/v1`）。**需到 [TokenHub 控制台](https://console.cloud.tencent.com/tokenhub/apikey) 申请新 API Key**，替换 `application.yml` 中 `albedo.bootstrap.demo-llm-api-key` 后即可真实推理。也可直接接入任意 OpenAI 兼容服务（DeepSeek / OpenAI 等）。

## 更多文档

- 需求文档：`docs/智能体开发平台-需求文档.md`
- 技术设计：`docs/技术设计-{智能体,MCP,知识库,Skill}模块.md`
- SSO 接入：`docs/耶瞳用户中心接入文档.md`
- 后端配置与接口：`backend/README.md`
