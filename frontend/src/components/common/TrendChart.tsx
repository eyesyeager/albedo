import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import type { TrendPoint } from '../../types/domain'
import { formatNumber } from '../../lib/format'
import { EmptyState } from '../ui'

interface TooltipProps {
  active?: boolean
  payload?: Array<{ payload?: TrendPoint }>
}

function ChartTooltip({ active, payload }: TooltipProps) {
  const point = payload?.[0]?.payload
  if (!active || !point) return null
  return (
    <div className="rounded-field border border-line bg-white px-3 py-2 shadow-pop">
      <p className="text-xs text-ink-muted">{point.label}</p>
      <p className="mt-0.5 text-sm font-semibold text-ink">{formatNumber(point.count)} 次调用</p>
      <p className="mt-0.5 text-2xs text-ink-faint">按自然日聚合 · 含 Agent、模型与工具运行</p>
    </div>
  )
}

export function TrendChart({ data, height = 210 }: { data: TrendPoint[]; height?: number }) {
  if (!data.length || data.every((point) => point.count === 0)) {
    return <EmptyState compact title="暂无调用数据" description="产生首次调用后，这里会按自然日展示调用趋势。" />
  }

  return (
    <div style={{ height }} className="w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 10, right: 8, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="albedoTrend" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#6D5DF6" stopOpacity={0.28} />
              <stop offset="100%" stopColor="#6D5DF6" stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke="#EFF0F5" strokeDasharray="3 3" />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 10, fill: '#9CA3AF' }}
            axisLine={false}
            tickLine={false}
            interval={Math.max(0, Math.floor(data.length / 5) - 1)}
            dy={8}
          />
          <YAxis tick={{ fontSize: 10, fill: '#9CA3AF' }} axisLine={false} tickLine={false} width={48} />
          <Tooltip content={<ChartTooltip />} cursor={{ stroke: '#8B7CFF', strokeDasharray: '3 3' }} />
          <Area type="monotone" dataKey="count" stroke="#6D5DF6" strokeWidth={2} fill="url(#albedoTrend)" dot={false} isAnimationActive={false} activeDot={{ r: 4, fill: '#6D5DF6', stroke: '#fff', strokeWidth: 2 }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
