import { useMemo, useState } from 'react'
import { Boxes, Code2, FileText, Globe2, Image, Link2, Search, ShieldCheck, Timer } from 'lucide-react'
import type { BuiltinTool } from '../types/domain'
import { cn } from '../lib/cn'
import { formatNumber } from '../lib/format'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { affectedAgents } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import { Badge, Button, ConfirmDialog, EmptyState, Input, Select, Switch, toast } from '../components/ui'
import { PageHeader, SectionCard } from '../components/common/PageHeader'

const TOOL_ICONS: Record<string, typeof Globe2> = {
  web_search: Globe2,
  code_interpreter: Code2,
  file_parser: FileText,
  current_time: Timer,
  image_inspector: Image,
  http_request: Link2,
}

export function ToolsPage() {
  const data = useWorkspace()
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [status, setStatus] = useState<'all' | 'enabled' | 'disabled'>('all')
  const [pending, setPending] = useState<{ id: string; enabled: boolean } | null>(null)
  const [busy, setBusy] = useState(false)

  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return data.tools.filter((tool) => {
      if (status === 'enabled' && !tool.enabled) return false
      if (status === 'disabled' && tool.enabled) return false
      if (!query) return true
      return `${tool.name} ${tool.key} ${tool.description}`.toLowerCase().includes(query)
    })
  }, [data.tools, debounced, status])

  const affected = pending ? affectedAgents(data, 'tool', pending.id) : []
  const pendingTool = pending ? data.tools.find((tool) => tool.id === pending.id) : undefined

  const applyToggle = (id: string, enabled: boolean) => {
    setBusy(true)
    api.tools
      .toggle(id, enabled)
      .then(() => {
        const tool = useWorkspace.getState().tools.find((item) => item.id === id)
        toast.success(`${tool?.name ?? '内置工具'}已${enabled ? '启用' : '停用'}`)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => { setBusy(false); setPending(null) })
  }

  const requestToggle = (tool: BuiltinTool, enabled: boolean) => {
    if (!enabled) {
      setPending({ id: tool.id, enabled })
      return
    }
    applyToggle(tool.id, enabled)
  }

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="内置工具"
        description="平台内置通用工具，仅支持查看与全局启用/停用"
        tag="平台内置"
        actions={<Badge tone="brand"><ShieldCheck className="h-3 w-3" />由平台统一维护</Badge>}
      />

      <div className="card flex flex-wrap items-center gap-2.5 p-3">
        <Input
          aria-label="搜索内置工具"
          placeholder="搜索工具名称、Tool Key 或功能说明…"
          leading={<Search className="h-3.5 w-3.5" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          className="min-w-[260px] flex-1"
        />
        <Select
          aria-label="状态筛选"
          value={status}
          onChange={(event) => setStatus(event.target.value as 'all' | 'enabled' | 'disabled')}
          options={[
            { value: 'all', label: '全部状态' },
            { value: 'enabled', label: '已启用' },
            { value: 'disabled', label: '已停用' },
          ]}
          className="w-[140px]"
        />
        <span className="text-xs text-ink-muted">共 {filtered.length} 个结果</span>
      </div>

      {filtered.length === 0 ? (
        <div className="card mt-4">
          <EmptyState icon={<Boxes className="h-5 w-5" />} title="没有找到匹配的工具" description="调整关键词或状态筛选条件后重试。" />
        </div>
      ) : (
        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((tool) => {
            const Icon = TOOL_ICONS[tool.key] ?? Boxes
            return (
              <div key={tool.id} className={cn('card flex flex-col p-4 transition-all duration-200', !tool.enabled && 'bg-canvas/60 opacity-70', tool.enabled && 'hover:-translate-y-0.5 hover:shadow-pop')}>
                <div className="flex items-start gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[11px] bg-brand-soft text-brand"><Icon className="h-4 w-4" aria-hidden /></span>
                  <div className="min-w-0 flex-1">
                    <h3 className="truncate text-md font-semibold text-ink">{tool.name}</h3>
                    <code className="font-mono text-2xs text-brand">{tool.key}</code>
                  </div>
                  <Switch label={`${tool.name} 全局开关`} checked={tool.enabled} onChange={(checked) => requestToggle(tool, checked)} />
                </div>
                <p className="mt-3 line-clamp-3 min-h-[54px] text-xs leading-relaxed text-ink-muted">{tool.description}</p>
                <div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-line pt-3">
                  <Badge tone="neutral">{tool.version}</Badge>
                  <Badge tone="neutral">累计调用 {formatNumber(tool.callCount)}</Badge>
                  <Badge tone={tool.enabled ? 'ok' : 'warn'} dot>{tool.enabled ? '可挂载' : '不可用'}</Badge>
                </div>
              </div>
            )
          })}
        </div>
      )}

      <SectionCard title="内置工具由平台统一维护" className="mt-5">
        <p className="text-xs leading-relaxed text-ink-muted">
          内置工具由平台研发人员在代码层开发、注册与维护，管理界面不支持新增或编辑工具逻辑。关闭全局开关后，所有已挂载该工具的智能体会同步显示「不可用」，新智能体不可挂载；恢复启用后原挂载关系无需重新配置即可继续使用。
        </p>
      </SectionCard>

      <ConfirmDialog
        open={pending !== null}
        onClose={() => setPending(null)}
        onConfirm={() => { if (pending) applyToggle(pending.id, pending.enabled) }}
        title={`停用「${pendingTool?.name ?? ''}」？`}
        description="这是全局开关，停用后所有已挂载该工具的智能体在运行时的相关调用会立即失效。"
        confirmText="确认停用"
        danger
        loading={busy}
        impacts={[
          `受影响智能体：${affected.length} 个${affected.length ? `（${affected.map((agent) => agent.name).join('、')}）` : ''}`,
          '已有 Agent 中的挂载引用会保留并显示「不可用」，恢复启用后可直接使用',
          '工具逻辑不可在管理界面编辑，平台版本发布时统一更新',
        ]}
      />
    </div>
  )
}
