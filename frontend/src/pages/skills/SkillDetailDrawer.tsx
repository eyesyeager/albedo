import { CheckCircle2, Clock3, Trash2, Users } from 'lucide-react'
import { formatRelative } from '../../lib/format'
import { useWorkspace } from '../../store/workspace'
import { Badge, Button, CopyButton, Drawer } from '../../components/ui'
import { SkillAvatar } from '../../components/common/GradientAvatar'

export function SkillDetailDrawer({ skillId, onClose, onDelete }: { skillId: string | null; onClose: () => void; onDelete: (id: string) => void }) {
  const data = useWorkspace()
  const skill = data.skills.find((item) => item.id === skillId)
  if (!skill) return null
  const referencedBy = data.agents.filter((agent) => agent.mounts.skillIds.includes(skill.id)).length

  return (
    <Drawer
      open
      onClose={onClose}
      title={skill.name}
      subtitle={`${skill.fileName} · ${skill.fileSize}`}
      width="md"
      footer={
        <>
          <Button
            variant="ghost"
            className="mr-auto text-danger hover:bg-danger/10 hover:text-danger"
            icon={<Trash2 className="h-3.5 w-3.5" />}
            onClick={() => onDelete(skill.id)}
          >
            删除
          </Button>
          <CopyButton value={skill.id} label="复制 Skill ID" message="Skill ID 已复制" />
        </>
      }
    >
      <div className="space-y-5">
        <div className="flex items-center gap-3 rounded-field border border-line bg-canvas/50 p-3">
          <SkillAvatar name={skill.name} size="md" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <p className="text-sm font-semibold text-ink">{skill.name}</p>
              <Badge tone="brand">{skill.version}</Badge>
            </div>
            <p className="mt-1 text-2xs text-ink-faint">{skill.fileSize} · {skill.fileName}</p>
          </div>
          <Badge tone="ok" dot><CheckCircle2 className="h-3 w-3" />已校验</Badge>
        </div>

        <div>
          <p className="mb-2 text-xs font-medium text-ink">功能说明</p>
          <div className="rounded-field border border-line bg-canvas/40 p-3 text-xs leading-relaxed text-ink-muted">
            {skill.instructions}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="rounded-field border border-line p-3">
            <p className="flex items-center gap-1.5 text-2xs text-ink-faint"><Users className="h-3 w-3" />被引用 Agent 数</p>
            <p className="mt-2 text-xl font-semibold text-ink">{referencedBy}</p>
          </div>
          <div className="rounded-field border border-line p-3">
            <p className="flex items-center gap-1.5 text-2xs text-ink-faint"><Clock3 className="h-3 w-3" />最近更新</p>
            <p className="mt-2 text-sm font-medium text-ink">{formatRelative(skill.updatedAt)}</p>
          </div>
        </div>

        <div className="rounded-field border border-line p-3">
          <p className="text-2xs text-ink-faint">Skill ID</p>
          <code className="mt-1 block break-all font-mono text-xs text-ink">{skill.id}</code>
        </div>

        <div className="rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
          新版本上传后，已有 Agent 不会自动升级；Skill 更新、启用与删除均写入系统操作日志。
        </div>
        <Button variant="outline" block onClick={onClose}>关闭</Button>
      </div>
    </Drawer>
  )
}
