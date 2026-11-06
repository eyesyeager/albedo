import { useEffect, useState } from 'react'
import { CheckCircle2, Eye, EyeOff, Server, Zap } from 'lucide-react'
import type { ModelDraft, ModelType, SystemConfig } from '../../types/domain'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Modal, Select, Switch, Textarea, toast } from '../../components/ui'

function emptyForm(config: SystemConfig): ModelDraft {
  const defaults = config.defaults.model
  return {
    name: '',
    upstreamModelId: '',
    type: defaults.type,
    vendor: defaults.vendor,
    baseUrl: defaults.baseUrl,
    apiKey: '',
    headers: defaults.headers,
    timeoutSeconds: defaults.timeoutSeconds,
    note: '',
    enabled: defaults.enabled,
  }
}

export function CreateModelDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const config = useWorkspace((state) => state.systemConfig)
  const [form, setForm] = useState<ModelDraft>(() => emptyForm(config))
  const [keyVisible, setKeyVisible] = useState(false)
  const [tested, setTested] = useState(false)
  const [testResult, setTestResult] = useState('')
  const [testing, setTesting] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    setForm(emptyForm(config))
    setTested(false)
    setTestResult('')
    setError('')
    setKeyVisible(false)
  }, [open, config])

  const update = (patch: Partial<ModelDraft>) => {
    setForm((current) => ({ ...current, ...patch }))
    setTested(false)
    setTestResult('')
    setError('')
  }

  const test = () => {
    if (testing) return
    setTesting(true)
    api.models
      .test(form)
      .then((result) => {
        setTested(true)
        setTestResult(`连接成功，上游响应延迟 ${result.latencyMs} ms`)
        toast.success('连接测试成功')
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 模型连接测试失败：', message)
        setError(message)
        toast.error(message)
      })
      .finally(() => setTesting(false))
  }

  const submit = () => {
    if (saving) return
    setSaving(true)
    api.models
      .create(form)
      .then((model) => {
        toast.success(`「${model.name}」已接入，服务端已验证上游连通性`)
        onClose()
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 接入模型失败：', message)
        setError(message)
      })
      .finally(() => setSaving(false))
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="接入模型"
      description="录入任意兼容 OpenAI API 规范的上游模型服务"
      width="lg"
      dirty={Boolean(form.name || form.upstreamModelId || form.apiKey || form.headers || form.note || form.baseUrl !== config.defaults.model.baseUrl)}
      footer={
        <>
          <Button variant="outline" icon={<Zap className="h-3.5 w-3.5" />} loading={testing} onClick={test}>测试连接</Button>
          <Button loading={saving} onClick={submit}>保存模型</Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="模型展示名称" htmlFor="model-name" required>
            <Input id="model-name" value={form.name} placeholder="例如：自定义对话模型" onChange={(event) => update({ name: event.target.value })} />
          </Field>
          <Field label="模型标识 Model ID" htmlFor="model-id" required hint="调用上游时实际传入的 model 参数">
            <Input id="model-id" className="font-mono text-xs" value={form.upstreamModelId} placeholder="model-id" onChange={(event) => update({ upstreamModelId: event.target.value })} />
          </Field>
          <Field label="模型类型" htmlFor="model-type" required>
            <Select
              id="model-type"
              value={form.type}
              onChange={(event) => update({ type: event.target.value as ModelType })}
              options={config.options.modelTypes.map((item) => ({ value: item.value, label: item.label }))}
            />
          </Field>
          <Field label="所属渠道 / 厂商" htmlFor="model-vendor" hint="便于分类管理">
            <Input id="model-vendor" value={form.vendor} placeholder="厂商名称" onChange={(event) => update({ vendor: event.target.value })} />
          </Field>
        </div>

        <Field label="Base URL" htmlFor="model-base" required hint="OpenAI 兼容服务地址，需包含 /v1 等完整前缀">
          <Input id="model-base" className="font-mono text-xs" value={form.baseUrl} placeholder="https://…/v1" onChange={(event) => update({ baseUrl: event.target.value })} />
        </Field>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="API Key" htmlFor="model-key" required hint="加密存储，保存后仅保留脱敏后缀">
            <Input
              id="model-key"
              type={keyVisible ? 'text' : 'password'}
              value={form.apiKey}
              placeholder="sk-…"
              trailing={
                <button type="button" aria-label={keyVisible ? '隐藏 API Key' : '显示 API Key'} onClick={() => setKeyVisible((visible) => !visible)} className="rounded p-1.5 text-ink-faint hover:text-ink">
                  {keyVisible ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                </button>
              }
              onChange={(event) => update({ apiKey: event.target.value })}
            />
          </Field>
          <Field label="超时时间（秒）" htmlFor="model-timeout" hint={`默认 ${config.defaults.model.timeoutSeconds} 秒`}>
            <Input id="model-timeout" type="number" min={3} value={form.timeoutSeconds} onChange={(event) => update({ timeoutSeconds: Number(event.target.value) })} />
          </Field>
        </div>

        <Field label="自定义请求头" htmlFor="model-headers" hint="JSON 对象，适配需要额外 Header 的上游或代理场景">
          <Textarea id="model-headers" rows={2} className="font-mono text-xs" value={form.headers} placeholder='{"X-Env":"production"}' onChange={(event) => update({ headers: event.target.value })} />
        </Field>
        <Field label="备注" htmlFor="model-note" hint="记录用途、适用场景、成本或维护信息，可在模型详情中随时修改">
          <Textarea id="model-note" rows={2} value={form.note} onChange={(event) => update({ note: event.target.value })} />
        </Field>

        <div className="flex items-center justify-between rounded-field border border-line px-3 py-2.5">
          <div>
            <p className="text-xs font-medium text-ink">启用状态</p>
            <p className="mt-0.5 text-2xs text-ink-faint">停用后不可被新建配置选用</p>
          </div>
          <Switch label="启用模型" checked={form.enabled} onChange={(checked) => update({ enabled: checked })} />
        </div>

        {testResult && <p className="flex items-center gap-2 rounded-field bg-ok/10 px-3 py-2 text-xs text-ok"><CheckCircle2 className="h-3.5 w-3.5" />{testResult}</p>}
        {error && <p className="field-error" role="alert">{error}</p>}
        <div className="flex items-start gap-2 rounded-field bg-canvas px-3 py-2.5 text-2xs leading-relaxed text-ink-muted">
          <Server className="mt-0.5 h-3.5 w-3.5 shrink-0 text-brand" />
          <span>仅 {config.options.publishableModelTypes.join(' / ')} 类型可创建对外发布版本；保存时服务端会再次真实探测上游，模型资源层不设置默认温度。</span>
        </div>
      </div>
    </Modal>
  )
}
