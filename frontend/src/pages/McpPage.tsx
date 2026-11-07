import { useMemo, useState } from 'react'
import { Plus, Plug, Search } from 'lucide-react'
import type { McpTransport } from '../types/domain'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { affectedAgents } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import { Button, ConfirmDialog, EmptyState, Input, Select, toast } from '../components/ui'
import { PageHeader } from '../components/common/PageHeader'
import { McpCard, TRANSPORT_LABEL } from './mcp/McpCard'
import { CreateMcpDialog } from './mcp/CreateMcpDialog'
import { McpDetailDrawer, type McpTab } from './mcp/McpDetailDrawer'

export function McpPage() {
  const data = useWorkspace()
  const mcps = data.mcps
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [transport, setTransport] = useState<'all' | McpTransport>('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [detail, setDetail] = useState<{ id: string; tab: McpTab } | null>(null)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const deleteMcp = deleteId ? mcps.find((item) => item.id === deleteId) : undefined
  const deleteAffected = deleteMcp ? affectedAgents(data, 'mcp', deleteMcp.id) : []

  const requestDelete = (id: string) => setDeleteId(id)

  const confirmDelete = () => {
    if (!deleteId || deleting) return
    setDeleting(true)
    api.mcp.remove(deleteId)
      .then(() => {
        toast.success('MCP 已删除，工具清单与调用凭证同步销毁')
        setDeleteId(null)
        setDetail(null)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeleting(false))
  }

  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return mcps.filter((mcp) => {
      if (transport !== 'all' && mcp.transport !== transport) return false
      if (!query) return true
      return `${mcp.name} ${mcp.description}`.toLowerCase().includes(query)
    })
  }, [mcps, debounced, transport])

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="MCP 管理"
        description={`接入、测试并管理外部工具服务，共 ${mcps.length} 个 MCP 服务器`}
        actions={<Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setCreateOpen(true)}>添加 MCP 服务器</Button>}
      />

      <div className="card flex flex-wrap items-center gap-2.5 p-3">
        <Input
          aria-label="搜索 MCP 服务器"
          placeholder="搜索服务器名称或描述…"
          leading={<Search className="h-3.5 w-3.5" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          className="min-w-[240px] flex-1"
        />
        <Select
          aria-label="连接类型筛选"
          value={transport}
          onChange={(event) => setTransport(event.target.value as 'all' | McpTransport)}
          options={[
            { value: 'all', label: '全部连接类型' },
            { value: 'sse', label: 'SSE' },
            { value: 'streamable_http', label: 'Streamable HTTP' },
            { value: 'stdio', label: 'stdio' },
          ]}
          className="w-[180px]"
        />
        <span className="text-xs text-ink-muted">共 {filtered.length} 个结果</span>
      </div>

      {filtered.length === 0 ? (
        <div className="card mt-4">
          <EmptyState
            icon={<Plug className="h-5 w-5" />}
            title={mcps.length === 0 ? '还没有 MCP 服务器' : '没有找到匹配的服务器'}
            description={mcps.length === 0 ? '接入第一个 MCP 服务，让智能体具备调用外部工具的能力。' : '调整关键词或连接类型筛选条件后重试。'}
            action={mcps.length === 0 && <Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setCreateOpen(true)}>添加 MCP 服务器</Button>}
          />
        </div>
      ) : (
        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((mcp) => (
            <McpCard
              key={mcp.id}
              mcp={mcp}
              onConfig={() => setDetail({ id: mcp.id, tab: 'general' })}
              onTools={() => setDetail({ id: mcp.id, tab: 'tools' })}
              onDelete={() => requestDelete(mcp.id)}
            />
          ))}
        </div>
      )}

      <p className="mt-5 text-2xs text-ink-faint">
        支持 {Object.values(TRANSPORT_LABEL).join('、')} 三种连接类型；自定义 Header 在调用前由平台注入，不会暴露给模型。
      </p>

      <CreateMcpDialog open={createOpen} onClose={() => setCreateOpen(false)} />
      {detail && (
        <McpDetailDrawer mcpId={detail.id} initialTab={detail.tab} onClose={() => setDetail(null)} onDelete={requestDelete} />
      )}

      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={confirmDelete}
        title={`删除「${deleteMcp?.name ?? ''}」？`}
        description="删除后该 MCP 服务器的工具清单、调用记录与请求头凭证将一并销毁，操作不可恢复，操作记录会写入系统日志。"
        confirmText="确认删除"
        danger
        loading={deleting}
        impacts={[
          `受影响智能体：${deleteAffected.length} 个${deleteAffected.length ? `（${deleteAffected.map((agent) => agent.name).join('、')}）` : ''}，挂载引用会被移除`,
          `可用工具：${deleteMcp?.tools.filter((tool) => tool.enabled).length ?? 0} 个，删除后全部不可调用`,
          '自定义 Header 与 stdio 环境变量凭证随服务一并清除，恢复需重新接入并测试连接',
        ]}
      />
    </div>
  )
}
