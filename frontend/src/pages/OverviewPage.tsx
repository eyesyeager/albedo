import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Activity, ArrowUpRight, Blocks, Bot, Cpu, Layers3, Plug, Plus, Sparkles, TrendingUp, Wrench } from 'lucide-react'
import { cn } from '../lib/cn'
import { formatNumber, formatPercent, formatTime } from '../lib/format'
import { adminApi } from '../lib/api'
import { getResourceHealth, LOG_TYPE_LABEL } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import type { OverviewStats, TrendPoint } from '../types/domain'
import { Badge, Button, Select } from '../components/ui'
import { SectionCard } from '../components/common/PageHeader'
import { TrendChart } from '../components/common/TrendChart'

function MetricCard({ label, value, sub, icon, tone }: { label: string; value: string; sub: string; icon: ReactNode; tone: string }) {
  return (
    <div className="card p-4 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-pop">
      <div className="flex items-start justify-between">
        <p className="text-xs text-ink-muted">{label}</p>
        <span className={cn('flex h-8 w-8 items-center justify-center rounded-field', tone)}>{icon}</span>
      </div>
      <p className="mt-2 text-2xl font-semibold tracking-tight text-ink">{value}</p>
      <p className="mt-1 text-2xs text-ink-faint">{sub}</p>
    </div>
  )
}

const STATUS_DOT = {
  success: 'bg-ok',
  failed: 'bg-danger',
  timeout: 'bg-warn',
  running: 'bg-brand',
}

const EMPTY_STATS: OverviewStats = {
  agentsTotal: 0, agentsPublished: 0, todayCalls: 0, callsChangePercent: 0,
  modelsTotal: 0, modelsEnabled: 0, successRate: 0, avgDurationMs: 0,
  avgDurationChange: 0, abnormalCount: 0, typeCounts: {},
}

