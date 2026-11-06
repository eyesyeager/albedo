import { useState } from 'react'
import { Blocks, Cpu, Plus, Plug, Trash2, Wrench } from 'lucide-react'
import type { AgentMounts } from '../../types/domain'
import { useWorkspace } from '../../store/workspace'
import { Button, Badge } from '../../components/ui'
import { IssueHint } from '../../components/common/StatusBadge'
import { SectionCard } from '../../components/common/PageHeader'
import { RESOURCE_LABEL, ResourcePicker, type ResourceKind } from './ResourcePicker'
import type { PanelProps } from './BasicInfoPanel'

type MountKey = keyof AgentMounts

const GROUP_META: { kind: ResourceKind; key: MountKey; icon: typeof Cpu; description: string }[] = [
  { kind: 'model', key: 'modelIds', icon: Cpu, description: '指定智能体运行所用的底层 LLM，与默认模型联动' },
  { kind: 'mcp', key: 'mcpIds', icon: Plug, description: '调用外部系统或 API 的工具能力，工具级可用性由 MCP 管理控制' },
  { kind: 'skill', key: 'skillIds', icon: Blocks, description: '挂载打包好的专项技能，版本由 Skill 资源管理' },
  { kind: 'tool', key: 'toolIds', icon: Wrench, description: '平台内置的通用工具，仅可挂载全局启用的工具' },
]

export function CapabilitiesPanel({ draft, onChange }: PanelProps) {
  const data = useWorkspace()
  const [picker, setPicker] = useState<ResourceKind | null>(null)

  const describe = (kind: ResourceKind, id: string): { name: string; summary: string; invalid: boolean } => {
    if (kind === 'model') {
      const model = data.models.find((item) => item.id === id)
      return { name: model?.name ?? id, summary: model ? `${model.vendor} · ${model.upstreamModelId}` : '资源已丢失', invalid: !model || !model.enabled || model.connection !== 'normal' }
    }
    if (kind === 'mcp') {
      const mcp = data.mcps.find((item) => item.id === id)
      const tools = mcp?.tools.filter((tool) => tool.enabled).length ?? 0
      return { name: mcp?.name ?? id, summary: mcp ? `${tools} 个可用工具 · ${mcp.transport}` : '资源已丢失', invalid: !mcp || !mcp.enabled }
    }
    if (kind === 'skill') {
      const skill = data.skills.find((item) => item.id === id)
      return { name: skill?.name ?? id, summary: skill ? `${skill.version} · ${skill.fileSize}` : '资源已丢失', invalid: !skill }
    }
    const tool = data.tools.find((item) => item.id === id)
    return { name: tool?.name ?? id, summary: tool ? `${tool.key} · ${tool.version}` : '资源已丢失', invalid: !tool || !tool.enabled }
  }

  const remove = (key: MountKey, id: string) => {
    onChange({ mounts: { ...draft.mounts, [key]: draft.mounts[key].filter((item) => item !== id) } })
  }

  const add = (kind: ResourceKind, key: MountKey, ids: string[]) => {
    onChange({ mounts: { ...draft.mounts, [key]: Array.from(new Set([...draft.mounts[key], ...ids])) } })
    setPicker(null)
  }

  const total = GROUP_META.reduce((sum, group) => sum + draft.mounts[group.key].length, 0)

  return (
    <div className="space-y-4">
      <SectionCard title="能力挂载" description={`模型、MCP、Skills 与内置工具共同构成智能体的能力集合，当前共挂载 ${total} 项`}>
        <div className="space-y-4">
          {GROUP_META.map((group) => (
            <div key={group.key} className="rounded-field border border-line">
              <div className="flex items-center justify-between gap-2 border-b border-line bg-canvas/50 px-3 py-2.5">
                <div className="flex items-center gap-2">
                  <span className="flex h-6 w-6 items-center justify-center rounded-[6px] bg-brand-soft text-brand"><group.icon className="h-3 w-3" aria-hidden /></span>
                  <div>
                    <p className="text-xs font-medium text-ink">{RESOURCE_LABEL[group.kind]} <span className="ml-1 font-normal text-ink-faint">{draft.mounts[group.key].length}</span></p>
                    <p className="text-2xs text-ink-faint">{group.description}</p>
                  </div>
                </div>
                <Button variant="secondary" size="sm" icon={<Plus className="h-3 w-3" />} onClick={() => setPicker(group.kind)}>添加</Button>
              </div>
              {draft.mounts[group.key].length === 0 ? (
                <p className="px-3 py-3 text-2xs text-ink-faint">暂未挂载{RESOURCE_LABEL[group.kind]}</p>
              ) : (
                <div className="divide-y divide-line/70">
                  {draft.mounts[group.key].map((id) => {
                    const item = describe(group.kind, id)
                    return (
                      <div key={id} className="flex items-center gap-3 px-3 py-2.5">
                        <div className="min-w-0 flex-1">
                          <p className="flex items-center gap-2 truncate text-xs font-medium text-ink">
                            {item.name}
                            {item.invalid && <IssueHint text="已失效" />}
                          </p>
                          <p className="truncate text-2xs text-ink-faint">{item.summary}</p>
                        </div>
                        <Badge tone="neutral">{group.kind === 'model' ? 'Chat' : group.kind.toUpperCase()}</Badge>
                        <button type="button" aria-label={`移除 ${item.name}`} title="仅从当前智能体移除" onClick={() => remove(group.key, id)} className="rounded p-1 text-ink-faint transition-colors hover:text-danger">
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          ))}
        </div>
      </SectionCard>

      {picker && (
        <ResourcePicker
          open
          onClose={() => setPicker(null)}
          kind={picker}
          selected={draft.mounts[GROUP_META.find((group) => group.kind === picker)!.key]}
          onConfirm={(ids) => add(picker, GROUP_META.find((group) => group.kind === picker)!.key, ids)}
        />
      )}
    </div>
  )
}
