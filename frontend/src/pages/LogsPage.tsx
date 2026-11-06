import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Activity, ChevronRight, Download, RotateCcw, Search, SlidersHorizontal, Timer } from 'lucide-react'
import { cn } from '../lib/cn'
import { adminApi, api } from '../lib/api'
import { formatDate, formatDuration, formatNumber, formatPercent, formatTime } from '../lib/format'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { asError } from '../lib/errors'
import { DEFAULT_LOG_FILTERS, LOG_TYPE_LABEL, type LogFilters } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import type { LogEntry, LogStatus, LogType, OverviewStats } from '../types/domain'
import { Button, CopyButton, EmptyState, Input, Pagination, Popover, Select, toast } from '../components/ui'
import { LogTypeIcon, RunStatusBadge } from '../components/common/StatusBadge'
import { PageHeader, SectionCard } from '../components/common/PageHeader'
import { LogDetailDrawer } from './logs/LogDetailDrawer'

const EMPTY_STATS: OverviewStats = {
  agentsTotal: 0, agentsPublished: 0, todayCalls: 0, callsChangePercent: 0,
  modelsTotal: 0, modelsEnabled: 0, successRate: 0, avgDurationMs: 0,
  avgDurationChange: 0, abnormalCount: 0, typeCounts: {},
}

function formatTaskSize(bytes: number): string {
  return bytes >= 1024 ? `${Math.round(bytes / 1024)} KB` : `${bytes} B`
}

function Metric({ label, value, sub, icon }: { label: string; value: string; sub: string; icon: ReactNode }) {
  return (
    <div className="card p-4">
      <div className="flex items-start justify-between">
        <p className="text-xs text-ink-muted">{label}</p>
        <span className="flex h-8 w-8 items-center justify-center rounded-field bg-brand-soft text-brand">{icon}</span>
      </div>
      <p className="mt-2 text-2xl font-semibold tracking-tight text-ink">{value}</p>
      <p className="mt-1 text-2xs text-ink-faint">{sub}</p>
    </div>
  )
}

