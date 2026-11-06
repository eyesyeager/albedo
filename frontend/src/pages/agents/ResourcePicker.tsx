import { useEffect, useMemo, useState } from 'react'
import { Search } from 'lucide-react'
import { useWorkspace } from '../../store/workspace'
import { Button, Checkbox, EmptyState, Input, Modal } from '../../components/ui'

export type ResourceKind = 'model' | 'mcp' | 'skill' | 'tool'

interface PickerItem {
  id: string
  name: string
  description: string
  available: boolean
}

export const RESOURCE_LABEL: Record<ResourceKind, string> = {
  model: '模型',
  mcp: 'MCP 服务器',
  skill: 'Skills',
  tool: '内置工具',
}

export function ResourcePicker({
  open, onClose, kind, selected, onConfirm,
}: {
  open: boolean
  onClose: () => void
  kind: ResourceKind
  selected: string[]
  onConfirm: (ids: string[]) => void
}) {
  const data = useWorkspace()
  const [keyword, setKeyword] = useState('')
  const [checked, setChecked] = useState<string[]>([])

  useEffect(() => {
    if (!open) return
    setKeyword('')
    setChecked([])
  }, [open])

  const items = useMemo<PickerItem[]>(() => {
    if (kind === 'model') return data.models.filter((model) => model.type === 'chat').map((model) => ({ id: model.id, name: model.name, description: `${model.vendor} · ${model.upstreamModelId}`, available: model.enabled && model.connection === 'normal' }))
    if (kind === 'mcp') return data.mcps.map((mcp) => ({ id: mcp.id, name: mcp.name, description: mcp.description, available: mcp.enabled }))
    if (kind === 'skill') return data.skills.map((skill) => ({ id: skill.id, name: skill.name, description: skill.description, available: true }))
    return data.tools.map((tool) => ({ id: tool.id, name: tool.name, description: `${tool.key} · ${tool.description}`, available: tool.enabled }))
  }, [data, kind])

  const visible = items.filter((item) => {
    if (kind === 'tool' && !item.available && !selected.includes(item.id)) return false
    return `${item.name} ${item.description}`.toLowerCase().includes(keyword.trim().toLowerCase())
  })

  const toggle = (id: string) => setChecked((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id])

  const confirm = () => {
    onConfirm(checked)
    onClose()
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={`添加${RESOURCE_LABEL[kind]}`}
      description="从平台资源池勾选能力，可一次添加多个，同一资源不可重复挂载"
      width="md"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button disabled={checked.length === 0} onClick={confirm}>添加 {checked.length || ''} 项</Button>
        </>
      }
    >
      <Input
        aria-label="搜索资源"
        placeholder="搜索名称或描述…"
        leading={<Search className="h-3.5 w-3.5" />}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        className="mb-3"
      />
      <div className="scroll-thin max-h-[320px] space-y-1 overflow-y-auto pr-1">
        {visible.length === 0 && <EmptyState compact title="没有可用资源" description="调整搜索词，或先在资源管理中启用对应能力。" />}
        {visible.map((item) => {
          const mounted = selected.includes(item.id)
          return (
            <div
              key={item.id}
              className={`flex items-start gap-3 rounded-field border px-3 py-2.5 transition-colors ${mounted ? 'border-line bg-canvas/60' : 'border-transparent hover:border-brand/20 hover:bg-brand-faint'}`}
            >
              <Checkbox
                checked={mounted || checked.includes(item.id)}
                disabled={mounted || !item.available}
                onChange={() => toggle(item.id)}
                label=""
              />
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-medium text-ink">
                  {item.name}
                  {mounted && <span className="ml-2 text-2xs font-normal text-ink-faint">已挂载</span>}
                  {!item.available && !mounted && <span className="ml-2 text-2xs font-normal text-warn">不可用</span>}
                </p>
                <p className="mt-0.5 line-clamp-2 text-2xs leading-relaxed text-ink-muted">{item.description}</p>
              </div>
            </div>
          )
        })}
      </div>
    </Modal>
  )
}
