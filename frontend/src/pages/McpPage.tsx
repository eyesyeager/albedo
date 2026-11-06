import { useMemo, useState } from 'react'
import { Plus, Plug, Search } from 'lucide-react'
import type { McpTransport } from '../types/domain'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { useWorkspace } from '../store/workspace'
import { Button, EmptyState, Input, Select } from '../components/ui'
import { PageHeader } from '../components/common/PageHeader'
import { McpCard, TRANSPORT_LABEL } from './mcp/McpCard'
import { CreateMcpDialog } from './mcp/CreateMcpDialog'
import { McpDetailDrawer, type McpTab } from './mcp/McpDetailDrawer'

export function McpPage() {
  const mcps = useWorkspace((state) => state.mcps)
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [transport, setTransport] = useState<'all' | McpTransport>('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [detail, setDetail] = useState<{ id: string; tab: McpTab } | null>(null)

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
            />
          ))}
        </div>
      )}

      <p className="mt-5 text-2xs text-ink-faint">
        支持 {Object.values(TRANSPORT_LABEL).join('、')} 三种连接类型；自定义 Header 在调用前由平台注入，不会暴露给模型。
      </p>

      <CreateMcpDialog open={createOpen} onClose={() => setCreateOpen(false)} />
      {detail && (
        <McpDetailDrawer mcpId={detail.id} initialTab={detail.tab} onClose={() => setDetail(null)} />
      )}
    </div>
  )
}
