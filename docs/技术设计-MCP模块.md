# Albedo · MCP 模块技术设计（实现级）

> 版本：v2.0
> 日期：2026-10-10
> 技术栈：Java 17 + Spring Boot 3.x + Spring AI MCP Client（`McpSyncClient` / `McpAsyncClient` + SSE / Streamable HTTP 传输层）
> 关联需求：PRD v0.3 §6.5 MCP 管理、§7 非功能需求、§8 技术架构、§9 核心数据模型
> 说明：Skill 与 MCP **已隔离**，不再属于本模块（无 `mcp_id` 外键），详见《技术设计-Skill模块》
> 上下游模块：向上为「智能体模块」提供工具调用（`listTools`/`callTool`）；与「模型网关」无直接依赖，独立协议适配

---

## 1. 模块概述

MCP 模块以 **Model Context Protocol（MCP）** 作为统一外部集成标准，负责外部 MCP Server 的接入、调试、工具注册与调用，并支持**工具级禁用**。Skill 本期弱化为「官方预打包的 MCP 能力封装/推荐模板」，不单独建模块。

> 说明：本模块即架构中的「**MCP 网关**」。协议适配（`initialize`/`tools/list`/`tools/call`）在这里完成；智能体模块仅消费其产出的工具描述与调用入口（详见智能体模块 §6.2）。

### 1.1 定位

- **协议适配**：接入 SSE / Streamable HTTP 传输的 MCP Server。
- **工具注册**：发现并注册 MCP Server 暴露的工具（schema）。
- **工具调用**：供智能体多步动态调用，非固定顺序。
- **工具治理**：Server 级接入 + 单个高危工具禁用、密钥注入。
- **调试**：连通性测试与工具调用调试。
- ~~**Skill**：官方预打包的 MCP 封装模板（预置少量模板）~~ → **已移出**：Skill 与 MCP 隔离，独立为指令与资料包模块。

### 1.2 职责边界

| 职责 | 说明 | 不包含 |
|------|------|--------|
| MCP Server CRUD 与广场 | 自定义 MCP 配置、MCP 广场（P1） | 插件转 MCP（MCP-07，P2 后续） |
| 协议适配与工具发现 | SSE/Streamable HTTP、`initialize`、`tools/list`、`tools/call` | 智能体调度（智能体模块） |
| 工具级禁用 | per-app 禁用标记 | 模型能力 |
| 鉴权注入 | token/密钥注入 | 密钥存储（统一密文） |
| ~~Skill 模板~~ | **已移出**：Skill 与 MCP 隔离，独立为指令资料包模块 | 本模块不再承担任何 Skill 职责 |

### 1.3 术语约定

沿用 PRD §5：**MCP** 是外部工具/服务的标准接入协议；**工具** 是智能体可调用的能力单元；**Skill** 是官方预打包的 MCP 能力封装/推荐模板（本期不单独建模块）。

本文在实现层新增下列与 Spring AI MCP 对齐的术语：

| 术语 | 说明 |
|------|------|
| `McpSyncClient` | Spring AI MCP 同步客户端，封装与单个 MCP Server 的会话（握手/工具列表/调用） |
| `McpAsyncClient` | Spring AI MCP 异步客户端（非阻塞，生产高并发场景） |
| `Transport` | MCP 传输层抽象（SSE / Streamable HTTP / STDIO） |
| `McpSchema.Tool` | MCP 工具定义（name/description/inputSchema），映射到 `tool` 表 |
| `ToolCallback` | 智能体侧工具统一抽象（由智能体模块消费，网关不直接产出） |

---

## 2. 核心功能

