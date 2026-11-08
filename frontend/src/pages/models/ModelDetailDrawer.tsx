import { useEffect, useState } from 'react'
import { Plus, Server, Trash2, Wand2 } from 'lucide-react'
import { formatDateTime } from '../../lib/format'
import { maskedUpstreamKey, newSecretKey } from '../../lib/mask'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { publicationsOf } from '../../store/selectors'
import { useWorkspace } from '../../store/workspace'
import { Badge, Button, ConfirmDialog, CopyButton, Drawer, Field, Input, Modal, Switch, Textarea, toast } from '../../components/ui'
import { ConnectionBadge } from '../../components/common/StatusBadge'
import { RevealCredentialButton } from '../../components/common/RevealCredentialButton'

export function ModelDetailDrawer({ modelId, onClose, onPublish }: { modelId: string | null; onClose: () => void; onPublish: () => void }) {
  const data = useWorkspace()
  const model = data.models.find((item) => item.id === modelId)
  const [baseUrl, setBaseUrl] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [rotatingId, setRotatingId] = useState<string | null>(null)
  const [rotateKey, setRotateKey] = useState('')
  const [rotatedKey, setRotatedKey] = useState<string | null>(null)
  const [rotateBusy, setRotateBusy] = useState(false)
  const [deleteVersionId, setDeleteVersionId] = useState<string | null>(null)
  const [deletingVersion, setDeletingVersion] = useState(false)

  useEffect(() => {
    const current = useWorkspace.getState().models.find((item) => item.id === modelId)
    if (!current) return
    setBaseUrl(current.baseUrl)
    setNote(current.note)
  }, [modelId])

  if (!model) return null
  const versions = publicationsOf(data, model.id)
  const canPublish = model.enabled && model.connection === 'normal' && (model.type === 'chat' || model.type === 'embedding')

  const openRotate = (id: string) => {
    setRotatingId(id)
    setRotateKey('')
    setRotatedKey(null)
  }

  const submitRotate = () => {
    if (!rotatingId || rotateKey.trim().length < 12 || rotateBusy) return
    setRotateBusy(true)
    api.models
      .rotatePublicationKey(rotatingId, rotateKey)
      .then((full) => {
        setRotatedKey(full)
        toast.success('发布版本密钥已轮换，旧 Key 立即失效')
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setRotateBusy(false))
  }

  const deleteVersion = deleteVersionId ? versions.find((item) => item.id === deleteVersionId) : undefined

  const confirmDeleteVersion = () => {
    if (!deleteVersionId || deletingVersion) return
    setDeletingVersion(true)
    api.models
      .removePublication(deleteVersionId)
      .then(() => {
        toast.success('发布版本已删除，该版本 Key 立即失效')
        setDeleteVersionId(null)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeletingVersion(false))
  }

  const save = () => {
    setSaving(true)
    api.models
      .update(model.id, { baseUrl, note })
      .then(() => toast.success('连接配置已保存，连接状态已置为待复测'))
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 保存模型配置失败：', message)
        toast.error(message)
      })
      .finally(() => setSaving(false))
  }

  return (
    <Drawer
      open
      onClose={onClose}
      title={model.name}
      subtitle={`${model.upstreamModelId} · ${model.vendor}`}
      width="lg"
      dirty={rotatingId === null && (baseUrl !== model.baseUrl || note !== model.note)}
    >
      <div className="space-y-5">
        <div className="flex items-center gap-3 rounded-field border border-line bg-canvas/50 p-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-[11px] bg-brand-soft text-brand"><Server className="h-4 w-4" /></span>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-ink">{model.name}</p>
            <code className="font-mono text-2xs text-ink-faint">{model.upstreamModelId}</code>
          </div>
          <ConnectionBadge connection={model.connection} />
        </div>

        <div className="space-y-4">
          <Field label="Base URL" htmlFor="model-detail-base" required>
            <Input id="model-detail-base" className="font-mono text-xs" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} />
          </Field>
          <Field label="上游 API Key" hint="默认脱敏展示，可在管理系统中查看完整值；不可在抽屉内编辑">
            <div className="flex flex-wrap items-center gap-2">
              <code className="min-w-0 flex-1 truncate rounded-field border border-line bg-canvas/50 px-3 py-2 font-mono text-xs text-ink-muted">{maskedUpstreamKey(model.credentialSuffix)}</code>
              <RevealCredentialButton
                reveal={() => api.models.revealCredential(model.id)}
                title="查看模型上游 API Key"
              />
            </div>
          </Field>
          <Field label="超时时间" hint="接入模型时设定的上游请求超时，可在接入表单中修改">
            <code className="block rounded-field border border-line bg-canvas/50 px-3 py-2 font-mono text-xs text-ink-muted">{model.timeoutSeconds} 秒</code>
          </Field>
          <Field label="备注" htmlFor="model-detail-note" hint="记录用途、适用场景、成本、配额或维护注意事项">
            <Textarea id="model-detail-note" rows={3} value={note} onChange={(event) => setNote(event.target.value)} />
          </Field>
          <Button block loading={saving} onClick={save}>保存更改</Button>
        </div>

        <div className="border-t border-line pt-5">
          <div className="mb-3 flex items-center justify-between gap-2">
            <div>
              <h3 className="section-title">发布版本</h3>
              <p className="mt-1 text-2xs text-ink-faint">各版本独立鉴权、参数、限流与启停，共用统一调用地址</p>
            </div>
            <Button variant="secondary" size="sm" icon={<Plus className="h-3 w-3" />} disabled={!canPublish} onClick={onPublish}>发布新版本</Button>
          </div>

          {versions.length === 0 ? (
            <div className="rounded-field border border-dashed border-line px-4 py-8 text-center">
              <p className="text-xs font-medium text-ink">尚未发布任何版本</p>
              <p className="mt-1 text-2xs text-ink-faint">发布后可通过 curl 使用统一地址调用该模型</p>
            </div>
          ) : (
            <div className="space-y-3">
              {versions.map((version) => (
                <div key={version.id} className="rounded-field border border-line p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <code className="font-mono text-xs font-semibold text-ink">{version.version}</code>
                    <Badge tone={version.enabled ? 'ok' : 'neutral'} dot>{version.enabled ? '已发布' : '已停用'}</Badge>
                    <div className="ml-auto"><Switch label={`${version.version} 启用开关`} checked={version.enabled} onChange={(checked) => {
                      api.models.togglePublication(version.id, checked).then(() => toast.success(`${version.version} 已${checked ? '启用' : '停用'}`))
                    }} /></div>
                  </div>
                  <div className="mt-2 flex items-center gap-2">
                    <code className="min-w-0 truncate font-mono text-2xs text-brand">{version.callIdentifier}</code>
                    <CopyButton value={version.callIdentifier} label="复制" message="调用标识已复制" />
                  </div>
                  <div className="mt-3 grid grid-cols-4 gap-2 rounded-field bg-canvas/70 p-2 text-center">
                    <div><p className="text-xs font-semibold text-ink">{version.temperature}</p><p className="text-[10px] text-ink-faint">温度</p></div>
                    <div><p className="text-xs font-semibold text-ink">{version.maxOutputTokens}</p><p className="text-[10px] text-ink-faint">输出 Tokens</p></div>
                    <div><p className="text-xs font-semibold text-ink">{version.qpm}</p><p className="text-[10px] text-ink-faint">QPM</p></div>
                    <div><p className="font-mono text-xs font-semibold text-ink">••{version.credentialSuffix}</p><p className="text-[10px] text-ink-faint">Key 后缀</p></div>
                  </div>
                  <p className="mt-2 text-2xs text-ink-faint">发布于 {formatDateTime(version.createdAt)} · 请求头 {version.headersConfigured ? '已配置' : '未配置'}</p>
                  <div className="mt-2 flex flex-wrap items-center gap-3">
                    <RevealCredentialButton
                      reveal={() => api.models.revealPublicationKey(version.id)}
                      title={`查看 ${version.version} 的 API Key`}
                      label="查看密钥"
                      link
                    />
                    <button type="button" onClick={() => openRotate(version.id)} className="text-2xs font-medium text-brand transition-colors hover:text-brand-hover">轮换密钥 →</button>
                    <button type="button" onClick={() => setDeleteVersionId(version.id)} className="inline-flex items-center gap-1 text-2xs font-medium text-ink-faint transition-colors hover:text-danger">
                      <Trash2 className="h-3 w-3" />删除版本
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <Modal
        open={rotatingId !== null}
        onClose={() => setRotatingId(null)}
        title={rotatedKey ? '新密钥已生效' : '轮换发布版本密钥'}
        description={rotatedKey ? '新密钥已生效，也可在版本卡片中随时再次查看' : '保存后该版本旧 Key 立即失效，其他发布版本不受影响'}
        width="md"
        footer={rotatedKey
          ? <Button onClick={() => setRotatingId(null)}>我已保存</Button>
          : <><Button variant="outline" onClick={() => setRotatingId(null)}>取消</Button><Button loading={rotateBusy} disabled={rotateKey.trim().length < 12} onClick={submitRotate}>确认轮换</Button></>}
      >
        {rotatedKey ? (
          <div className="space-y-3">
            <code className="block break-all rounded-field bg-canvas px-3 py-3 font-mono text-xs text-ink">{rotatedKey}</code>
            <CopyButton value={rotatedKey} label="复制完整密钥" message="完整密钥已复制，请立即保存" />
          </div>
        ) : (
          <div className="space-y-2">
            <label htmlFor="rotate-key" className="field-label">新自定义 API Key</label>
            <div className="flex items-center gap-2">
              <Input id="rotate-key" type="password" className="flex-1" value={rotateKey} placeholder="至少 12 个字符" onChange={(event) => setRotateKey(event.target.value)} />
              <Button variant="outline" size="sm" icon={<Wand2 className="h-3.5 w-3.5" />} onClick={() => setRotateKey(newSecretKey())}>随机生成</Button>
            </div>
            <p className="text-2xs leading-relaxed text-ink-faint">与上游 API Key、管理访问 Key 相互隔离；系统日志仅记录轮换时间与 Key 后缀，不记录明文。</p>
          </div>
        )}
      </Modal>

      <ConfirmDialog
        open={deleteVersionId !== null}
        onClose={() => setDeleteVersionId(null)}
        onConfirm={confirmDeleteVersion}
        title={`删除发布版本 ${deleteVersion?.version ?? ''}？`}
        description="删除后该版本的调用标识与自定义 Key 立即失效，下游请求将返回鉴权失败，且无法恢复。"
        confirmText="确认删除"
        danger
        loading={deletingVersion}
        impacts={[
          `调用标识：${deleteVersion?.callIdentifier ?? ''} 将不可再使用`,
          `该版本 Key（••${deleteVersion?.credentialSuffix ?? ''}）同步销毁`,
          '同一模型的其他发布版本不受影响，模型资源本身保留',
        ]}
      />
    </Drawer>
  )
}
