# Reranker 接入方案（主流厂商）

> 适用版本：智能体开发平台 · 模型管理 → Reranker 接入
> 原则：**平台不内置 Reranker 模型**，仅提供主流厂商的接入入口；用户选定厂商后填入 API Key（及必要参数）即可使用。支持自定义 OpenAI 兼容 `/rerank` 服务。

## 1. 总体说明

| 项 | 说明 |
|---|---|
| 能力定位 | 对向量召回 + 关键词召回的候选结果做二次相关性排序，提升送入 LLM 的上下文质量 |
| 接入方式 | 选厂商快捷卡（推荐）→ 填 API Key + Base URL（部分预填）；或「自建 / OpenAI 兼容」任意 `/rerank` 服务 |
| 统一请求语义 | `query` + `documents[]` → 返回按相关性降序的 `index / relevance_score` |
| 鉴权 | 厂商多为 `Authorization: Bearer <API_KEY>`；腾讯云支持 Bearer（也兼容 TC3 签名） |
| 计费 | 由对应厂商按调用量计费，平台不收取 Reranker 费用 |

兼容的 OpenAI 风格 `/rerank` 请求体（多数厂商通用）：

```json
{
  "model": "BAAI/bge-reranker-v2-m3",
  "query": "用户问题",
  "documents": ["候选片段 1", "候选片段 2", "候选片段 3"],
  "top_n": 3,
  "return_documents": false
}
```

> 注意：Cohere、Jina、硅基流动、火山方舟、阿里百炼（兼容模式）均使用上表字段名；腾讯云（知识引擎原子能力）使用 `Model / Query / Documents` 驼峰字段。

---

## 2. 各厂商接入详情

### 2.1 BAAI 智源（北京智源研究院）

BAAI 仅**提供模型权重**，不提供官方托管 API。可经以下任一方式部署后，以 OpenAI 兼容 `/rerank` 接入：

| 承载方式 | Base URL 示例 | 模型 | 说明 |
|---|---|---|---|
| 硅基流动 | `https://api.siliconflow.cn/v1/rerank` | `BAAI/bge-reranker-v2-m3` | 最省事，注册即开 |
| 火山引擎方舟 | `https://ark.cn-beijing.volces.com/api/v3/rerank` | `BAAI/bge-reranker-v2-m3` | 云端托管，稳定 |
| 自建 sentence-transformers | 自有服务地址 | `BAAI/bge-reranker-v2-m3` | GPU 自托管，数据不出域 |
| HuggingFace Endpoint | `https://<id>.endpoints.huggingface.cloud/v1/rerank` | `BAAI/bge-reranker-v2-m3` | HF 推理端点 |

- 推荐模型：`BAAI/bge-reranker-v2-m3`（中英多语言，RAG 重排常用）
- 文档：https://huggingface.co/BAAI

接入示例（以硅基流动承载为例，见 2.7）：

```bash
curl -X POST https://api.siliconflow.cn/v1/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "BAAI/bge-reranker-v2-m3",
    "query": "如何申请年假？",
    "documents": ["年假需提前 3 天在 OA 提交审批", "差旅报销标准为..."],
    "top_n": 1
  }'
```

---

### 2.2 Jina AI

| 项 | 值 |
|---|---|
| Base URL | `https://api.jina.ai/v1/rerank` |
| 鉴权 | `Authorization: Bearer <JINA_API_KEY>` |
| 推荐模型 | `jina-reranker-v2-base-multilingual`（多语言）、`jina-reranker-v1-base-en` |
| 文档 | https://jina.ai/reranker |

```bash
curl -X POST https://api.jina.ai/v1/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "jina-reranker-v2-base-multilingual",
    "query": "What is RAG?",
    "documents": ["Retrieval-Augmented Generation ...", "..."],
    "top_n": 3
  }'
```

响应：

```json
{ "results": [ { "index": 0, "relevance_score": 0.93 }, { "index": 2, "relevance_score": 0.71 } ] }
```

---

### 2.3 Cohere

