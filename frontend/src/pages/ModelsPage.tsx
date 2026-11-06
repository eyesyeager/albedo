import { useMemo, useState } from 'react'
import { Cpu, Plus, Search } from 'lucide-react'
import type { ModelResource } from '../types/domain'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { activePublicationCount, affectedAgents, publicationsOf } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import { Button, ConfirmDialog, CopyButton, EmptyState, Input, Select, toast } from '../components/ui'
import { PageHeader } from '../components/common/PageHeader'
import { CreateModelDialog } from './models/CreateModelDialog'
import { ModelDetailDrawer } from './models/ModelDetailDrawer'
import { ModelRow, MODEL_TYPE_LABEL } from './models/ModelRow'
import { PublishVersionDialog } from './models/PublishVersionDialog'

export function ModelsPage() {
  const data = useWorkspace()
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [type, setType] = useState<'all' | ModelResource['type']>('all')
  const [vendor, setVendor] = useState('all')
  const [createOpen, setCreateOpen] = useState(false)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [publishId, setPublishId] = useState<string | null>(null)
  const [pending, setPending] = useState<{ id: string; enabled: boolean } | null>(null)
  const [busy, setBusy] = useState(false)
  const [testingId, setTestingId] = useState<string | null>(null)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  const vendors = Array.from(new Set(data.models.map((model) => model.vendor)))
  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return data.models.filter((model) => {
      if (type !== 'all' && model.type !== type) return false
      if (vendor !== 'all' && model.vendor !== vendor) return false
      if (!query) return true
      return `${model.name} ${model.upstreamModelId} ${model.note}`.toLowerCase().includes(query)
    })
  }, [data.models, debounced, type, vendor])

  const pendingModel = pending ? data.models.find((model) => model.id === pending.id) : undefined
  const affected = pending ? affectedAgents(data, 'model', pending.id) : []
  const activeVersions = pending ? activePublicationCount(data, pending.id) : 0
  const publishModel = data.models.find((model) => model.id === publishId) ?? null
  const detailModel = data.models.find((model) => model.id === detailId) ?? null
  const deleteModel = deleteId ? data.models.find((model) => model.id === deleteId) : undefined
  const deleteAffected = deleteModel ? affectedAgents(data, 'model', deleteModel.id) : []
  const deleteVersions = deleteModel ? publicationsOf(data, deleteModel.id) : []
  const baseUrl = `${data.settings.domain.replace(/\/$/, '')}/v1`

  const applyToggle = (id: string, enabled: boolean) => {
    setBusy(true)
    api.models
      .toggle(id, enabled)
      .then(() => toast.success(`模型已${enabled ? '启用' : '停用'}`))
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => { setBusy(false); setPending(null) })
  }

  const requestToggle = (model: ModelResource, enabled: boolean) => {
    if (!enabled) {
      setPending({ id: model.id, enabled })
      return
    }
    applyToggle(model.id, enabled)
  }

  const runTest = (model: ModelResource) => {
    if (testingId) return
    setTestingId(model.id)
    api.models.testExisting(model.id)
      .then(({ latencyMs }) => toast.success(`「${model.name}」连接正常，上游延迟 ${latencyMs} ms`))
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setTestingId(null))
  }

  const confirmDelete = () => {
    if (!deleteId || deleting) return
    setDeleting(true)
    api.models.remove(deleteId)
      .then(() => {
        toast.success('模型已删除，关联发布版本同步下线')
        setDeleteId(null)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeleting(false))
  }

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="模型管理"
        description={`统一接入、测试并发布模型资源，共 ${data.models.length} 个模型`}
        actions={<Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => setCreateOpen(true)}>接入模型</Button>}
      />

      <div className="card flex flex-wrap items-center gap-2.5 p-3">
        <Input
          aria-label="搜索模型"
          placeholder="搜索模型名称、Model ID 或备注…"
          leading={<Search className="h-3.5 w-3.5" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          className="min-w-[240px] flex-1"
        />
        <Select
          aria-label="模型类型筛选"
          value={type}
          onChange={(event) => setType(event.target.value as 'all' | ModelResource['type'])}
          options={[{ value: 'all', label: '全部类型' }, ...Object.entries(MODEL_TYPE_LABEL).map(([value, label]) => ({ value, label }))]}
          className="w-[140px]"
        />
        <Select
          aria-label="厂商筛选"
          value={vendor}
          onChange={(event) => setVendor(event.target.value)}
          options={[{ value: 'all', label: '全部厂商' }, ...vendors.map((item) => ({ value: item, label: item }))]}
          className="w-[140px]"
        />
      </div>

      <div className="card mt-4 overflow-hidden">
        {filtered.length === 0 ? (
          <EmptyState icon={<Cpu className="h-5 w-5" />} title="没有找到匹配的模型" description="调整关键词、类型或厂商筛选条件后重试。" />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1040px] text-left">
              <thead className="table-head">
                <tr>
                  {['模型', '类型 / 厂商', '连接状态', '发布版本', '平均延迟', '启用', '操作'].map((head) => (
                    <th key={head} className="px-4 py-2.5 font-medium">{head}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-line/70">
                {filtered.map((model) => (
                  <ModelRow
                    key={model.id}
                    model={model}
                    data={data}
                    testing={testingId === model.id}
                    onTest={() => runTest(model)}
                    onDelete={() => setDeleteId(model.id)}
                    onConfig={() => setDetailId(model.id)}
                    onPublish={() => setPublishId(model.id)}
                    onToggle={(enabled) => requestToggle(model, enabled)}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div className="flex flex-wrap items-center gap-2 border-t border-line bg-canvas/50 px-4 py-2.5">
          <span className="text-2xs text-ink-faint">已发布模型共用统一 Base URL：</span>
          <code className="min-w-0 break-all font-mono text-2xs text-ink">{baseUrl}</code>
          <CopyButton value={baseUrl} label="复制" message="统一调用地址已复制" />
        </div>
      </div>

      <CreateModelDialog open={createOpen} onClose={() => setCreateOpen(false)} />
      {detailModel && (
        <ModelDetailDrawer
          modelId={detailModel.id}
          onClose={() => setDetailId(null)}
          onPublish={() => setPublishId(detailModel.id)}
        />
      )}
      {publishModel && (
        <PublishVersionDialog model={publishModel} open onClose={() => setPublishId(null)} />
      )}

      <ConfirmDialog
        open={pending !== null}
        onClose={() => setPending(null)}
        onConfirm={() => { if (pending) applyToggle(pending.id, pending.enabled) }}
        title={`停用「${pendingModel?.name ?? ''}」？`}
        description="停用后该模型不可用于新 Agent 配置，已有 Agent 运行时会明确报错，其全部对外发布版本也停止接受调用。"
        confirmText="确认停用"
        danger
        loading={busy}
        impacts={[
          `受影响智能体：${affected.length} 个${affected.length ? `（${affected.map((agent) => agent.name).join('、')}）` : ''}`,
          `生效发布版本：${activeVersions} 个，停用后全部不可用`,
          '版本记录与配置保留，恢复启用后原挂载关系自动可用',
        ]}
      />

      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={confirmDelete}
        title={`删除「${deleteModel?.name ?? ''}」？`}
        description="删除后该模型及其全部发布版本将立即下线，已发布 Key 失效，且无法恢复，请确认后再操作。"
        confirmText="确认删除"
        danger
        loading={deleting}
        impacts={[
          `受影响智能体：${deleteAffected.length} 个${deleteAffected.length ? `（${deleteAffected.map((agent) => agent.name).join('、')}）` : ''}，挂载引用会被移除`,
          `发布版本：${deleteVersions.length} 个（其中生效 ${deleteVersions.filter((item) => item.enabled).length} 个），全部失效`,
          '上游 API Key 与各发布版本 Key 一并销毁，操作记录保留在系统日志',
        ]}
      />
    </div>
  )
}
