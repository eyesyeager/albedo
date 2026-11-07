import { useMemo, useState } from 'react'
import { Blocks, Plus, Search } from 'lucide-react'
import { formatDateTime } from '../lib/format'
import { useDebouncedValue } from '../hooks/useDebouncedValue'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { useWorkspace } from '../store/workspace'
import { Button, ConfirmDialog, EmptyState, Input, Modal, Badge, toast } from '../components/ui'
import { PageHeader } from '../components/common/PageHeader'
import { SkillCard } from './skills/SkillCard'
import { SkillDetailDrawer } from './skills/SkillDetailDrawer'
import { UploadSkillDialog } from './skills/UploadSkillDialog'

export function SkillsPage() {
  const data = useWorkspace()
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadTargetId, setUploadTargetId] = useState<string | null>(null)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [versionId, setVersionId] = useState<string | null>(null)
  const [deleteId, setDeleteId] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  const filtered = useMemo(() => {
    const query = debounced.trim().toLowerCase()
    return data.skills.filter((skill) => !query || `${skill.name} ${skill.description} ${skill.instructions}`.toLowerCase().includes(query))
  }, [data.skills, debounced])

  const versionSkill = data.skills.find((skill) => skill.id === versionId)
  const uploadTarget = data.skills.find((skill) => skill.id === uploadTargetId) ?? null
  const deleteSkill = data.skills.find((skill) => skill.id === deleteId) ?? null
  const deleteAgents = deleteSkill
    ? data.agents.filter((agent) => agent.mounts.skillIds.includes(deleteSkill.id)).map((agent) => agent.name)
    : []
  const referencedCount = (skillId: string) => data.agents.filter((agent) => agent.mounts.skillIds.includes(skillId)).length

  const requestDelete = (skillId: string) => {
    const skill = data.skills.find((item) => item.id === skillId)
    if (!skill) return
    const used = referencedCount(skill.id)
    if (used > 0) {
      toast.error(`「${skill.name}」已被 ${used} 个智能体挂载，请先在智能体「能力挂载」中取消挂载后再删除`)
      return
    }
    setDetailId(null)
    setDeleteId(skill.id)
  }

  const confirmDelete = () => {
    if (!deleteId || deleting) return
    setDeleting(true)
    api.skills
      .remove(deleteId)
      .then(() => {
        toast.success('Skill 已删除，历史版本与本地包文件同步清理')
        setDeleteId(null)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeleting(false))
  }

  return (
    <div className="mx-auto max-w-[1400px]">
      <PageHeader
        title="Skills 管理"
        description="上传并管理本地化能力包，供智能体按需挂载"
        actions={<Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => { setUploadTargetId(null); setUploadOpen(true) }}>上传 Skill</Button>}
      />

      <div className="card flex flex-wrap items-center gap-2.5 p-3">
        <Input
          aria-label="搜索 Skill"
          placeholder="搜索名称或说明…"
          leading={<Search className="h-3.5 w-3.5" />}
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          className="min-w-[240px] flex-1"
        />
        <span className="text-xs text-ink-muted">共 {filtered.length} 个结果</span>
      </div>

      {filtered.length === 0 ? (
        <div className="card mt-4">
          <EmptyState
            icon={<Blocks className="h-5 w-5" />}
            title={data.skills.length === 0 ? '还没有 Skill' : '没有找到匹配的 Skill'}
            description={data.skills.length === 0 ? '上传第一个能力包，为智能体扩展专项能力。' : '换个关键词或清除搜索条件后重试。'}
            action={data.skills.length === 0 && <Button icon={<Plus className="h-3.5 w-3.5" />} onClick={() => { setUploadTargetId(null); setUploadOpen(true) }}>上传 Skill</Button>}
          />
        </div>
      ) : (
        <div className="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((skill, index) => (
            <SkillCard
              key={skill.id}
              skill={skill}
              index={index}
              referencedBy={referencedCount(skill.id)}
              onDetail={() => setDetailId(skill.id)}
              onVersions={() => setVersionId(skill.id)}
            />
          ))}
        </div>
      )}

      <UploadSkillDialog open={uploadOpen} targetSkill={uploadTarget} onClose={() => { setUploadOpen(false); setUploadTargetId(null) }} />
      {detailId && <SkillDetailDrawer skillId={detailId} onClose={() => setDetailId(null)} onDelete={requestDelete} />}

      <ConfirmDialog
        open={deleteId !== null}
        onClose={() => setDeleteId(null)}
        onConfirm={confirmDelete}
        title={`删除 Skill · ${deleteSkill?.name ?? ''}`}
        description="删除后该 Skill 及其全部历史版本、本地包文件将不可恢复，操作会写入系统操作日志。"
        confirmText="删除"
        danger
        loading={deleting}
        impacts={deleteAgents.length > 0 ? deleteAgents.map((name) => `仍被智能体「${name}」挂载，需先取消挂载`) : undefined}
      />

      <Modal
        open={versionId !== null}
        onClose={() => setVersionId(null)}
        title={`版本记录 · ${versionSkill?.name ?? ''}`}
        description="同一 Skill 的历史上传版本，新版本不会覆盖历史包"
        width="md"
      >
        <div className="space-y-2">
          {versionSkill?.versions.map((version, index) => (
            <div key={`${version.version}-${version.createdAt}`} className="flex items-start gap-3 rounded-field border border-line p-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <p className="font-mono text-xs font-medium text-ink">{version.version}</p>
                  {index === 0 && <Badge tone="brand">当前版本</Badge>}
                </div>
                <p className="mt-1 text-2xs text-ink-muted">{version.note}</p>
                <p className="mt-1 text-2xs text-ink-faint">{formatDateTime(version.createdAt)} · {version.fileSize}</p>
              </div>
            </div>
          ))}
          <button
            type="button"
            onClick={() => {
              if (versionSkill) setUploadTargetId(versionSkill.id)
              setVersionId(null)
              setUploadOpen(true)
            }}
            className="w-full rounded-field border border-dashed border-line py-2.5 text-xs text-ink-muted transition-colors hover:border-brand/40 hover:text-brand"
          >
            上传新版本
          </button>
          <p className="px-1 text-2xs leading-relaxed text-ink-faint">
            新版本不覆盖历史包；已有 Agent 默认继续使用挂载时的版本，如需升级请在对应智能体的「能力挂载」中重新选择。
          </p>
        </div>
      </Modal>
    </div>
  )
}
