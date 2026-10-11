export type ModelType = "LLM" | "EMBEDDING" | "RERANKER";
export type AppStatus = "enabled" | "disabled";

export interface RerankerProvider {
  key: string;
  name: string;
  model: string;
  baseUrl: string;
  keyHint: string;
  doc: string;
}
export type DocStatus = "success" | "parsing" | "failed" | "pending";

export interface Model {
  id: string;
  name: string;
  type: ModelType;
  provider: string;
  baseUrl: string;
  apiKeyMasked: string;
  status: "online" | "offline" | "preset";
  temperature: number;
  topP: number;
  maxTokens: number;
  contextLen: number;
  usage: { inputTokens: number; outputTokens: number };
  updatedAt: string;
}

/** 技能包解压后的单个文件（路径为包内相对路径，目录层级由调用方自行构建） */
export interface SkillPackageFile {
  path: string;
  size: number;
  /** 文本文件内容；二进制资源为空 */
  content?: string;
  /** 是否为二进制资源（图片/字体等），为 true 时不做内容预览 */
  binary?: boolean;
}

/** 技能包（SKILL.md / 压缩包）解析报告 */
export interface SkillPackageReport {
  fileName: string;
  size: string;
  kind: string;
  valid: boolean;
  checks: { label: string; ok: boolean; hint: string }[];
  manifest: {
    name: string;
    description: string;
    version: string;
    icon: string;
  };
  /** 解压后的全部文件（扁平列表，含目录层级路径） */
  files: SkillPackageFile[];
  /** 入口 Markdown 文件内容预览（不假定固定文件名） */
  preview: string;
  /** 识别出的入口文件路径，如 SKILL.md / README.md，未识别则为空 */
  entryPath?: string;
}

export interface HealthCheckResult {
  modelId: string;
  modelName: string;
  type: ModelType;
  ok: boolean;
  /** 本次健康检查的模拟耗时（ms） */
  latencyMs: number;
  request: { method: string; url: string; body: string };
  response: { status: string; latencyMs: number; body: string };
  summary: string;
}

export interface DocItem {
  id: string;
  name: string;
  type: string;
  size: string;
  status: DocStatus;
  chunks: number;
  tags: string[];
  updatedAt: string;
}

export interface KnowledgeBase {
  id: string;
  name: string;
  desc: string;
  embeddingModel: string;
  reranker: string;
  retrieveMode: "vector" | "hybrid";
  topK: number;
  docCount: number;
  charCount: number;
  status: "ready" | "indexing";
  updatedAt: string;
}

export interface McpTool {
  name: string;
  desc: string;
  enabled: boolean;
}

export interface McpServer {
  id: string;
  name: string;
  endpoint: string;
  transport: "SSE" | "Streamable HTTP";
  auth: string;
  status: "connected" | "error";
  tools: McpTool[];
  updatedAt: string;
}

export interface Skill {
  id: string;
  name: string;
  desc: string;
  icon: string;
  preset: boolean;
  /** 来自技能包导入（SKILL.md + references/ + scripts/ + assets/ 压缩包） */
  packaged?: boolean;
  /** 技能包版本（取自 SKILL.md frontmatter 的 version） */
  version?: string;
  /** 技能包解压后的全部文件（不假定固定目录规范） */
  packageFiles?: SkillPackageFile[];
  /** 识别出的入口文件路径（根目录 Markdown，优先 SKILL.md） */
  entryPath?: string;
}

export interface ToolBinding {
  id: string;
  kind: "KnowledgeBase" | "MCP" | "Skill";
  /** 被引用资源的 id（知识库 id / MCP id / Skill id），用于删除前的占用校验 */
  refId?: string;
  name: string;
  sub: string;
  enabled: boolean;
  strategy: "smart" | "always";
}

export interface AppConfig {
  model: string;
  modelMode: "fixed" | "auto";
  prompt: string;
  reactRounds: number;
  shortMemory: number;
  knowledgeStrategy: "smart" | "always";
  tools: ToolBinding[];
}

export interface AppSnapshot {
  id: string;
  savedAt: string;
  note: string;
  config: string;
}

export interface AppItem {
  id: string;
  name: string;
  desc: string;
  icon: string;
  iconColor: string;
  status: AppStatus;
  qpm: number;
  jwtKeyMasked: string;
  sessionCount: number;
  callToday: number;
  errorRate: number;
  updatedAt: string;
  config: AppConfig;
  snapshots: AppSnapshot[];
}

export interface SessionTrace {
  id: string;
  appId: string;
  sessionId: string;
  userQuery: string;
  status: "success" | "error" | "running";
  steps: TraceStep[];
  startedAt: string;
  latency: number;
}

export interface TraceStep {
  id: string;
  type: "think" | "tool" | "text" | "step";
  title: string;
  detail?: string;
  status?: "success" | "error" | "running";
  latency?: number;
  toolName?: string;
  input?: string;
  output?: string;
}

export interface OperationLog {
  id: string;
  operator: string;
  module: string;
  action: string;
  target: string;
  result: "success" | "fail";
  ip: string;
  createdAt: string;
}

export interface User {
  uid: number;
  name: string;
  email: string;
  role: "USER" | "NO";
  avatarColor: string;
}
