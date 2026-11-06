import { MessageSquare, Puzzle, Sparkles } from 'lucide-react'
import type { Agent, ModelResource } from '../../types/domain'
import { formatNumber, formatRelative } from '../../lib/format'
import { GradientAvatar } from '../../components/common/GradientAvatar'
import { PublishBadge, IssueHint } from '../../components/common/StatusBadge'
import { Badge } from '../../components/ui'

export function AgentCard({ agent, models, onClick }: { agent: Agent; models: ModelResource[]; onClick: () => void }) {
  const model = models.find((item) => item.id === agent.defaultModelId)
  const capabilityCount = agent.mounts.mcpIds.length + agent.mounts.skillIds.length + agent.mounts.toolIds.length
  const modelInvalid = !model || !model.enabled || model.connection !== 'normal'

  return (
    <button
      type="button"
      onClick={onClick}
      className="card group flex flex-col p-4 text-left transition-all duration-200 hover:-translate-y-0.5 hover:border-brand/30 hover:shadow-pop"
    >
      <div className="flex items-start gap-3">
        <GradientAvatar theme={agent.theme} name={agent.name} />
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="truncate text-md font-semibold text-ink transition-colors group-hover:text-brand">{agent.name}</h3>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-1.5">
            <PublishBadge published={agent.apiEnabled || agent.aguiEnabled} />
            {agent.aguiEnabled && <Badge tone="brand"><Sparkles className="h-2.5 w-2.5" />AGUI</Badge>}
          </div>
        </div>
      </div>

      <p className="mt-3 line-clamp-2 min-h-[40px] text-xs leading-relaxed text-ink-muted">{agent.description}</p>

      <div className="mt-3 space-y-2 border-t border-line pt-3">
        <div className="flex items-center justify-between gap-2 text-2xs">
          <span className="text-ink-faint">默认模型</span>
          <span className="flex items-center gap-1.5 truncate">
            {modelInvalid && <IssueHint text="资源异常" />}
            <span className={modelInvalid ? 'text-warn' : 'text-ink'}>{model?.name ?? '未选择模型'}</span>
          </span>
        </div>
        <div className="flex items-center justify-between text-2xs">
          <span className="text-ink-faint">挂载能力</span>
          <span className="inline-flex items-center gap-1 text-ink"><Puzzle className="h-3 w-3 text-brand" />{capabilityCount} 个</span>
        </div>
        <div className="flex items-center justify-between text-2xs">
          <span className="text-ink-faint">会话量</span>
          <span className="inline-flex items-center gap-1 text-ink"><MessageSquare className="h-3 w-3 text-brand" />{formatNumber(agent.sessionCount)}</span>
        </div>
      </div>

      <p className="mt-3 text-2xs text-ink-faint">更新于 {formatRelative(agent.updatedAt)}</p>
    </button>
  )
}