export function LogsPage() {
  const data = useWorkspace()
  const config = useWorkspace((state) => state.systemConfig)
  const pageSize = config.limits.logPageSize || 8
  const types = config.options.logTypes.map((item) => ({ key: item.value as LogType | 'all', label: item.label }))
  const [params, setParams] = useSearchParams()
  const [filters, setFilters] = useState<LogFilters>(DEFAULT_LOG_FILTERS)
  const debouncedKeyword = useDebouncedValue(filters.keyword)
  const [moreOpen, setMoreOpen] = useState(false)
  const [exportOpen, setExportOpen] = useState(false)
  const [page, setPage] = useState(1)
  const [exporting, setExporting] = useState(false)
  const [serverResult, setServerResult] = useState<{ items: LogEntry[]; total: number; typeCounts: Record<string, number> } | null>(null)
  const [stats, setStats] = useState<OverviewStats | null>(null)

  const effective = useMemo<LogFilters>(() => ({ ...filters, keyword: debouncedKeyword }), [filters, debouncedKeyword])

  useEffect(() => setPage(1), [debouncedKeyword, filters.type, filters.status, filters.range, filters.agentId])

  useEffect(() => {
    let canceled = false
    adminApi.logs
      .query({
        page,
        pageSize,
        keyword: effective.keyword,
        type: effective.type,
        status: effective.status,
        range: effective.range,
        agentId: effective.agentId,
      })
      .then((result) => {
        if (!canceled) setServerResult(result)
      })
      .catch(() => {
        if (!canceled) setServerResult(null)
      })
    return () => {
      canceled = true
    }
  }, [page, pageSize, effective.keyword, effective.type, effective.status, effective.range, effective.agentId])

  useEffect(() => {
    adminApi.stats.overview().then(setStats).catch(() => setStats(null))
  }, [data.logs.length])

  const shown = stats ?? EMPTY_STATS

  const logIdParam = params.get('logId')
  const runIdParam = params.get('runId')
  useEffect(() => {
    if (!runIdParam) return
    let canceled = false
    // runId → logId 必须走服务端查询：前端快照只保留最近若干条日志，
    // 更早的运行不在快照里，会让深链静默失效。
    adminApi.logs
      .query({ page: 1, pageSize: 1, runId: runIdParam, range: '1y' })
      .then((result) => {
        if (canceled) return
        const match = result.items[0]
        setParams((current) => {
          const next = new URLSearchParams(current)
          if (match) next.set('logId', match.logId)
          next.delete('runId')
          return next
        }, { replace: true })
      })
      .catch(() => {
        if (canceled) return
        setParams((current) => {
          const next = new URLSearchParams(current)
          next.delete('runId')
          return next
        }, { replace: true })
      })
    return () => {
      canceled = true
    }
  }, [runIdParam, setParams])

  const openDetail = (id: string) => {
    const next = new URLSearchParams(params)
    next.set('logId', id)
    setParams(next, { replace: true })
  }
  const closeDetail = () => {
    const next = new URLSearchParams(params)
    next.delete('logId')
    next.delete('runId')
    setParams(next, { replace: true })
  }

  const reset = () => {
    setFilters(DEFAULT_LOG_FILTERS)
    setMoreOpen(false)
  }
  const hasFilters = Boolean(filters.keyword) || filters.type !== 'all' || filters.status !== 'all' || filters.range !== '7d' || filters.agentId !== 'all'

  const filterLabel = [
    `类型=${types.find((item) => item.key === filters.type)?.label ?? '全部'}`,
    `状态=${filters.status === 'all' ? '全部' : filters.status}`,
    `时间=${filters.range}`,
    `智能体=${filters.agentId === 'all' ? '全部' : filters.agentId}`,
    effective.keyword ? `关键词=${effective.keyword}` : '',
  ].filter(Boolean).join(' · ')

  const exportLogs = (format: 'csv' | 'json') => {
    setExportOpen(false)
    setExporting(true)
    api.logs
      .export(format, filterLabel, { ...effective })
      .then((task) => toast.success(`日志导出完成：${task.recordCount} 条记录（${task.id}）`))
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setExporting(false))
  }

  const total = serverResult?.total ?? 0
  const rows = serverResult?.items ?? []
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  const typeCounts = serverResult?.typeCounts ?? {}
  const agentOptions = data.agents.map((agent) => ({ value: agent.id, label: agent.name }))

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="调用日志"
        description="统一查询智能体、工具、模型与系统操作产生的全链路日志"
        actions={
          <Popover
            open={exportOpen}
            onClose={() => setExportOpen(false)}
            trigger={<Button variant="outline" icon={<Download className="h-3.5 w-3.5" />} loading={exporting} onClick={() => setExportOpen((open) => !open)}>导出日志</Button>}
            panelClassName="w-[220px] p-2"
          >
            <p className="px-2 pb-1.5 pt-1 text-2xs text-ink-faint">导出当前筛选结果（已脱敏）</p>
            <button type="button" onClick={() => exportLogs('csv')} className="w-full rounded-field px-2 py-2 text-left text-xs text-ink transition-colors hover:bg-brand-faint">CSV · 摘要字段</button>
            <button type="button" onClick={() => exportLogs('json')} className="w-full rounded-field px-2 py-2 text-left text-xs text-ink transition-colors hover:bg-brand-faint">JSON · 完整结构化字段</button>
            {data.exportTasks.length > 0 && (
              <div className="mt-1 border-t border-line px-2 pt-2">
                <p className="text-2xs text-ink-faint">最近导出任务</p>
                {data.exportTasks.slice(0, 3).map((task) => (
                  <p key={task.id} className="mt-1 truncate text-2xs text-ink-muted" title={`${task.filters} · ${task.fileSizeBytes} bytes`}>
                    {task.id} · {task.recordCount} 条 · {formatTaskSize(task.fileSizeBytes)} · 已完成
                  </p>
                ))}
              </div>
            )}
          </Popover>
        }
      />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <Metric label="今日调用" value={formatNumber(shown.todayCalls)} sub={`较昨日 +${formatPercent(shown.callsChangePercent)}`} icon={<Activity className="h-4 w-4" />} />
        <Metric label="成功率" value={formatPercent(shown.successRate)} sub="最近 24 小时成功调用比例" icon={<Activity className="h-4 w-4" />} />
        <Metric label="平均耗时" value={`${(shown.avgDurationMs / 1000).toFixed(2)} s`} sub={`较上一周期 ${shown.avgDurationChange}%`} icon={<Timer className="h-4 w-4" />} />
      </div>

      <SectionCard className="mt-4" bodyClassName="p-0">
        <div className="scroll-thin flex gap-1 overflow-x-auto border-b border-line px-3 pt-2">
          {types.map((type) => {
            const count = type.key === 'all'
              ? Object.values(typeCounts).reduce((sum, value) => sum + value, 0)
              : typeCounts[type.key] ?? 0
            return (
              <button
                key={type.key}
                type="button"
                onClick={() => setFilters({ ...filters, type: type.key })}
                className={cn(
                  'relative flex shrink-0 items-center gap-1.5 border-b-2 px-3 pb-2.5 pt-2 text-xs transition-colors',
                  filters.type === type.key ? 'border-brand font-medium text-brand' : 'border-transparent text-ink-muted hover:text-ink',
                )}
              >
                {type.label}
                <span className={cn('rounded-pill px-1.5 text-[10px]', filters.type === type.key ? 'bg-brand-soft text-brand' : 'bg-canvas text-ink-faint')}>{count}</span>
              </button>
            )
          })}
        </div>

        <div className="flex flex-wrap items-center gap-2 p-3">
          <Input
            aria-label="搜索日志"
            placeholder="搜索对象名称、摘要、Agent 或 runId…"
            leading={<Search className="h-3.5 w-3.5" />}
            value={filters.keyword}
            onChange={(event) => setFilters({ ...filters, keyword: event.target.value })}
            className="min-w-[240px] flex-1"
          />
          <Select
            aria-label="状态筛选"
            value={filters.status}
            onChange={(event) => setFilters({ ...filters, status: event.target.value as LogStatus | 'all' })}
            options={config.options.logStatuses.map((item) => ({ value: item.value, label: item.label }))}
            className="w-[120px]"
          />
          <Select
            aria-label="时间范围"
            value={filters.range}
            onChange={(event) => setFilters({ ...filters, range: event.target.value as LogFilters['range'] })}
            options={config.options.logRanges.map((item) => ({ value: item.value, label: item.label }))}
            className="w-[130px]"
          />
          <Button variant={moreOpen ? 'secondary' : 'outline'} icon={<SlidersHorizontal className="h-3.5 w-3.5" />} onClick={() => setMoreOpen((open) => !open)}>更多筛选</Button>
          <Button variant="ghost" icon={<RotateCcw className="h-3.5 w-3.5" />} disabled={!hasFilters} onClick={reset}>重置</Button>
        </div>

        {moreOpen && (
          <div className="flex flex-wrap items-end gap-4 border-t border-line bg-canvas/40 p-3 animate-fade-in">
            <div>
              <label htmlFor="log-agent" className="field-label">智能体</label>
              <Select
                id="log-agent"
                value={filters.agentId}
                onChange={(event) => setFilters({ ...filters, agentId: event.target.value })}
                options={[{ value: 'all', label: '全部智能体' }, ...agentOptions]}
                className="w-[200px]"
              />
            </div>
            <p className="pb-2 text-xs text-ink-muted">共 <span className="font-semibold text-brand">{total}</span> 条匹配记录</p>
          </div>
        )}

        {total === 0 ? (
          <EmptyState title="没有找到匹配的日志" description="当前时间范围内没有记录，试着调整关键词、类型或筛选条件；当前筛选条件会保留。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left">
              <thead className="table-head">
                <tr>
                  {['时间', '类型', '调用对象 / 摘要', 'Agent', 'Run ID', '耗时', '状态', ''].map((head) => <th key={head} className="px-3 py-2.5 font-medium">{head}</th>)}
                </tr>
              </thead>
              <tbody className="divide-y divide-line/70">
                {rows.map((log) => (
                  <tr key={log.logId} onClick={() => openDetail(log.logId)} className="cursor-pointer transition-colors hover:bg-brand-faint">
                    <td className="px-3 py-2.5">
                      <p className="text-xs tabular-nums text-ink">{formatTime(log.timestamp)}</p>
                      <p className="text-2xs text-ink-faint">{formatDate(log.timestamp)}</p>
                    </td>
                    <td className="px-3 py-2.5"><span className="flex items-center gap-2"><LogTypeIcon type={log.type} /><span className="text-2xs text-ink-muted">{LOG_TYPE_LABEL[log.type]}</span></span></td>
                    <td className="max-w-[280px] px-3 py-2.5">
                      <p className="truncate text-xs font-medium text-ink">{log.name}</p>
                      <p className="mt-0.5 truncate text-2xs text-ink-faint">{log.summary}</p>
                    </td>
                    <td className="px-3 py-2.5 text-2xs text-ink-muted">{data.agents.find((agent) => agent.id === log.agentId)?.name ?? '—'}</td>
                    <td className="px-3 py-2.5 font-mono text-2xs text-ink-muted">{log.runId ?? '—'}</td>
                    <td className="px-3 py-2.5 text-2xs tabular-nums text-ink-muted">{formatDuration(log.durationMs)}</td>
                    <td className="px-3 py-2.5"><RunStatusBadge status={log.status} /></td>
                    <td className="px-3 py-2.5"><ChevronRight className="h-3.5 w-3.5 text-ink-faint" /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Pagination page={page} pageCount={pageCount} total={total} onChange={setPage} />
      </SectionCard>

      <div className="mt-3 flex flex-wrap items-center gap-2 text-2xs text-ink-faint">
        <span>默认按时间倒序，点击任意行查看完整执行链路。</span>
        <CopyButton value={rows.map((log) => log.logId).join(', ')} label="复制当前结果 Log ID" message="Log ID 列表已复制" />
      </div>

      {logIdParam && <LogDetailDrawer logId={logIdParam} onClose={closeDetail} />}
    </div>
  )
}
