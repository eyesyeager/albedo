import type { ReactNode } from 'react'
import { Activity, AlertCircle, Bot, Boxes, Check, Clock3, Cpu, Layers3, Plug, ScrollText, Wrench, X } from 'lucide-react'
import type { ConnectionStatus, LogStatus, LogType } from '../../types/domain'
import { Badge, type BadgeTone } from '../ui'

export function ConnectionBadge({ connection }: { connection: ConnectionStatus }) {
  const config = {
    normal: { tone: 'ok', text: '连接正常' },
    untested: { tone: 'warn', text: '未测试' },
    error: { tone: 'danger', text: '连接异常' },
  }[connection] as { tone: BadgeTone; text: string }
  return <Badge tone={config.tone} dot>{config.text}</Badge>
}

export function EnabledBadge({ enabled, running }: { enabled: boolean; running?: boolean }) {
  return (
    <Badge tone={enabled ? 'ok' : 'neutral'} dot>
      {enabled ? (running ? '运行中' : '已启用') : '已停用'}
    </Badge>
  )
}

export function PublishBadge({ published }: { published: boolean }) {
  return <Badge tone={published ? 'ok' : 'neutral'} dot>{published ? '已发布' : '草稿'}</Badge>
}

export function RunStatusBadge({ status }: { status: LogStatus }) {
  const config = {
    running: { tone: 'brand', text: '运行中', icon: <Activity className="h-3 w-3" /> },
    success: { tone: 'ok', text: '成功', icon: <Check className="h-3 w-3" /> },
    failed: { tone: 'danger', text: '失败', icon: <X className="h-3 w-3" /> },
    timeout: { tone: 'warn', text: '超时', icon: <Clock3 className="h-3 w-3" /> },
  }[status] as { tone: BadgeTone; text: string; icon: ReactNode }
  return <Badge tone={config.tone}>{config.icon}{config.text}</Badge>
}

const TYPE_META: Record<LogType, { tone: BadgeTone; icon: ReactNode; label: string }> = {
  session: { tone: 'brand', icon: <Bot className="h-3.5 w-3.5" />, label: '会话' },
  mcp: { tone: 'info', icon: <Plug className="h-3.5 w-3.5" />, label: 'MCP' },
  skill: { tone: 'warn', icon: <Boxes className="h-3.5 w-3.5" />, label: 'Skill' },
  tool: { tone: 'brand', icon: <Wrench className="h-3.5 w-3.5" />, label: '内置工具' },
  model: { tone: 'info', icon: <Cpu className="h-3.5 w-3.5" />, label: '模型' },
  system: { tone: 'neutral', icon: <ScrollText className="h-3.5 w-3.5" />, label: '系统操作' },
}

const TYPE_ICON_CLASS: Record<LogType, string> = {
  session: 'bg-brand-soft text-brand',
  mcp: 'bg-blue-50 text-blue-600',
  skill: 'bg-warn/10 text-warn',
  tool: 'bg-brand-soft text-brand',
  model: 'bg-blue-50 text-blue-600',
  system: 'bg-canvas text-ink-muted',
}

export function LogTypeIcon({ type }: { type: LogType }) {
  return (
    <span className={`inline-flex h-7 w-7 items-center justify-center rounded-field ${TYPE_ICON_CLASS[type]}`}>
      {TYPE_META[type].icon}
    </span>
  )
}

export function LogTypeBadge({ type }: { type: LogType }) {
  const meta = TYPE_META[type]
  return <Badge tone={meta.tone}>{meta.icon}{meta.label}</Badge>
}

export function IssueHint({ text }: { text: string }) {
  return (
    <span className="inline-flex items-center gap-1 text-2xs text-warn">
      <AlertCircle className="h-3 w-3" aria-hidden />{text}
    </span>
  )
}

export const RESOURCE_ICONS = {
  model: <Layers3 className="h-3.5 w-3.5" />,
  mcp: <Plug className="h-3.5 w-3.5" />,
  skill: <Boxes className="h-3.5 w-3.5" />,
  tool: <Wrench className="h-3.5 w-3.5" />,
}
