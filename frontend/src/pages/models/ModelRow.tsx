import { Cpu, Trash2 } from 'lucide-react'
import type { ModelResource, WorkspaceData } from '../../types/domain'
import { cn } from '../../lib/cn'
import { activePublicationCount, publicationsOf } from '../../store/selectors'
import { Badge, Button, Switch } from '../../components/ui'
import { ConnectionBadge } from '../../components/common/StatusBadge'

export const MODEL_TYPE_LABEL: Record<ModelResource['type'], string> = {
  chat: 'Chat',
  embedding: 'Embedding',
  rerank: 'Rerank',
  multimodal: '多模态',
}

export function ModelRow({
  model, data, onConfig, onPublish, onToggle, onTest, onDelete, testing,
}: {
  model: ModelResource
  data: WorkspaceData
  onConfig: () => void
  onPublish: () => void
  onToggle: (enabled: boolean) => void
  onTest: () => void
  onDelete: () => void
  testing: boolean
}) {
  const versions = publicationsOf(data, model.id)
  const active = activePublicationCount(data, model.id)
  const canPublish = model.enabled && model.connection === 'normal' && (model.type === 'chat' || model.type === 'embedding')

  return (
    <tr className={cn('transition-colors hover:bg-brand-faint/50', !model.enabled && 'bg-canvas/40')}>
      <td className="px-4 py-3">
        <div className="flex items-center gap-3">
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-[10px] bg-brand-soft text-brand"><Cpu className="h-4 w-4" aria-hidden /></span>
          <div className="min-w-0">
            <p className="truncate text-xs font-semibold text-ink">{model.name}</p>
            <code className="font-mono text-2xs text-ink-faint">{model.upstreamModelId}</code>
            <p className="mt-0.5 max-w-[220px] truncate text-2xs text-ink-muted" title={model.note}>{model.note}</p>
          </div>
        </div>
      </td>
      <td className="px-4 py-3">
        <p className="text-xs text-ink">{MODEL_TYPE_LABEL[model.type]}</p>
        <p className="mt-0.5 text-2xs text-ink-faint">{model.vendor}</p>
      </td>
      <td className="px-4 py-3"><ConnectionBadge connection={model.connection} /></td>
      <td className="px-4 py-3">
        {versions.length === 0 ? <span className="text-2xs text-ink-faint">未发布</span> : (
          <>
            <Badge tone="brand">{active} 个生效</Badge>
            <p className="mt-1 text-2xs text-ink-faint">共 {versions.length} 个版本</p>
          </>
        )}
      </td>
      <td className="px-4 py-3 text-xs tabular-nums text-ink-muted">{model.latencyMs ? `${model.latencyMs} ms` : '—'}</td>
      <td className="px-4 py-3"><Switch label={`${model.name} 启用开关`} checked={model.enabled} onChange={onToggle} /></td>
      <td className="px-4 py-3">
        <div className="flex items-center gap-1.5">
          <Button variant="outline" size="sm" loading={testing} title="使用已保存的配置测试上游连接" onClick={onTest}>测试</Button>
          <Button variant="outline" size="sm" onClick={onConfig}>配置</Button>
          <Button variant="outline" size="sm" disabled={!canPublish} title={canPublish ? undefined : '需启用并通过连接测试'} onClick={onPublish}>
            {versions.length === 0 ? '发布' : '发布新版本'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            aria-label={`删除模型 ${model.name}`}
            title="删除模型"
            className="px-2 text-ink-faint hover:bg-danger/10 hover:text-danger"
            onClick={onDelete}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </td>
    </tr>
  )
}
