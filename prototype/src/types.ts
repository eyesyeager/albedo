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
  mcp: string;
  preset: boolean;
}

export interface ToolBinding {
  id: string;
  kind: "KnowledgeBase" | "MCP" | "Skill";
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
