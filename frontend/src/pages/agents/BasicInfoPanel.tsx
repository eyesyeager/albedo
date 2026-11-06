import type { Agent } from '../../types/domain'
import { cn } from '../../lib/cn'
import { AGENT_THEME_OPTIONS, AGENT_THEMES } from '../../components/common/GradientAvatar'
import { Field, Input, Textarea } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'

export interface PanelProps {
  draft: Agent
  onChange: (patch: Partial<Agent>) => void
}

export function BasicInfoPanel({ draft, onChange }: PanelProps) {
  return (
    <div className="space-y-4">
      <SectionCard title="基础信息" description="定义智能体在工作台和对外使用时的基本信息" bodyClassName="space-y-4 p-5">
        <Field label="智能体名称" htmlFor="detail-name" required hint="1～50 字符；列表、对话、发布文档统一使用">
          <Input id="detail-name" value={draft.name} maxLength={50} onChange={(event) => onChange({ name: event.target.value })} />
        </Field>
        <Field label="功能介绍" htmlFor="detail-desc" hint="建议不超过 200 字符">
          <Textarea id="detail-desc" rows={3} value={draft.description} maxLength={200} onChange={(event) => onChange({ description: event.target.value })} />
        </Field>
      </SectionCard>

      <SectionCard title="头像与主题色" description="用于列表、对话与集成页面展示">
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            {AGENT_THEME_OPTIONS.map((option) => (
              <button
                key={option.key}
                type="button"
                aria-label={`选择${option.label}主题`}
                aria-pressed={draft.theme === option.key}
                onClick={() => onChange({ theme: option.key })}
                className={cn(
                  'h-8 w-8 rounded-full bg-gradient-to-br transition-all duration-200',
                  AGENT_THEMES[option.key],
                  draft.theme === option.key && 'ring-2 ring-brand ring-offset-2',
                )}
              />
            ))}
          </div>
          <p className="text-2xs text-ink-faint">主题色仅影响展示，不改变 Agent ID、API 地址或历史日志。</p>
        </div>
      </SectionCard>
    </div>
  )
}