| 需求编号 | 功能 | 优先级 |
|----------|------|--------|
| MCP-01 | MCP 广场（官方/第三方服务市场） | P1 |
| MCP-02 | 自定义 MCP（SSE / Streamable HTTP） | P0 |
| MCP-03 | MCP 工具调用（智能体多步动态调用） | P0 |
| MCP-04 | MCP 鉴权（token/密钥注入） | P1 |
| MCP-05 | MCP 调试（连通性测试 + 工具调用调试） | P0 |
| MCP-06 | 工具级禁用（per-app 高危工具禁用） | P0 |
| MCP-07 | 插件转 MCP | P2（后续迭代） |
| SKL-01 | 预置技能模板（官方预打包 MCP 封装） | P2 |
| SKL-02 | 自定义技能 | P2（后续迭代） |

### 2.1 MCP Server 配置模型

```jsonc
{
  "name": "工单系统",
  "endpoint": "https://mcp.example.com/sse",
  "transport": "SSE",              // SSE | STREAMABLE_HTTP
  "auth": { "type": "bearer", "token": "encrypted" },
  "headers": { "X-Custom": "..." }, // 自定义请求头
  "timeoutMs": 30000,
  "retry": 2
}
```

### 2.2 工具模型（统一注册表）

| 字段 | 说明 |
|------|------|
| `tool_name` | 工具名（MCP Server 内唯一） |
| `description` | 工具描述（供模型规划） |
| `input_schema` | JSON Schema 入参 |
| `enabled` | 是否可用（per-app 禁用覆盖） |

---

## 3. 技术选型与依赖

### 3.1 技术栈总览

| 层 | 选型 | 用途 |
|----|------|------|
| 语言/运行时 | Java 17 | 基础运行时 |
| 框架 | Spring Boot 3.x + WebFlux | 应用基座、非阻塞 I/O |
| MCP 客户端 | `spring-ai-starter-mcp-client-webflux` | SSE / Streamable HTTP 协议适配（生产推荐 WebFlux 传输层） |
| 连接管理 | 自研连接池 + 心跳保活 | 会话复用、故障隔离 |
| 缓存 | Redis | 工具 schema 缓存、per-app 绑定缓存 |
| 密文 | 统一加密服务（KMS/密文） | token/headers 密文存储 |

### 3.2 Maven 依赖（示意）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.cloud.ai</groupId>
      <artifactId>spring-ai-alibaba-bom</artifactId>
      <version>${spring-ai-alibaba.version}</version><!-- 1.1.2.2 -->
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- MCP 客户端（WebFlux 传输层：SSE + Streamable HTTP） -->
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
  </dependency>
  <!-- WebFlux 基础（SSE 事件流 / 非阻塞 HTTP） -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
  </dependency>
</dependencies>
```

> 说明：`spring-ai-starter-mcp-client-webflux` 同时提供 `WebFluxSseClientTransport` 与 Streamable HTTP 传输实现，适合生产环境（非阻塞、高并发）。同步（`McpSyncClient`）与异步（`McpAsyncClient`）客户端**不可混用**，本模块统一采用 `McpSyncClient` + WebFlux 传输层。

### 3.3 核心类/接口速查

| 类型 | 全限定名（或约定包） | 在本文中的角色 |
|------|---------------------|----------------|
| `McpSyncClient` | `org.springframework.ai.mcp.client.McpSyncClient` | 与单个 MCP Server 的同步会话客户端 |
| `McpAsyncClient` | `org.springframework.ai.mcp.client.McpAsyncClient` | 异步客户端（高并发场景预留） |
| `HttpClientSseClientTransport` | `org.springframework.ai.mcp.client.transport.HttpClientSseClientTransport` | SSE 传输（JDK HttpClient 实现） |
| `WebFluxSseClientTransport` | `org.springframework.ai.mcp.client.transport.WebFluxSseClientTransport` | SSE 传输（WebFlux 实现，生产推荐） |
| `McpServerParameters` | `org.springframework.ai.mcp.spec.McpServerParameters` | MCP Server 连接参数（URL/超时/headers） |
| `McpSchema` | `org.springframework.ai.mcp.spec.McpSchema` | MCP 协议模型（`Tool`/`CallToolRequest`/`CallToolResult`/`ListToolsResult`） |
| `SyncMcpToolCallbackProvider` | `org.springframework.ai.mcp.*` | 自建直连场景下将 MCP 工具批量暴露为 `ToolCallback`（智能体模块用） |
| `McpConnectionPool` | `com.albedo.mcp.invoke.McpConnectionPool`（自定义） | 连接池 + 心跳 + 故障隔离 |
| `ToolRegistry` | `com.albedo.mcp.registry.ToolRegistry`（自定义） | 工具 schema 缓存 + 变更失效 |

---

## 4. 架构设计

### 4.1 模块内部分层

```
┌─────────────────────────────────────────────────────────────┐
│  管理台前端（Vue3 + TS）                                      │
│  MCP 列表/广场/工具调试/Skill 模板                             │
└───────────────┬─────────────────────────────────────────────┘
                │ RESTful（管理侧，eyesUser 鉴权）
