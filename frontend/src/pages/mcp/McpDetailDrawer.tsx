import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, Clock3, Play, Trash2, Zap } from 'lucide-react'
import { formatDateTime, formatDuration } from '../../lib/format'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { affectedAgents } from '../../store/selectors'
import { Button, ConfirmDialog, Drawer, EmptyState, Field, Input, Select, Switch, Tabs, Textarea, toast } from '../../components/ui'
import { RunStatusBadge } from '../../components/common/StatusBadge'
import { TRANSPORT_LABEL } from './McpCard'
import { McpToolTestDialog } from './McpToolTestDialog'

export type McpTab = 'general' | 'tools' | 'usage' | 'calls'

export function McpDetailDrawer({ mcpId, initialTab, onClose, onDelete }: { mcpId: string | null; initialTab: McpTab; onClose: () => void; onDelete: (id: string) => void }) {
  const navigate = useNavigate()
  const data = useWorkspace()
  const defaults = useWorkspace((state) => state.systemConfig.defaults.mcp)
  const mcp = data.mcps.find((item) => item.id === mcpId)
  const [tab, setTab] = useState<McpTab>(initialTab)
  const [form, setForm] = useState({ name: '', description: '', endpoint: '', env: '', enabled: true })
  const [qpm, setQpm] = useState(defaults.qpm)
  const [concurrency, setConcurrency] = useState(defaults.maxConcurrency)
  // 通用保存、用量保存、连接测试各自独立 pending，避免任一请求结束提前解锁其它按钮。
  const [pending, setPending] = useState<Record<string, boolean>>({})
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [testingConnection, setTestingConnection] = useState(false)
  const [connectionResult, setConnectionResult] = useState<{ toolCount: number; latencyMs: number; at: string } | null>(null)
  const [connectionError, setConnectionError] = useState('')
  const [toolTestId, setToolTestId] = useState<string | null>(null)

  const isBusy = (key: string): boolean => Boolean(pending[key])
  const runAction = (key: string, action: () => Promise<unknown>): void => {
    if (pending[key]) return
    setPending((current) => ({ ...current, [key]: true }))
    action()
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setPending((current) => ({ ...current, [key]: false })))
  }

  useEffect(() => setTab(initialTab), [mcpId, initialTab])
  useEffect(() => {
    const current = useWorkspace.getState().mcps.find((item) => item.id === mcpId)
    if (!current) return
    setForm({ name: current.name, description: current.description, endpoint: current.endpoint, env: current.env ?? '', enabled: current.enabled })
    setQpm(current.qpm)
    setConcurrency(current.maxConcurrency)
  }, [mcpId])

  useEffect(() => {
    setConnectionResult(null)
    setConnectionError('')
    setToolTestId(null)
  }, [mcpId])

  if (!mcp) return null

  const affected = affectedAgents(data, 'mcp', mcp.id)

  const performSave = () => runAction('save', () =>
    api.mcp
      .update(mcp.id, { name: form.name, description: form.description, endpoint: form.endpoint, env: form.env })
      .then(() => (form.enabled !== mcp.enabled ? api.mcp.toggle(mcp.id, form.enabled) : undefined))
      .then(() => {
        toast.success(form.enabled !== mcp.enabled ? `MCP 已${form.enabled ? '启用' : '停用'}，配置已保存` : 'MCP 配置已保存')
      }),
  )

  const saveGeneral = () => {
    if (mcp.enabled && !form.enabled) {
      setConfirmDisable(true)
      return
    }
    performSave()
  }

  const saveUsage = () => runAction('usage', () =>
    api.mcp
      .updateUsage(mcp.id, qpm, concurrency)
      .then(() => toast.success('使用配置已保存')),
  )

  const testConnection = () => {
    if (testingConnection) return
    setTestingConnection(true)
    setConnectionError('')
    api.mcp
      .testExisting(mcp.id)
      .then((result) => {
        setConnectionResult({ ...result, at: new Date().toISOString() })
        toast.success(`连接正常，发现 ${result.toolCount} 个工具`)
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] MCP 连接测试失败：', message)
        setConnectionError(message)
        toast.error(message)
      })
      .finally(() => setTestingConnection(false))
  }

  const calls = data.logs.filter((log) => log.resourceId === mcp.id && log.type === 'mcp').slice(0, 10)

  return (
    <Drawer
      open
      onClose={onClose}
      title={mcp.name}
      subtitle={`${TRANSPORT_LABEL[mcp.transport]} · ${mcp.endpoint}`}
      width="lg"
      dirty={!confirmDisable && (form.name !== mcp.name || form.description !== mcp.description || form.endpoint !== mcp.endpoint || form.env !== (mcp.env ?? '') || form.enabled !== mcp.enabled || qpm !== mcp.qpm || concurrency !== mcp.maxConcurrency)}
      footer={
        <Button
          variant="ghost"
          className="mr-auto text-danger hover:bg-danger/10 hover:text-danger"
          icon={<Trash2 className="h-3.5 w-3.5" />}
          onClick={() => onDelete(mcp.id)}
        >
          删除 MCP
        </Button>
      }
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
          {mcp.transport === 'stdio' && (
            <Field label="环境变量" htmlFor="mcp-detail-env" hint="stdio 子进程环境变量，JSON 对象，加密存储">
              <Textarea id="mcp-detail-env" rows={3} value={form.env} className="font-mono text-xs" placeholder='{"GITHUB_TOKEN":"…"}' onChange={(event) => setForm({ ...form, env: event.target.value })} />
            </Field>
          )}
          <div className="rounded-field border border-line px-3 py-3">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-xs font-medium text-ink">连接测试</p>
                <p className="mt-0.5 text-2xs leading-relaxed text-ink-faint">
                  使用已保存的地址与请求头发起真实工具发现，成功后同步工具清单并刷新连接状态
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                icon={<Zap className="h-3.5 w-3.5" />}
                loading={testingConnection}
                onClick={testConnection}
              >
                测试连接
              </Button>
            </div>
            {connectionResult && (
              <p className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-field bg-ok/10 px-2.5 py-1.5 text-2xs text-ok">
                <CheckCircle2 className="h-3.5 w-3.5" />
                <span>连接正常</span>
                <span className="tabular-nums">工具 {connectionResult.toolCount} 个</span>
                <span className="tabular-nums">延迟 {connectionResult.latencyMs} ms</span>
                <span className="tabular-nums text-ink-faint">{formatDateTime(connectionResult.at)}</span>
              </p>
            )}
            {connectionError && <p className="mt-2 text-2xs leading-relaxed text-danger" role="alert">{connectionError}</p>}
            {!connectionResult && !connectionError && mcp.lastTestedAt && (
              <p className="mt-2 text-2xs text-ink-faint">最近测试：{formatDateTime(mcp.lastTestedAt)}</p>
            )}
          </div>
          <div className="flex items-center justify-between rounded-field border border-line px-3 py-2.5">
            <div>
              <p className="text-xs font-medium text-ink">启用状态</p>
              <p className="mt-0.5 text-2xs text-ink-faint">停用后所有已挂载 Agent 无法调用该 MCP，配置引用仍保留</p>
            </div>
            <Switch label="启用 MCP" checked={form.enabled} onChange={(checked) => setForm({ ...form, enabled: checked })} />
          </div>
          <Button block loading={isBusy('save')} onClick={saveGeneral}>保存更改</Button>
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
              <div className="flex shrink-0 items-center gap-1.5">
                <Button
                  variant="ghost"
                  size="sm"
                  icon={<Play className="h-3.5 w-3.5" />}
                  onClick={() => setToolTestId(tool.id)}
                >
                  调用测试
                </Button>
                <Switch
                  label={`${tool.name} 启用开关`}
                  checked={tool.enabled}
                  onChange={(checked) => {
                    api.mcp.toggleTool(mcp.id, tool.id, checked)
                      .then(() => toast.success(`工具「${tool.name}」已${checked ? '启用' : '停用'}`))
                      .catch((reason: unknown) => toast.error(asError(reason).message))
                  }}
                />
              </div>
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
          <Button block loading={isBusy('usage')} onClick={saveUsage}>保存使用配置</Button>
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

      <McpToolTestDialog
        mcpId={mcp.id}
        mcpName={mcp.name}
        tool={mcp.tools.find((item) => item.id === toolTestId) ?? null}
        timeoutSeconds={mcp.timeoutSeconds}
        onClose={() => setToolTestId(null)}
      />

      <ConfirmDialog
        open={confirmDisable}
        onClose={() => setConfirmDisable(false)}
        onConfirm={() => { setConfirmDisable(false); performSave() }}
        title={`停用「${mcp.name}」？`}
        description="停用后所有已挂载该 MCP 的智能体无法调用其工具，运行时会返回明确的资源不可用错误。"
        confirmText="确认停用"
        danger
        loading={isBusy('save')}
        impacts={[
          `受影响智能体：${affected.length} 个${affected.length ? `（${affected.map((agent) => agent.name).join('、')}）` : ''}`,
          `当前可用工具：${mcp.tools.filter((tool) => tool.enabled).length} 个，停用后全部不可调用`,
          '已挂载 Agent 的配置引用保留并显示失效提示，恢复启用后可直接使用',
        ]}
      />
    </Drawer>
  )
}
