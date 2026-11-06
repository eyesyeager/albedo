import { useEffect, useState } from 'react'
import { CheckCircle2, Eye, EyeOff, KeyRound, ShieldCheck, Wand2 } from 'lucide-react'
import type { ModelPublication, ModelResource, PublicationDraft } from '../../types/domain'
import { maskedUpstreamKey, newSecretKey } from '../../lib/mask'
import { firstIssue, validatePublication } from '../../lib/validate'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { Button, CodeBlock, CopyButton, Field, Input, Modal, Textarea, toast } from '../../components/ui'

function draftFor(model: ModelResource, versionCount: number, defaults: { temperature: number; maxOutputTokens: number; qpm: number }): PublicationDraft {
  const version = `v${versionCount + 1}.0.0`
  return {
    version,
    callIdentifier: versionCount === 0 ? model.upstreamModelId : `${model.upstreamModelId}-${version}`,
    apiKey: '',
    headers: '',
    temperature: defaults.temperature,
    maxOutputTokens: defaults.maxOutputTokens,
    qpm: defaults.qpm,
  }
}

export function PublishVersionDialog({ model, open, onClose }: { model: ModelResource | null; open: boolean; onClose: () => void }) {
  const publications = useWorkspace((state) => state.publications)
  const domain = useWorkspace((state) => state.settings.domain)
  const config = useWorkspace((state) => state.systemConfig)
  const [draft, setDraft] = useState<PublicationDraft | null>(null)
  const [keyVisible, setKeyVisible] = useState(false)
  const [busy, setBusy] = useState(false)
  const [published, setPublished] = useState<{ publication: ModelPublication; fullKey: string } | null>(null)
  const [error, setError] = useState('')

  const versionCount = model ? publications.filter((item) => item.modelId === model.id).length : 0

  useEffect(() => {
    if (!open || !model) return
    const count = useWorkspace.getState().publications.filter((item) => item.modelId === model.id).length
    setDraft(draftFor(model, count, config.defaults.publication))
    setPublished(null)
    setError('')
    setKeyVisible(false)
  }, [open, model?.id])

  if (!model || !draft) return null

  const issues = validatePublication(draft, model.id, publications, config.limits)
  const update = (patch: Partial<PublicationDraft>) => {
    setDraft((current) => (current ? { ...current, ...patch } : current))
    setError('')
  }
  const fieldError = (field: string, skipEmpty = false): string | undefined => {
    if (skipEmpty && !String(draft[field as keyof PublicationDraft] ?? '').trim()) return undefined
    return firstIssue(issues, field)
  }

  const generateKey = () => {
    update({ apiKey: newSecretKey() })
    setKeyVisible(true)
  }

  const submit = () => {
    if (busy || issues.length > 0) return
    setBusy(true)
    api.models
      .publish(model.id, draft)
      .then(({ publication, fullKey: key }) => {
        setPublished({ publication, fullKey: key })
        toast.success(`${model.name} ${publication.version} 发布成功`)
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 发布模型版本失败：', message)
        setError(message)
      })
      .finally(() => setBusy(false))
  }

  const baseUrl = `${domain.replace(/\/$/, '')}/v1`

  const curlSnippet = (callIdentifier: string, key: string): string => {
    const endpoint = model.type === 'embedding' ? 'embeddings' : 'chat/completions'
    const body = model.type === 'embedding'
      ? `{
    "model": "${callIdentifier}",
    "input": "你好"
  }`
      : `{
    "model": "${callIdentifier}",
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "你好"}
    ],
    "stream": false
  }`
    return `curl -X POST '${baseUrl}/${endpoint}' \\\n  -H 'Authorization: Bearer ${key}' \\\n  -H 'Content-Type: application/json' \\\n  -d '${body}'`
  }

  if (published) {
    return (
      <Modal
        open={open}
        onClose={onClose}
        title="发布成功 · 请保存密钥"
        description="该版本的自定义 API Key 仅展示这一次，关闭后无法再次查看"
        width="lg"
        footer={<Button onClick={onClose} icon={<CheckCircle2 className="h-3.5 w-3.5" />}>我已保存</Button>}
      >
        <div className="space-y-3">
          <div className="rounded-field bg-ok/10 px-3 py-2.5 text-xs text-ok">版本 {published.publication.version} 已发布，调用标识 {published.publication.callIdentifier}</div>
          <code className="block break-all rounded-field bg-canvas px-3 py-3 font-mono text-xs text-ink">{published.fullKey}</code>
          <CopyButton value={published.fullKey} label="复制完整密钥" message="完整密钥已复制，请立即保存" />
          <div className="space-y-2 rounded-field border border-line p-3">
            <p className="text-2xs text-ink-faint">通过 curl 调用该版本（示例已填入本次密钥，删除该版本或轮换密钥后示例失效）</p>
            <CodeBlock language="bash" maxHeight="260px" code={curlSnippet(published.publication.callIdentifier, published.fullKey)} />
          </div>
        </div>
      </Modal>
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="发布新版本"
      description={`为「${model.name}」创建独立的对外调用配置`}
      width="lg"
      dirty={Boolean(draft.apiKey || draft.headers)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button loading={busy} disabled={issues.length > 0} onClick={submit}>确认发布</Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="rounded-field border border-line bg-canvas/60 p-3">
          <p className="text-2xs text-ink-faint">平台统一调用地址（所有模型与版本共用）</p>
          <div className="mt-1 flex items-center gap-2">
            <code className="min-w-0 flex-1 break-all font-mono text-xs text-ink">{baseUrl}</code>
            <CopyButton value={baseUrl} label="复制" message="统一调用地址已复制" />
          </div>
          <p className="mt-1.5 text-2xs text-ink-faint">该模型已有 {versionCount} 个发布版本</p>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="版本号" htmlFor="pub-version" required error={fieldError('version')} hint="同一模型内唯一，建议 SemVer 格式">
            <Input id="pub-version" value={draft.version} invalid={Boolean(fieldError('version'))} onChange={(event) => update({ version: event.target.value })} />
          </Field>
          <Field label="模型调用标识" htmlFor="pub-identifier" required error={fieldError('callIdentifier')} hint="全平台唯一，下游请求中的 model 值">
            <Input id="pub-identifier" className="font-mono text-xs" value={draft.callIdentifier} invalid={Boolean(fieldError('callIdentifier'))} onChange={(event) => update({ callIdentifier: event.target.value })} />
          </Field>
        </div>

        <Field label="自定义 API Key" htmlFor="pub-key" required error={fieldError('apiKey', true)} hint="至少 12 个字符，与上游 Key、管理访问 Key 相互隔离">
          <div className="flex items-center gap-2">
            <Input
              id="pub-key"
              type={keyVisible ? 'text' : 'password'}
              value={draft.apiKey}
              invalid={Boolean(fieldError('apiKey', true))}
              className="flex-1"
              leading={<KeyRound className="h-3.5 w-3.5" />}
              trailing={
                <button type="button" aria-label={keyVisible ? '隐藏 Key' : '显示 Key'} onClick={() => setKeyVisible((visible) => !visible)} className="rounded p-1.5 text-ink-faint hover:text-ink">
                  {keyVisible ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                </button>
              }
              onChange={(event) => update({ apiKey: event.target.value })}
            />
            <Button variant="outline" size="sm" icon={<Wand2 className="h-3.5 w-3.5" />} onClick={generateKey}>随机生成</Button>
          </div>
        </Field>

        <Field label="自定义请求头" htmlFor="pub-headers" error={fieldError('headers')} hint="可空；非空时必须是合法 JSON 对象，用于网关路由或额外鉴权">
          <Textarea id="pub-headers" rows={2} className="font-mono text-xs" value={draft.headers} invalid={Boolean(fieldError('headers'))} placeholder='{"X-Env":"production"}' onChange={(event) => update({ headers: event.target.value })} />
        </Field>

        <div className="grid grid-cols-3 gap-3">
          <Field label="温度" htmlFor="pub-temp" error={fieldError('temperature')} hint={`${config.limits.temperatureMin || 0} ~ ${config.limits.temperatureMax || 2}`}>
            <Input id="pub-temp" type="number" min={config.limits.temperatureMin || 0} max={config.limits.temperatureMax || 2} step={0.1} value={draft.temperature} onChange={(event) => update({ temperature: Number(event.target.value) })} />
          </Field>
          <Field label="最大输出 Tokens" htmlFor="pub-tokens" error={fieldError('maxOutputTokens')} hint="正整数">
            <Input id="pub-tokens" type="number" min={1} value={draft.maxOutputTokens} onChange={(event) => update({ maxOutputTokens: Number(event.target.value) })} />
          </Field>
          <Field label="QPM" htmlFor="pub-qpm" error={fieldError('qpm')} hint="每分钟请求数">
            <Input id="pub-qpm" type="number" min={1} value={draft.qpm} onChange={(event) => update({ qpm: Number(event.target.value) })} />
          </Field>
        </div>

        {error && <p className="field-error" role="alert">{error}</p>}
        <div className="flex items-start gap-2 rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>请妥善保存密钥：各版本的鉴权、生成参数与限流相互独立，日志不记录明文密钥。上游 Key 与发布 Key 隔离，示例：{maskedUpstreamKey(model.credentialSuffix)}。</span>
        </div>
      </div>
    </Modal>
  )
}