┌───────────────▼─────────────────────────────────────────────┐
│  MCP 网关（MCP Gateway）                                      │
│  ┌────────────┐ ┌────────────┐ ┌─────────────┐ ┌──────────┐ │
│  │ 服务管理    │ │ 协议适配器  │ │ 工具注册表   │ │ 调用引擎  │ │
│  │ Server Mgmt│ │ Adapter    │ │ Tool Registry│ │ Invoker  │ │
│  └─────┬──────┘ └─────┬──────┘ └──────┬──────┘ └────┬─────┘ │
│        │              │               │             │        │
│  ┌─────▼──────────────▼───────────────▼─────────────▼─────┐ │
│  │  连接池 / 工具缓存 / 鉴权注入 / 禁用过滤 / 调试探针        │ │
│  └──────────────────────────┬───────────────────────────────┘ │
└─────────────────────────────┼─────────────────────────────────┘
                              │ MCP 协议（SSE/Streamable HTTP）
                    ┌─────────▼─────────┐
                    │  外部 MCP Server   │
                    └───────────────────┘
```

### 4.2 核心组件职责（映射到 Spring AI MCP）

| 组件 | 职责 | Spring AI / 技术落点 |
|------|------|----------------------|
| 服务管理（Server Mgmt） | MCP Server CRUD、广场数据、状态（connected/error） | 领域服务 + `mcp_config` 表 |
| 协议适配器（Adapter） | 封装 SSE / Streamable HTTP 握手、`initialize`、`tools/list`、`tools/call` | `McpSyncClient` + `Transport` 工厂 |
| 连接池（Connection Pool） | 复用会话、心跳保活、故障隔离 | 自研 `McpConnectionPool` |
| 工具注册表（Tool Registry） | 缓存工具 schema、per-app 禁用覆盖、变更失效 | Redis `mcp:tools:{id}` + `McpSchema.Tool` |
| 调用引擎（Invoker） | 鉴权注入、参数序列化、超时/重试、结果归一化 | `syncClient.tools().callTool(...)` |
| 调试探针（Probe） | 连通性测试、单工具调用调试（回放入参/返回/耗时） | 复用 Adapter，单次会话 |
| 禁用过滤器 | 按 `ToolAppBinding.enabled` 过滤工具 | `tool_app_binding` 表 + 缓存 |

### 4.3 关键设计决策

| 决策 | 方案 | 理由 |
|------|------|------|
| 工具统一注册表 | `tool` 表 + `tool_app_binding` 表 per-app 禁用标记 | 支持 Server 级接入 + 工具级禁用 |
| 工具 schema 缓存 | 连接建立时 `tools/list` 拉取并缓存，Server 变更失效重拉 | 减少握手开销 |
| 连接复用 | MCP 连接池 + 心跳保活 | 降低延迟，MCP 会话有状态 |
| 鉴权注入 | token 密文存储，调用时解密注入 Header，不落明文 | 安全（APP-16/MCP-04） |
| 同步客户端统一 | 统一 `McpSyncClient` + WebFlux 传输层（不混用异步） | Spring AI 约定同步/异步不可混用 |
| Skill 与 MCP 隔离 | Skill 不再绑定 MCP、表中无 `mcp_id` 外键，独立为指令与资料包模块 | PRD 修订 v0.3；见《技术设计-Skill模块》 |

---

## 5. 协议适配实现（实现级）

### 5.1 客户端创建（Adapter）

```java
McpServerParameters params = McpServerParameters.builder(serverId)
    .serverUrl(config.endpoint())
    .build();

