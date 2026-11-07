import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bot, Plus, RotateCcw, Search, SlidersHorizontal } from 'lucide-react'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { isAgentPublished } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import { Badge, Button, ConfirmDialog, EmptyState, Input, Segmented, toast } from '../components/ui'
import { PageHeader } from '../components/common/PageHeader'
import { AgentCard } from './agents/AgentCard'
import { CreateAgentDialog } from './agents/CreateAgentDialog'
import { FilterPanel } from './agents/FilterPanel'

type StatusFilter = 'all' | 'published' | 'draft'

export function AgentsPage() {
  const navigate = useNavigate()
  const agents = useWorkspace((state) => state.agents)
  const models = useWorkspace((state) => state.models)
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [status, setStatus] = useState<StatusFilter>('all')
  const [modelFilter, setModelFilter] = useState('all')
  const [filterOpen, setFilterOpen] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  const hasFilters = Boolean(keyword.trim()) || status !== 'all' || modelFilter !== 'all'
  const deleteAgent = agents.find((agent) => agent.id === deleteId) ?? null

  const confirmDelete = () => {
    if (!deleteId || deleting) return
    const name = deleteAgent?.name ?? ''
    setDeleting(true)
    api.agents
      .remove(deleteId)
      .then(() => {
        toast.success(`已删除智能体「${name}」，挂载配置与凭证同步清理`)
        setDeleteId(null)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeleting(false))
  }

  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return agents.filter((agent) => {
      if (status === 'published' && !isAgentPublished(agent)) return false
      if (status === 'draft' && isAgentPublished(agent)) return false
      if (modelFilter !== 'all' && agent.defaultModelId !== modelFilter) return false
      if (!query) return true
      return `${agent.name} ${agent.description}`.toLowerCase().includes(query)
    })
  }, [agents, debounced, status, modelFilter])

  const reset = () => {
    setKeyword('')
    setStatus('all')
    setModelFilter('all')
  }

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="智能体"
        description={`管理工作区内的智能体资产，共 ${agents.length} 个`}
        actions={<Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setCreateOpen(true)}>创建智能体</Button>}
      />

      <div className="card p-3">
        <div className="flex flex-wrap items-center gap-2.5">
          <Input
            className="min-w-[240px] flex-1"
            aria-label="搜索智能体"
            placeholder="搜索名称或功能介绍…"
            leading={<Search className="h-3.5 w-3.5" />}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Segmented
            value={status}
            onChange={(value) => setStatus(value as StatusFilter)}
            items={[
              { key: 'all', label: '全部', count: agents.length },
              { key: 'published', label: '已发布', count: agents.filter(isAgentPublished).length },
              { key: 'draft', label: '草稿', count: agents.filter((agent) => !isAgentPublished(agent)).length },
            ]}
          />
          <Button
            variant={filterOpen ? 'secondary' : 'outline'}
            icon={<SlidersHorizontal className="h-3.5 w-3.5" />}
            onClick={() => setFilterOpen((open) => !open)}
          >
            筛选
            {hasFilters && <span className="ml-0.5 h-1.5 w-1.5 rounded-full bg-brand" aria-label="筛选生效" />}
          </Button>
          <Button variant="ghost" icon={<RotateCcw className="h-3.5 w-3.5" />} disabled={!hasFilters} onClick={reset}>重置筛选</Button>
        </div>
      </div>

      <FilterPanel
        open={filterOpen}
        models={models}
        modelFilter={modelFilter}
        onModelFilterChange={setModelFilter}
        resultCount={filtered.length}
        onReset={reset}
        hasFilters={hasFilters}
      />

      <div className="mt-4 flex items-center justify-between">
        <p className="text-xs text-ink-muted">
          共 <span className="font-medium text-ink">{filtered.length}</span> 个智能体
        </p>
        <Badge tone="neutral">{agents.filter(isAgentPublished).length} 个已发布</Badge>
      </div>

      {filtered.length === 0 ? (
        <div className="card mt-3">
          <EmptyState
            icon={<Bot className="h-5 w-5" />}
            title={agents.length === 0 ? '还没有智能体' : '没有找到匹配的智能体'}
            description={agents.length === 0 ? '创建第一个智能体，组合模型与工具能力开始使用。' : '试着调整关键词、状态或默认模型筛选条件。'}
            action={agents.length === 0
              ? <Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setCreateOpen(true)}>创建智能体</Button>
              : <Button variant="outline" onClick={reset}>清除筛选</Button>}
          />
        </div>
      ) : (
        <div className="mt-3 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((agent) => (
            <AgentCard
              key={agent.id}
              agent={agent}
              models={models}
              onClick={() => navigate(`/agents/${agent.id}`)}
              onDelete={() => setDeleteId(agent.id)}
            />
          ))}
        </div>
      )}

      <CreateAgentDialog open={createOpen} onClose={() => setCreateOpen(false)} />

      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={confirmDelete}
        title={`删除智能体 · ${deleteAgent?.name ?? ''}`}
        description="删除后该智能体及其测试会话、能力挂载与访问凭证都将不可恢复，操作会写入系统操作日志。"
        confirmText="删除"
        danger
        loading={deleting}
        impacts={[
          deleteAgent && (deleteAgent.apiEnabled || deleteAgent.aguiEnabled) ? '已开启的 API / AGUI 对外接口将立即不可用' : '该智能体将不再出现在列表中',
          '已挂载的 MCP、Skill、工具与模型配置会一并解除',
          '测试会话、访问凭证与对话历史会被清除',
        ]}
      />
    </div>
  )
}