| 项 | 值 |
|---|---|
| Base URL | `https://api.cohere.ai/v1/rerank` |
| 鉴权 | `Authorization: Bearer <COHERE_API_KEY>`（同时支持 `Cohere-Api-Key` 头） |
| 推荐模型 | `rerank-multilingual-v3.0`、`rerank-english-v3.0` |
| 文档 | https://docs.cohere.com |

```bash
curl -X POST https://api.cohere.ai/v1/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "rerank-multilingual-v3.0",
    "query": "How to reset password?",
    "documents": ["Open settings > account ...", "..."],
    "top_n": 3
  }'
```

响应：

```json
{ "results": [ { "index": 0, "relevance_score": 0.98 }, { "index": 1, "relevance_score": 0.62 } ] }
```

---

### 2.4 阿里云百炼（DashScope / 通义）

| 项 | 值 |
|---|---|
| Base URL（OpenAI 兼容） | `https://dashscope.aliyuncs.com/compatible-mode/v1/rerank` |
| 鉴权 | `Authorization: Bearer <DASHSCOPE_API_KEY>`（或环境变量 `DASHSCOPE_API_KEY`） |
| 推荐模型 | `gte-rerank`（通用）、`gte-rerank-v2`、`gte-rerank-v2-googlenews` |
| 文档 | https://help.aliyun.com/zh/model-studio/developer-reference/dashscopererank |

```bash
curl -X POST https://dashscope.aliyuncs.com/compatible-mode/v1/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gte-rerank",
    "query": "如何申请年假？",
    "documents": ["年假需提前 3 天在 OA 提交审批", "..."],
    "top_n": 3,
    "return_documents": false
  }'
```

Python（DashScope SDK）：

```python
import dashscope
from dashscope import TextReRank
dashscope.api_key = "<你的Key>"
resp = TextReRank.call(model="gte-rerank", query="如何申请年假？", documents=[...], top_n=3)
```

---

### 2.5 火山引擎（方舟 Ark）

| 项 | 值 |
|---|---|
| Base URL | `https://ark.cn-beijing.volces.com/api/v3/rerank` |
| 鉴权 | `Authorization: Bearer <ARK_API_KEY>` |
| 模型 | 直接填 `BAAI/bge-reranker-v2-m3`，或方舟「在线推理」创建的接入点 ID（`ep-xxxx`） |
| 文档 | https://www.volcengine.com/docs/82379 |

```bash
curl -X POST https://ark.cn-beijing.volces.com/api/v3/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "BAAI/bge-reranker-v2-m3",
    "query": "如何申请年假？",
    "documents": ["年假需提前 3 天在 OA 提交审批", "..."],
    "top_n": 3
  }'
```

> 提示：方舟也支持 BGE 系列之外的可选重排模型；若使用自建接入点，请把 `model` 换成对应的 `ep-xxxx`。

---

### 2.6 腾讯云（混元 · 知识引擎原子能力「重排序」）

| 项 | 值 |
|---|---|
| Base URL | `https://lkeap.tencentcloudapi.com/` |
| 鉴权 | `Authorization: Bearer <TENCENT_API_KEY>`（或腾讯云 TC3-HMAC 签名） |
| 模型 | `lke-reranker`（基于混元精调的重排模型） |
| 频率 | 默认 20 次/秒 |
| 文档 | https://cloud.tencent.com/document/product/1772/115339 |

> 腾讯云 Reranker 归在「知识引擎原子能力」下，属混元精调技术；接口字段为驼峰形式。

```bash
curl -X POST https://lkeap.tencentcloudapi.com/ \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "Model": "lke-reranker",
    "Query": "如何申请年假？",
    "Documents": ["年假需提前 3 天在 OA 提交审批", "..."]
  }'
```

响应：

```json
{ "Results": [ { "Index": 0, "Score": 0.91 }, { "Index": 1, "Score": 0.45 } ], "RequestId": "..." }
```

---

### 2.7 硅基流动（SiliconFlow）

| 项 | 值 |
|---|---|
| Base URL | `https://api.siliconflow.cn/v1/rerank` |
| 鉴权 | `Authorization: Bearer <SILICONFLOW_API_KEY>` |
| 推荐模型 | `BAAI/bge-reranker-v2-m3`、`Pro/BAAI/bge-reranker-v2-m3`、`netease-youdao/bce-reranker-base_v1` |
| 文档 | https://docs.siliconflow.com/cn/api-reference/rerank/create-rerank |