// 生产推荐 WebFlux SSE 传输层（支持 Streamable HTTP 时切换对应 transport）
var transport = WebFluxSseClientTransport.builder(params).build();

var syncClient = McpSyncClient.builder(transport)
    .requestTimeout(Duration.ofMillis(config.timeoutMs()))   // 超时
    .sync();
```

> Streamable HTTP 传输：Spring AI MCP 已支持 Streamable HTTP，通过对应的 Streamable HTTP transport 实现（`transport = STREAMABLE_HTTP` 时由 `TransportFactory` 切换）。传输层差异封装在 Adapter 内，上层零感知。

### 5.2 工具发现（`tools/list`）

```java
McpSchema.ListToolsResult result = syncClient.tools().listTools();
List<McpSchema.Tool> tools = result.tools();
// 映射到 tool 表：name / description / inputSchema(JSON Schema)
```

### 5.3 工具调用（`tools/call`）

```java
McpSchema.CallToolResult result = syncClient.tools().callTool(
    new McpSchema.CallToolRequest(toolName, argsMap));
// result.content() → 归一化为统一响应（§6.3）
```

### 5.4 鉴权注入

MCP 的鉴权 token/headers 在**建立连接时注入**（`McpServerParameters` / 自定义 transport 的请求头），由调用引擎从密文解密后注入，全程不落明文：

```java
// 调用引擎在创建 transport 前，从密文解密 token/headers
Map<String, String> headers = secretService.decryptHeaders(config.authCipher(), config.headersCipher());
McpServerParameters params = McpServerParameters.builder(serverId)
    .serverUrl(config.endpoint())
    .headers(headers)               // 注入 Authorization / 自定义头（示意，以实际 transport 注入方式为准）
    .build();
```

---

## 6. 工具调用与归一化

### 6.1 工具调用主链路（供智能体）

```
智能体下发 callTool(app_id, mcp_id, tool_name, args)
  → 禁用过滤器：检查 tool_app_binding，被禁用 → 返回错误
  → 鉴权注入：解密 token/headers
  → 连接池借用 McpSyncClient（无则建连 + initialize）
  → Invoker：tools/call（含超时/重试）
  → 结果归一化 → 返回智能体 + 写 ToolCallTrace（由智能体模块）
```

### 6.2 连接池与故障隔离

```java
public class McpConnectionPool {
    // key = mcp_config_id；每个 Server 维护独立的连接池
    // 借用：优先复用空闲连接，无则新建（initialize 握手）
    // 心跳：定时 ping / tools/list 保活，失效连接重建
    // 故障隔离：单 Server 连接异常不影响其他 Server（独立池）
}
```

### 6.3 统一调用语义

```jsonc
// 调用请求
{ "toolName": "create_ticket", "args": { "title": "...", "priority": "high" } }
// 归一化响应
{ "ok": true, "content": [ { "type": "text", "text": "..." } ], "latencyMs": 120 }
// 失败
{ "ok": false, "error": { "code": "MCP_CALL_FAILED", "message": "..." } }
```

### 6.4 工具级禁用

```
POST /v1/mcp-servers/{id}/tools/{tool}/disable  { app_id }
  → 写 tool_app_binding(app_id, tool_id, enabled=false)
  → 失效工具注册表缓存（mcp:binding:{app_id}）
  → 智能体装配时过滤掉禁用工具
```

### 6.5 Skill 模板装配（SKL-01）

```
Skill 模板 = 预置的 MCP Server 配置 + 推荐工具集 + 使用说明
  → 用户「使用」模板 → 复制生成 MCP Server 配置（可改 token）
  → 后续等同于普通 MCP 接入（§5）
