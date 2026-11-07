import { MoreHorizontal, Plug, Settings2, Trash2 } from 'lucide-react'
import type { McpServer } from '../../types/domain'
import { formatNumber, formatPercent } from '../../lib/format'
import { Button, Badge } from '../../components/ui'
import { ConnectionBadge, EnabledBadge } from '../../components/common/StatusBadge'

export const TRANSPORT_LABEL: Record<McpServer['transport'], string> = {
  sse: 'SSE',
  streamable_http: 'Streamable HTTP',
  stdio: 'stdio',
}

export function McpCard({ mcp, onConfig, onTools, onDelete }: { mcp: McpServer; onConfig: () => void; onTools: () => void; onDelete?: () => void }) {
  const enabledTools = mcp.tools.filter((tool) => tool.enabled).length

  return (
    <div className="card group relative flex flex-col p-4 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-pop">
      <div className="flex items-start gap-3">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] bg-blue-50 text-blue-600">
          <Plug className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="truncate text-md font-semibold text-ink">{mcp.name}</h3>
            <Badge tone="neutral">{TRANSPORT_LABEL[mcp.transport]}</Badge>
          </div>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <EnabledBadge enabled={mcp.enabled} running />
            <ConnectionBadge connection={mcp.enabled ? mcp.connection : 'untested'} />
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-0.5">
          {onDelete && (
            <button
              type="button"
              aria-label={`删除 MCP ${mcp.name}`}
              title="删除 MCP"
              onClick={onDelete}
              className="rounded-field p-1 text-ink-faint opacity-0 transition-all duration-200 hover:bg-danger/10 hover:text-danger focus-visible:opacity-100 group-hover:opacity-100"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          )}
          <button type="button" aria-label={`查看 ${mcp.name} 工具管理`} title="工具管理" onClick={onTools} className="rounded-field p-1 text-ink-faint transition-colors hover:bg-canvas hover:text-ink">
            <MoreHorizontal className="h-4 w-4" />
          </button>
        </div>
      </div>

      <p className="mt-3 line-clamp-2 min-h-[36px] text-xs leading-relaxed text-ink-muted">{mcp.description}</p>
      <p className="mt-2 truncate rounded-field bg-canvas px-2.5 py-1.5 font-mono text-2xs text-ink-muted" title={mcp.endpoint}>{mcp.endpoint}</p>

      <div className="mt-3 grid grid-cols-3 gap-2 border-t border-line pt-3 text-center">
        <div><p className="text-sm font-semibold text-ink">{enabledTools}</p><p className="text-2xs text-ink-faint">可用工具</p></div>
        <div><p className="text-sm font-semibold text-ink">{formatNumber(mcp.callCount)}</p><p className="text-2xs text-ink-faint">调用次数</p></div>
        <div><p className="text-sm font-semibold text-ink">{formatPercent(mcp.successRate)}</p><p className="text-2xs text-ink-faint">成功率</p></div>
      </div>

      <Button variant="outline" size="sm" block className="mt-3" icon={<Settings2 className="h-3.5 w-3.5" />} onClick={onConfig}>查看配置</Button>
    </div>
  )
}
