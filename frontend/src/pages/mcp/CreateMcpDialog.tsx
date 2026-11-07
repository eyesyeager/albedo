import { useEffect, useState } from 'react'
import { CheckCircle2, Plug, Zap } from 'lucide-react'
import type { McpDraft, McpTransport, SystemConfig } from '../../types/domain'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Modal, Select, Textarea, toast } from '../../components/ui'

function emptyForm(config: SystemConfig): McpDraft {
  const defaults = config.defaults.mcp
  return {
    name: '',
    description: '',
    transport: defaults.transport,
    endpoint: defaults.endpoint,
    headers: defaults.headers,
    env: defaults.env ?? '',
    timeoutSeconds: defaults.timeoutSeconds,
    tested: false,
  }
}

export function CreateMcpDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const config = useWorkspace((state) => state.systemConfig)
  const [form, setForm] = useState<McpDraft>(() => emptyForm(config))
  const [testing, setTesting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [testResult, setTestResult] = useState('')
  const [testTools, setTestTools] = useState<string[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    setForm(emptyForm(config))
    setTestResult('')
    setTestTools([])
    setError('')
  }, [open, config])

  const update = (patch: Partial<McpDraft>) => {
    setForm((current) => ({ ...current, ...patch, tested: false }))
    setTestResult('')
    setTestTools([])
    setError('')
  }

  const test = () => {
    if (testing) return
    setTesting(true)
    setError('')
    api.mcp
      .test(form)
      .then((result) => {
        setTestResult(`连接成功，发现 ${result.toolCount} 个可用工具 · 延迟 ${result.latencyMs} ms`)
        setTestTools(result.tools.map((tool) => tool.name))
        setForm((current) => ({ ...current, tested: true }))
        toast.success('连接测试成功')
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] MCP 连接测试失败：', message)
        setError(message)
        toast.error(message)
      })
      .finally(() => setTesting(false))
  }

  const submit = () => {
    if (saving) return
    setSaving(true)
    api.mcp
      .create(form)
      .then(() => {
        toast.success(`「${form.name.trim()}」已接入资源池`)
        onClose()
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 创建 MCP 失败：', message)
        setError(message)
      })
      .finally(() => setSaving(false))
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="添加 MCP 服务器"
      description="接入后作为资源池条目，可被任意智能体挂载复用"
      width="lg"
      dirty={Boolean(form.name || form.endpoint || form.description || form.headers)}
      footer={
        <>
          <Button variant="outline" icon={<Zap className="h-3.5 w-3.5" />} loading={testing} onClick={test}>测试连接</Button>
          <Button loading={saving} disabled={!form.name.trim() || !form.endpoint.trim()} onClick={submit}>保存并接入</Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="名称" htmlFor="mcp-name" required>
            <Input id="mcp-name" value={form.name} placeholder="例如：自定义搜索服务" onChange={(event) => update({ name: event.target.value })} />
          </Field>
          <Field label="连接类型" htmlFor="mcp-transport" required>
            <Select
              id="mcp-transport"
              value={form.transport}
              onChange={(event) => update({ transport: event.target.value as McpTransport })}
              options={config.options.mcpTransports.map((item) => ({ value: item.value, label: item.label }))}
            />
          </Field>
        </div>
        <Field label={form.transport === 'stdio' ? '启动命令' : '服务地址'} htmlFor="mcp-endpoint" required hint={form.transport === 'stdio' ? '填写可执行的启动命令' : '远程服务需为合法 HTTP(S) URL'}>
          <Input id="mcp-endpoint" value={form.endpoint} className="font-mono text-xs" placeholder={form.transport === 'stdio' ? 'npx -y …' : 'https://…'} onChange={(event) => update({ endpoint: event.target.value })} />
        </Field>
        <Field label="描述" htmlFor="mcp-desc" hint={`留空时使用「${config.defaults.mcp.description}」`}>
          <Textarea id="mcp-desc" rows={2} value={form.description} placeholder="描述这个服务提供的能力" onChange={(event) => update({ description: event.target.value })} />
        </Field>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label={form.transport === 'stdio' ? '环境变量（可选）' : '自定义请求头'} htmlFor="mcp-headers" hint={form.transport === 'stdio' ? 'stdio 子进程环境变量，JSON 对象，加密存储' : 'JSON 对象，敏感值不会出现在明文日志中'}>
            {form.transport === 'stdio' ? (
              <Textarea id="mcp-headers" rows={3} value={form.env} className="font-mono text-xs" placeholder='{"GITHUB_TOKEN":"…"}' onChange={(event) => update({ env: event.target.value })} />
            ) : (
              <Textarea id="mcp-headers" rows={3} value={form.headers} className="font-mono text-xs" placeholder='{"Authorization":"Bearer …"}' onChange={(event) => update({ headers: event.target.value })} />
            )}
          </Field>
          <Field label="超时时间（秒）" htmlFor="mcp-timeout" hint={`范围 ${config.limits.mcpTimeoutMin || 3} ~ ${config.limits.mcpTimeoutMax || 600} 秒`}>
            <Input
              id="mcp-timeout"
              type="number"
              min={config.limits.mcpTimeoutMin || 3}
              max={config.limits.mcpTimeoutMax || 600}
              value={form.timeoutSeconds}
              onChange={(event) => update({ timeoutSeconds: Number(event.target.value) })}
            />
          </Field>
        </div>

        {testResult && (
          <div className="rounded-field bg-ok/10 px-3 py-2">
            <p className="flex items-center gap-2 text-xs text-ok">
              <CheckCircle2 className="h-3.5 w-3.5" />{testResult}
            </p>
            {testTools.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1">
                {testTools.slice(0, 8).map((name) => (
                  <code key={name} className="rounded bg-white/70 px-1.5 py-0.5 font-mono text-2xs text-ok">{name}</code>
                ))}
                {testTools.length > 8 && <span className="text-2xs text-ok">等 {testTools.length} 个工具</span>}
              </div>
            )}
          </div>
        )}
        {error && <p className="field-error" role="alert">{error}</p>}
        <div className="flex items-start gap-2 rounded-field bg-canvas px-3 py-2.5 text-2xs leading-relaxed text-ink-muted">
          <Plug className="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand" />
          <span>连接测试使用当前未保存的表单值，不会自动保存；成功后工具清单将在保存时写入资源池，并默认全部启用。</span>
        </div>
      </div>
    </Modal>
  )
}
