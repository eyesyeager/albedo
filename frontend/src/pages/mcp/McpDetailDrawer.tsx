import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, Clock3 } from 'lucide-react'
import { formatDateTime, formatDuration } from '../../lib/format'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { affectedAgents } from '../../store/selectors'
import { Button, ConfirmDialog, Drawer, EmptyState, Field, Input, Select, Switch, Tabs, Textarea, toast } from '../../components/ui'
import { RunStatusBadge } from '../../components/common/StatusBadge'
import { TRANSPORT_LABEL } from './McpCard'

export type McpTab = 'general' | 'tools' | 'usage' | 'calls'

export function McpDetailDrawer({ mcpId, initialTab, onClose }: { mcpId: string | null; initialTab: McpTab; onClose: () => void }) {
  const navigate = useNavigate()
  const data = useWorkspace()
  const defaults = useWorkspace((state) => state.systemConfig.defaults.mcp)
  const mcp = data.mcps.find((item) => item.id === mcpId)
  const [tab, setTab] = useState<McpTab>(initialTab)
  const [form, setForm] = useState({ name: '', description: '', endpoint: '', enabled: true })
  const [qpm, setQpm] = useState(defaults.qpm)
  const [concurrency, setConcurrency] = useState(defaults.maxConcurrency)
  const [saving, setSaving] = useState(false)
  const [confirmDisable, setConfirmDisable] = useState(false)

  useEffect(() => setTab(initialTab), [mcpId, initialTab])
  useEffect(() => {
    const current = useWorkspace.getState().mcps.find((item) => item.id === mcpId)
    if (!current) return
    setForm({ name: current.name, description: current.description, endpoint: current.endpoint, enabled: current.enabled })
    setQpm(current.qpm)
    setConcurrency(current.maxConcurrency)
  }, [mcpId])

  if (!mcp) return null

  const affected = affectedAgents(data, 'mcp', mcp.id)

  const performSave = () => {
    setSaving(true)
    api.mcp
      .update(mcp.id, { name: form.name, description: form.description, endpoint: form.endpoint })
      .then(() => (form.enabled !== mcp.enabled ? api.mcp.toggle(mcp.id, form.enabled) : undefined))
      .then(() => {
        toast.success(form.enabled !== mcp.enabled ? `MCP 已${form.enabled ? '启用' : '停用'}，配置已保存` : 'MCP 配置已保存')
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 保存 MCP 配置失败：', message)
        toast.error(message)
      })
      .finally(() => setSaving(false))
  }

  const saveGeneral = () => {
    if (mcp.enabled && !form.enabled) {
      setConfirmDisable(true)
      return
    }
    performSave()
  }

  const saveUsage = () => {
    setSaving(true)
    api.mcp
      .updateUsage(mcp.id, qpm, concurrency)
      .then(() => toast.success('使用配置已保存'))
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setSaving(false))
  }

  const calls = data.logs.filter((log) => log.resourceId === mcp.id && log.type === 'mcp').slice(0, 10)

  return (
    <Drawer
      open
      onClose={onClose}
      title={mcp.name}
      subtitle={`${TRANSPORT_LABEL[mcp.transport]} · ${mcp.endpoint}`}
      width="lg"
      dirty={!confirmDisable && (form.name !== mcp.name || form.description !== mcp.description || form.endpoint !== mcp.endpoint || form.enabled !== mcp.enabled || qpm !== mcp.qpm || concurrency !== mcp.maxConcurrency)}
    >
      <Tabs
        className="mb-5"
        value={tab}
        onChange={(value) => setTab(value as McpTab)}
        items={[
          { key: 'general', label: '通用配置' },
          { key: 'tools', label: '工具管理', count: mcp.tools.length },
          { key: 'usage', label: '使用配置' },
          { key: 'calls', label: '调用记录' },
        ]}
      />

      {tab === 'general' && (
        <div className="space-y-4">
          <Field label="名称" htmlFor="mcp-detail-name" required>
            <Input id="mcp-detail-name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
          </Field>
          <Field label="描述" htmlFor="mcp-detail-desc">
            <Textarea id="mcp-detail-desc" rows={2} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
          </Field>
          <Field label="服务地址 / 命令" htmlFor="mcp-detail-endpoint" required>
            <Input id="mcp-detail-endpoint" className="font-mono text-xs" value={form.endpoint} onChange={(event) => setForm({ ...form, endpoint: event.target.value })} />
          </Field>
          <Field label="连接类型" hint="创建后不可直接编辑；如需切换请新建服务并重新测试连接">
            <Select aria-label="连接类型" value={mcp.transport} disabled options={Object.entries(TRANSPORT_LABEL).map(([value, label]) => ({ value, label }))} />
          </Field>
          <div className="flex items-center justify-between rounded-field border border-line px-3 py-2.5">
            <div>
              <p className="text-xs font-medium text-ink">启用状态</p>
              <p className="mt-0.5 text-2xs text-ink-faint">停用后所有已挂载 Agent 无法调用该 MCP，配置引用仍保留</p>
            </div>
            <Switch label="启用 MCP" checked={form.enabled} onChange={(checked) => setForm({ ...form, enabled: checked })} />
          </div>
          <Button block loading={saving} onClick={saveGeneral}>保存更改</Button>
        </div>
      )}

      {tab === 'tools' && (
        <div className="divide-y divide-line rounded-field border border-line">
          {mcp.tools.map((tool) => (
            <div key={tool.id} className="flex items-start justify-between gap-3 px-3 py-3">
              <div className="min-w-0">
                <p className="truncate text-xs font-medium text-ink">{tool.name}</p>
                <code className="font-mono text-2xs text-brand">{tool.key}</code>
                <p className="mt-1 text-2xs leading-relaxed text-ink-muted">{tool.description}</p>
              </div>
              <Switch
                label={`${tool.name} 启用开关`}
                checked={tool.enabled}
                onChange={(checked) => {
                  api.mcp.toggleTool(mcp.id, tool.id, checked).then(() => toast.success(`工具「${tool.name}」已${checked ? '启用' : '停用'}`))
                }}
              />
            </div>
          ))}
        </div>
      )}

      {tab === 'usage' && (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <Field label="每分钟调用额度" htmlFor="mcp-qpm" hint={`QPM，平台默认 ${defaults.qpm}`}>
              <Input id="mcp-qpm" type="number" min={1} value={qpm} onChange={(event) => setQpm(Number(event.target.value))} />
            </Field>
            <Field label="最大并发数" htmlFor="mcp-concurrency" hint={`平台默认 ${defaults.maxConcurrency}`}>
              <Input id="mcp-concurrency" type="number" min={1} value={concurrency} onChange={(event) => setConcurrency(Number(event.target.value))} />
            </Field>
          </div>
          <div className="flex items-start gap-2 rounded-field bg-warn/10 px-3 py-2.5 text-2xs leading-relaxed text-warn">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>超额请求进入短队列；超过服务超时时间（{mcp.timeoutSeconds} 秒）后返回限流或超时错误，并在调用日志中记录状态。</span>
          </div>
          <Button block loading={saving} onClick={saveUsage}>保存使用配置</Button>
        </div>
      )}

      {tab === 'calls' && (
        calls.length === 0 ? (
          <EmptyState compact title="暂无调用记录" description="该 MCP 的工具被调用后，记录会出现在这里。" />
        ) : (
          <div className="divide-y divide-line rounded-field border border-line">
            {calls.map((log) => (
              <button
                key={log.logId}
                type="button"
                onClick={() => { onClose(); navigate(`/logs?logId=${log.logId}`) }}
                className="flex w-full items-center gap-3 px-3 py-3 text-left transition-colors hover:bg-brand-faint"
              >
                {log.status === 'success' ? <CheckCircle2 className="h-4 w-4 text-ok" /> : <Clock3 className="h-4 w-4 text-warn" />}
                <div className="min-w-0 flex-1">
                  <p className="truncate text-xs font-medium text-ink">{log.name}</p>
                  <p className="mt-0.5 truncate text-2xs text-ink-faint">{log.summary}</p>
                </div>
                <div className="shrink-0 text-right">
                  <RunStatusBadge status={log.status} />
                  <p className="mt-1 text-2xs tabular-nums text-ink-faint">{formatDuration(log.durationMs)} · {formatDateTime(log.timestamp)}</p>
                </div>
              </button>
            ))}
          </div>
        )
      )}

      <ConfirmDialog
        open={confirmDisable}
        onClose={() => setConfirmDisable(false)}
        onConfirm={() => { setConfirmDisable(false); performSave() }}
        title={`停用「${mcp.name}」？`}
        description="停用后所有已挂载该 MCP 的智能体无法调用其工具，运行时会返回明确的资源不可用错误。"
        confirmText="确认停用"
        danger
        impacts={[
          `受影响智能体：${affected.length} 个${affected.length ? `（${affected.map((agent) => agent.name).join('、')}）` : ''}`,
          `当前可用工具：${mcp.tools.filter((tool) => tool.enabled).length} 个，停用后全部不可调用`,
          '已挂载 Agent 的配置引用保留并显示失效提示，恢复启用后可直接使用',
        ]}
      />
    </Drawer>
  )
}