export function OverviewPage() {
  const snapshot = useWorkspace()
  const config = useWorkspace((state) => state.systemConfig)
  const navigate = useNavigate()
  const [days, setDays] = useState(14)
  const [stats, setStats] = useState<OverviewStats | null>(null)
  const [trend, setTrend] = useState<TrendPoint[]>([])

  useEffect(() => {
    let canceled = false
    adminApi.stats.overview()
      .then((value) => { if (!canceled) setStats(value) })
      .catch(() => { if (!canceled) setStats(null) })
    return () => { canceled = true }
  }, [snapshot.logs.length])

  useEffect(() => {
    let canceled = false
    adminApi.stats.trends(days)
      .then((value) => { if (!canceled) setTrend(value) })
      .catch(() => { if (!canceled) setTrend([]) })
    return () => { canceled = true }
  }, [days, snapshot.logs.length])

  const shown = stats ?? EMPTY_STATS
  const health = getResourceHealth(snapshot)
  const hour = new Date().getHours()
  const greeting = hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'
  const user = snapshot.settings.workspaceName
  const healthy = health.abnormalCount === 0

  const resourceRows = [
    { label: '模型服务', icon: <Cpu className="h-3.5 w-3.5" />, tone: 'bg-brand-soft text-brand', value: `${health.modelsEnabled} / ${health.modelsTotal}`, sub: `平均延迟 ${health.modelLatencyMs} ms` },
    { label: 'MCP 服务', icon: <Plug className="h-3.5 w-3.5" />, tone: 'bg-emerald-50 text-emerald-600', value: `${health.mcpEnabled} / ${health.mcpTotal}`, sub: `${health.availableTools} 个可用工具` },
    { label: 'Skills', icon: <Blocks className="h-3.5 w-3.5" />, tone: 'bg-orange-50 text-orange-600', value: `${health.skillsTotal}`, sub: `最近更新${health.skillsLatest}` },
    { label: '内置工具', icon: <Wrench className="h-3.5 w-3.5" />, tone: 'bg-blue-50 text-blue-600', value: `${health.toolsEnabled} / ${health.toolsTotal}`, sub: `今日调用 ${health.toolsTodayCalls} 次` },
  ]

  return (
    <div className="mx-auto max-w-[1400px]">
      <div className="mb-6">
        <p className="text-sm text-ink-muted">{greeting}，{user}</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight text-ink">你的 AI 工作台运行良好</h1>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="智能体" value={formatNumber(shown.agentsTotal)} sub={`${shown.agentsPublished} 个已发布`} icon={<Bot className="h-4 w-4 text-brand" />} tone="bg-brand-soft" />
        <MetricCard label="今日调用" value={formatNumber(shown.todayCalls)} sub={`↗ 较昨日 +${formatPercent(shown.callsChangePercent)}`} icon={<TrendingUp className="h-4 w-4 text-emerald-600" />} tone="bg-emerald-50" />
        <MetricCard label="模型资源" value={formatNumber(shown.modelsTotal)} sub={`${shown.modelsEnabled} 个运行中`} icon={<Layers3 className="h-4 w-4 text-blue-600" />} tone="bg-blue-50" />
        <MetricCard label="成功率" value={formatPercent(shown.successRate)} sub="过去 24 小时" icon={<Activity className="h-4 w-4 text-orange-500" />} tone="bg-orange-50" />
      </div>

      <div className="mt-5 grid grid-cols-1 gap-4 lg:grid-cols-[1.7fr_1fr]">
        <SectionCard
          title="调用趋势"
          description={`近 ${days} 天所有能力调用次数`}
          actions={
            <Select
              aria-label="趋势时间范围"
              value={String(days)}
              onChange={(event) => setDays(Number(event.target.value))}
              options={config.options.trendRanges.map((item) => ({ value: item.value, label: item.label }))}
              className="h-8 w-[132px] py-1 text-xs"
            />
          }
          bodyClassName="px-3 pb-3 pt-2"
        >
          <TrendChart data={trend} />
        </SectionCard>

        <SectionCard
          title="资源状态"
          description="能力资源实时概览"
          actions={<Badge tone={healthy ? 'ok' : 'warn'} dot>{healthy ? '全部正常' : `${health.abnormalCount} 项异常`}</Badge>}
          bodyClassName="p-2"
        >
          <div className="divide-y divide-line/70">
            {resourceRows.map((row) => (
              <div key={row.label} className="flex items-center gap-3 px-3 py-2.5">
                <span className={cn('flex h-8 w-8 items-center justify-center rounded-field', row.tone)}>{row.icon}</span>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-medium text-ink">{row.label}</p>
                  <p className="mt-0.5 truncate text-2xs text-ink-faint">{row.sub}</p>
                </div>
                <span className="text-sm font-medium tabular-nums text-ink">{row.value}</span>
              </div>
            ))}
          </div>
        </SectionCard>
      </div>

      <div className="mt-5 grid grid-cols-1 gap-4 lg:grid-cols-[1.7fr_1fr]">
        <SectionCard
          title="最近活动"
          description="工作区最新调用记录"
          actions={<Button variant="ghost" size="sm" onClick={() => navigate('/logs')}>查看全部 →</Button>}
          bodyClassName="p-2"
        >
          <div className="divide-y divide-line/70">
            {snapshot.logs.slice(0, config.limits.activityMaxItems || 5).map((log) => (
              <button
                key={log.logId}
                type="button"
                onClick={() => navigate(`/logs?logId=${log.logId}`)}
                className="flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors hover:bg-brand-faint"
              >
                <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', STATUS_DOT[log.status])} aria-hidden />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-xs font-medium text-ink">{log.name}</span>
                  <span className="block truncate text-2xs text-ink-faint">{log.summary}</span>
                </span>
                <Badge tone="neutral">{LOG_TYPE_LABEL[log.type]}</Badge>
                <span className="shrink-0 text-2xs tabular-nums text-ink-faint">{formatTime(log.timestamp)}</span>
              </button>
            ))}
          </div>
        </SectionCard>

        <div className="space-y-4">
          <div className="relative overflow-hidden rounded-card border border-brand/10 bg-gradient-to-br from-[#F2EFFF] via-[#F7F5FF] to-[#EAF7F4] p-5">
            <span className="absolute -right-6 -top-6 h-24 w-24 rounded-full bg-brand/10 blur-2xl" aria-hidden />
            <Sparkles className="relative h-5 w-5 text-brand" aria-hidden />
            <h2 className="relative mt-3 text-sm font-semibold text-ink">创建你的下一个智能体</h2>
            <p className="relative mt-1.5 text-xs leading-relaxed text-ink-muted">组合模型、MCP 与 Skills，几分钟即可跑通一个可用的智能体。</p>
            <button
              type="button"
              onClick={() => navigate('/agents')}
              className="relative mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-brand transition-all hover:gap-2.5"
            >
              开始创建 <ArrowUpRight className="h-3.5 w-3.5" />
            </button>
          </div>

          <SectionCard title="快速入口" bodyClassName="p-3">
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" size="sm" icon={<Plus className="h-3.5 w-3.5" />} onClick={() => navigate('/resources/models')}>接入模型</Button>
              <Button variant="outline" size="sm" icon={<Plug className="h-3.5 w-3.5" />} onClick={() => navigate('/resources/mcp')}>连接 MCP</Button>
            </div>
          </SectionCard>
        </div>
      </div>
    </div>
  )
}
