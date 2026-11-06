import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { MessageSquare, Search } from 'lucide-react'
import { formatDuration, formatNumber } from '../../lib/format'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { agentSessions, LOG_SOURCE_LABEL } from '../../store/selectors'
import { useWorkspace } from '../../store/workspace'
import { EmptyState, Input, Select } from '../../components/ui'
import { RunStatusBadge } from '../../components/common/StatusBadge'
import { SectionCard } from '../../components/common/PageHeader'

type SourceFilter = 'all' | 'test_session' | 'standard_api' | 'agui'
type RangeFilter = '7d' | '30d' | '1y'


export function HistoryTab({ agentId }: { agentId: string }) {
  const navigate = useNavigate()
  const logs = useWorkspace((state) => state.logs)
  const ranges = useWorkspace((state) => state.systemConfig.options.historyRanges)
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [source, setSource] = useState<SourceFilter>('all')
  const [range, setRange] = useState<RangeFilter>('7d')

  const sessions = useMemo(() => agentSessions({ logs }, agentId), [logs, agentId])
  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return sessions.filter((log) => {
      if (source !== 'all' && log.source !== source) return false
      const days = ranges.find((item) => item.value === range)?.days ?? 7
      if (Date.now() - new Date(log.timestamp).getTime() > days * 86_400_000) return false
      if (!query) return true
      return `${log.name} ${log.summary} ${log.runId ?? ''}`.toLowerCase().includes(query)
    })
  }, [sessions, debounced, source, range])

  return (
    <SectionCard
      title="对话历史"
      description="按智能体 ID 与结构化来源关联的运行记录，遵循全局日志保留周期"
      actions={<span className="text-xs text-ink-muted">共 <span className="font-semibold text-brand">{filtered.length}</span> 条</span>}
      bodyClassName="p-0"
    >
      <div className="flex flex-wrap items-center gap-2 border-b border-line p-4">
        <Input
          aria-label="搜索会话"
          placeholder="搜索会话内容、摘要或 runId…"
          leading={<Search className="h-3.5 w-3.5" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          className="min-w-[220px] flex-1"
        />
        <Select
          aria-label="来源筛选"
          value={source}
          onChange={(event) => setSource(event.target.value as SourceFilter)}
          options={[
            { value: 'all', label: '全部来源' },
            { value: 'test_session', label: '测试会话' },
            { value: 'standard_api', label: '标准 API' },
            { value: 'agui', label: 'AGUI' },
          ]}
          className="w-[140px]"
        />
        <Select
          aria-label="时间范围"
          value={range}
          onChange={(event) => setRange(event.target.value as RangeFilter)}
          options={[
            { value: '7d', label: '最近 7 天' },
            { value: '30d', label: '最近 30 天' },
            { value: '1y', label: '最近 1 年' },
          ]}
          className="w-[140px]"
        />
      </div>

      {filtered.length === 0 ? (
        <EmptyState icon={<MessageSquare className="h-5 w-5" />} title="暂无对话历史" description="先在右侧测试会话发起一次运行，或通过已发布接口调用智能体。" />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] text-left">
            <thead className="table-head">
              <tr>
                {['会话 / 摘要', '运行标识', '来源', '耗时', 'Token', '状态'].map((head) => (
                  <th key={head} className="px-4 py-2.5 font-medium">{head}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-line/70">
              {filtered.map((log) => (
                <tr
                  key={log.logId}
                  onClick={() => navigate(`/logs?runId=${log.runId ?? ''}`)}
                  className="cursor-pointer transition-colors hover:bg-brand-faint"
                >
                  <td className="max-w-[280px] px-4 py-3">
                    <p className="truncate text-xs font-medium text-ink">{log.name}</p>
                    <p className="mt-0.5 truncate text-2xs text-ink-faint">{log.summary}</p>
                  </td>
                  <td className="px-4 py-3 font-mono text-2xs text-ink-muted">{log.runId ?? '—'}</td>
                  <td className="px-4 py-3 text-2xs text-ink-muted">{LOG_SOURCE_LABEL[log.source]}</td>
                  <td className="px-4 py-3 text-2xs tabular-nums text-ink-muted">{formatDuration(log.durationMs)}</td>
                  <td className="px-4 py-3 text-2xs tabular-nums text-ink-muted">{log.tokenUsage ? formatNumber(log.tokenUsage.totalTokens) : '—'}</td>
                  <td className="px-4 py-3"><RunStatusBadge status={log.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </SectionCard>
  )
}