```

---

## 7. 关键流程

### 7.1 MCP Server 接入与连通性测试

```
用户配置 endpoint/transport/auth → POST /v1/mcp-servers
  → 保存配置（token 密文）
  → 调试探针：握手 initialize → tools/list 拉取工具 → 更新 tool 注册表
  → 返回 { status: connected, tools: [...] }（失败返回 error + 原因）
```

### 7.2 工具调用（供智能体）

见 §6.1 主链路。

---

## 8. 接口定义

### 8.1 管理侧接口（RESTful，`@Permission(USER)`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/mcp-servers` | 创建自定义 MCP |
| GET | `/v1/mcp-servers` | 列表 |
| GET | `/v1/mcp-servers/{id}` | 详情（含工具） |
| PUT | `/v1/mcp-servers/{id}` | 编辑 |
| DELETE | `/v1/mcp-servers/{id}` | 删除 |
| POST | `/v1/mcp-servers/{id}/test` | 连通性测试 |
| POST | `/v1/mcp-servers/{id}/tools/{tool}/test` | 单工具调试 |
| POST | `/v1/mcp-servers/{id}/tools/{tool}/disable` | 工具级禁用 |
| POST | `/v1/mcp-servers/{id}/tools/{tool}/enable` | 工具级启用 |
| GET | `/v1/mcp-marketplace` | MCP 广场（P1） |
| GET | `/v1/skills/templates` | 预置 Skill 模板（P2） |

### 8.2 内部接口（供智能体运行时）

| 方法 | 说明 |
|------|------|
| `listTools(appId, mcpIds)` | 返回装配可用工具 schema（已过滤禁用） |
| `callTool(appId, mcpId, toolName, args)` | 调用工具，返回归一化结果 |
| `describeTools(appId, mcpIds)` | 生成工具描述（供 Planner） |

---

## 9. 数据结构

### 9.1 数据库表

| 表 | 关键字段 | 说明 |
|----|----------|------|
| `mcp_config` | id, owner_uid, name, endpoint, transport(SSE/STREAMABLE_HTTP), auth(密文), headers(密文), status, timeout_ms, retry, created_at | MCP Server 配置 |
| `tool` | id, kind(MCP/SKILL), mcp_config_id, name, description, input_schema, created_at | 统一工具注册表 |
| `tool_app_binding` | app_id, tool_id, enabled | per-app 工具禁用标记 |
| `skill_template` | id, name, desc, icon, mcp_preset(JSON), preset | 预置 Skill 模板（P2） |

### 9.2 缓存

| 键 | 用途 |
|----|------|
| `mcp:tools:{mcp_config_id}` | 工具 schema 缓存（Server 变更失效） |
| `mcp:binding:{app_id}` | per-app 工具启用状态缓存 |

### 9.3 领域对象（示意）

```java
record McpConfig(Long id, Long ownerUid, String name, String endpoint,
                 Transport transport, String authCipher, String headersCipher,
                 Status status, int timeoutMs, int retry) {}

record ToolDef(Long id, ToolKind kind, Long mcpConfigId, String name,
               String description, String inputSchema) {}

record ToolAppBinding(Long appId, Long toolId, boolean enabled) {}

enum Transport { SSE, STREAMABLE_HTTP }
enum ToolKind { MCP, SKILL }
```

领域对象与 Spring AI MCP 类型对应：

| 领域对象 | Spring AI MCP 类型 |
|----------|--------------------|
| MCP Server 连接参数 | `McpServerParameters` |
| 工具定义 | `McpSchema.Tool` |
| 工具调用请求/结果 | `McpSchema.CallToolRequest` / `CallToolResult` |
| 工具列表结果 | `McpSchema.ListToolsResult` |
| 会话客户端 | `McpSyncClient` |

### 9.4 建议包结构