```bash
curl -X POST https://api.siliconflow.cn/v1/rerank \
  -H "Authorization: Bearer <你的Key>" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "BAAI/bge-reranker-v2-m3",
    "query": "如何申请年假？",
    "documents": ["年假需提前 3 天在 OA 提交审批", "..."],
    "top_n": 3,
    "return_documents": false
  }'
```

响应：

```json
{ "results": [ { "index": 0, "relevance_score": 0.93, "document": { "text": "年假需提前..." } } ] }
```

---

### 2.8 sentence-transformers / HuggingFace

适用于**私有化 / 数据不出域**场景，需自备 GPU 服务。两种形态：

#### A. 自建 sentence-transformers（CrossEncoder）

```python
from sentence_transformers import CrossEncoder

model = CrossEncoder("BAAI/bge-reranker-v2-m3")
query = "如何申请年假？"
docs = ["年假需提前 3 天在 OA 提交审批", "差旅报销标准为..."]

pairs = [[query, d] for d in docs]
scores = model.predict(pairs)          # 相关性分数
ranked = sorted(zip(docs, scores), key=lambda x: -x[1])
```

若需对外暴露为 `/rerank` 服务，可用 FastAPI 包装上述逻辑，对接平台「自建 / OpenAI 兼容」入口。

#### B. HuggingFace Inference Endpoint

- 在 HF 部署 `BAAI/bge-reranker-v2-m3` 推理端点
- Base URL：`https://<your-id>.endpoints.huggingface.cloud/v1/rerank`
- 鉴权：`Authorization: Bearer <HF_TOKEN>`
- 请求体同 2.1 / 2.7 的 OpenAI 风格

| 项 | 值 |
|---|---|
| 推荐模型 | `BAAI/bge-reranker-v2-m3` |
| 文档 | https://huggingface.co/models?pipeline_tag=text-ranking |

---

## 3. 选型建议

| 场景 | 推荐 |
|---|---|
| 开箱即用、国内合规 | 硅基流动 / 阿里云百炼 / 火山方舟 / 腾讯云 |
| 多语言（含英文） | Cohere `rerank-multilingual-v3.0`、Jina `jina-reranker-v2-base-multilingual`、BGE 系列 |
| 数据不出域、私有化 | sentence-transformers 自建 / HuggingFace Endpoint |
| 想用 BGE 权重但不想自建 | 硅基流动 / 火山方舟（均托管 `BAAI/bge-reranker-v2-m3`） |

## 4. 平台侧接入字段映射

原型快捷卡（`rerankerProviders`）已预填以下默认值，用户仅需补 API Key（部分需确认 Base URL）：

| 厂商 key | 名称 | 预填模型 | 预填 Base URL |
|---|---|---|---|
| `baai` | BAAI 智源 | `BAAI/bge-reranker-v2-m3` | SiliconFlow（可改） |
| `jina` | Jina AI | `jina-reranker-v2-base-multilingual` | `https://api.jina.ai/v1/rerank` |
| `cohere` | Cohere | `rerank-multilingual-v3.0` | `https://api.cohere.ai/v1/rerank` |
| `dashscope` | 阿里云百炼 | `gte-rerank` | `https://dashscope.aliyuncs.com/compatible-mode/v1/rerank` |
| `volcengine` | 火山引擎（方舟） | `BAAI/bge-reranker-v2-m3` | `https://ark.cn-beijing.volces.com/api/v3/rerank` |
| `hunyuan` | 腾讯云（混元） | `lke-reranker` | `https://lkeap.tencentcloudapi.com/` |
| `siliconflow` | 硅基流动 | `BAAI/bge-reranker-v2-m3` | `https://api.siliconflow.cn/v1/rerank` |
| `hf` | sentence-transformers / HuggingFace | `BAAI/bge-reranker-v2-m3` | 用户自填端点 |
| `custom` | 自建 / OpenAI 兼容 | 用户自填 | 用户自填 |
