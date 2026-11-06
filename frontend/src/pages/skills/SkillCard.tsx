import { MoreHorizontal, Package, Users } from 'lucide-react'
import type { Skill } from '../../types/domain'
import { formatRelative } from '../../lib/format'
import { Badge } from '../../components/ui'
import { SkillAvatar } from '../../components/common/GradientAvatar'

export function SkillCard({ skill, index, referencedBy, onDetail, onVersions }: { skill: Skill; index: number; referencedBy: number; onDetail: () => void; onVersions: () => void }) {
  return (
    <div className="card flex flex-col p-4 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-pop">
      <div className="flex items-start gap-3">
        <SkillAvatar name={skill.name} index={index} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="truncate text-md font-semibold text-ink">{skill.name}</h3>
            <Badge tone="brand">{skill.version}</Badge>
          </div>
          <p className="mt-1 flex items-center gap-1.5 text-2xs text-ok"><span className="h-1.5 w-1.5 rounded-full bg-ok" />已校验</p>
        </div>
        <button type="button" aria-label={`查看 ${skill.name} 详情`} title="查看详情" onClick={onDetail} className="rounded-field p-1 text-ink-faint transition-colors hover:bg-canvas hover:text-ink">
          <MoreHorizontal className="h-4 w-4" />
        </button>
      </div>

      <p className="mt-3 line-clamp-2 min-h-[36px] text-xs leading-relaxed text-ink-muted">{skill.description}</p>

      <div className="mt-3 flex flex-wrap gap-1.5 border-t border-line pt-3 text-2xs text-ink-faint">
        <span className="inline-flex items-center gap-1 rounded-pill bg-canvas px-2 py-1"><Package className="h-3 w-3" />{skill.fileName}</span>
        <span className="rounded-pill bg-canvas px-2 py-1">{skill.fileSize}</span>
        <span className="inline-flex items-center gap-1 rounded-pill bg-canvas px-2 py-1"><Users className="h-3 w-3" />{referencedBy} 个 Agent</span>
      </div>

      <div className="mt-3 flex items-center justify-between">
        <span className="text-2xs text-ink-faint">更新于 {formatRelative(skill.updatedAt)}</span>
        <button type="button" onClick={onVersions} className="text-2xs font-medium text-brand transition-colors hover:text-brand-hover">版本记录 →</button>
      </div>
    </div>
  )
}