```
com.albedo.mcp
├── controller/       # MCP Server 管理、工具调试、广场、Skill 模板接口
├── service/          # McpServerService / ToolRegistryService / BindingService
├── adapter/          # TransportFactory（SSE/Streamable HTTP）、McpClientFactory
├── invoke/           # McpConnectionPool、Invoker（鉴权注入/超时重试/归一化）
├── registry/         # ToolRegistry（schema 缓存、变更失效）
├── probe/            # 调试探针（连通性测试、单工具调试）
├── config/           # Bean 装配、WebFlux 配置
└── domain/           # McpConfig / ToolDef / ToolAppBinding / Transport ...
```

---

## 10. 依赖关系

### 10.1 依赖的模块

| 依赖模块 | 依赖内容 | 交互方式 |
|----------|----------|----------|
| 认证（eyesAuth starter） | 管理侧鉴权、`UserInfoHolder.uid` | 依赖注入 + AOP |
| 基础设施 | Redis（连接/工具缓存）、密文服务 | 基础设施依赖 |
| 外部 | MCP Server（SSE / Streamable HTTP） | MCP 协议 |

### 10.2 被依赖方

| 调用方 | 内容 |
|--------|------|
| 智能体运行时 | 工具列表（`listTools`）+ 工具调用（`callTool`） |
| 控制台前端 | MCP 管理、工具调试 |

### 10.3 与智能体模块的衔接约定

- MCP 网关负责工具 schema 与调用；智能体模块负责「何时调用」（ReAct 规划）与「禁用过滤的最终裁决」。
- 工具调用轨迹（`tool_call_trace`）由智能体模块统一写入，MCP 网关返回原始归一化结果。
- 智能体模块通过 `listTools` 拉取 schema 并转换为 `ToolCallback`（详见智能体模块 §6.2），网关不直接产出 `ToolCallback`。

---

## 11. 非功能性要求

### 11.1 性能

| 指标 | 目标 |
|------|------|
| 工具调用平台侧转发开销 | < 100ms（不含 MCP Server 自身耗时） |
| 工具 schema 获取 | 缓存命中 < 20ms |
| 连通性测试 | P95 < 3s（含握手） |
| 并发工具调用 | 连接池复用，水平扩展 |

### 11.2 安全

- MCP token/密钥密文存储、调用时注入，禁硬编码；
- 工具级禁用对高危工具兜底（MCP-06）；
- 出网访问控制（允许列表，防 SSRF）；
- 工具调用超时/重试限制，防资源耗尽。

### 11.3 可用性与可扩展性

- 协议适配器插件化（`TransportFactory`），新增传输协议/版本易扩展；
- MCP Server 不可用时降级（跳过该工具并提示，不阻断会话）；
- 连接池故障隔离，单 Server 异常不影响其他 Server。

### 11.4 可观测性

- 工具调用耗时/成功率埋点；
- 连通性状态监控与告警；
- 全链路 trace_id。

---

## 12. 潜在风险与应对措施

| 风险 | 影响 | 应对 |
|------|------|------|
| MCP 生态不稳定/协议版本差异 | 接入失败 | 协议适配层 + 版本探测 + 降级（PRD §12.1） |
| 高危工具被误调用 | 安全/业务风险 | 工具级禁用 + 工具描述约束 + 调用审计 |
| SSRF（恶意 endpoint） | 内网风险 | 出网白名单 + URL 校验 |
| MCP Server 不可用 | 会话卡顿 | 超时熔断 + 降级提示 + 故障隔离 |
| 工具 schema 变更 | 调用参数错误 | `tools/list` 变更监听失效缓存重拉 |
| token 泄露 | 越权 | 密文存储 + 轮换 + 最小权限注入 |
| 同步/异步客户端混用 | 连接异常 | 统一 `McpSyncClient`，禁止混用 |

---

## 13. 与其他模块衔接

- **智能体模块**：MCP 作为「工具」挂载，MCP 网关提供 `listTools`/`callTool`；智能体负责规划调度与 per-app 禁用过滤。
- **模型网关**：无直接依赖（MCP 工具调用不经模型推理）。
- **知识库模块**：均为「工具」来源，但协议/实现独立；Skill 模板可封装任意 MCP（含知识库类工具，后续）。
