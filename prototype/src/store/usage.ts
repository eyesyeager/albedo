import { apps } from "@/data/mock";
import type { AppItem, ToolBinding } from "@/types";

/**
 * 统一的资源占用校验：删除知识库 / MCP / Skill 前，
 * 若已被任一应用在「工具装配」中挂载，则不允许删除。
 */

function matches(t: ToolBinding, kind: ToolBinding["kind"], refId?: string, name?: string) {
  if (t.kind !== kind) return false;
  // 优先按 refId 精确匹配，缺失时回退为名称匹配
  return t.refId ? !!refId && t.refId === refId : !!name && t.name === name;
}

export function appsUsing<T extends { id: string; name: string }>(
  kind: ToolBinding["kind"],
  item: T
): AppItem[] {
  return apps.filter((a) => a.config.tools.some((t) => matches(t, kind, item.id, item.name)));
}

export function kbUsage(kb: { id: string; name: string }) {
  return appsUsing("KnowledgeBase", kb);
}

export function mcpUsage(mcp: { id: string; name: string }) {
  return appsUsing("MCP", mcp);
}

export function skillUsage(skill: { id: string; name: string }) {
  return appsUsing("Skill", skill);
}
