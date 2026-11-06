import { Braces, Cpu, Puzzle, Settings2, Type } from 'lucide-react'
import { cn } from '../../lib/cn'

export type ConfigSection = 'basic' | 'prompt' | 'model' | 'capabilities' | 'advanced'

export const CONFIG_SECTIONS: { key: ConfigSection; label: string; icon: typeof Type }[] = [
  { key: 'basic', label: '基础信息', icon: Type },
  { key: 'prompt', label: '提示词', icon: Braces },
  { key: 'model', label: '模型设置', icon: Cpu },
  { key: 'capabilities', label: '能力挂载', icon: Puzzle },
  { key: 'advanced', label: '高级设置', icon: Settings2 },
]

export function ConfigNav({ value, onChange }: { value: ConfigSection; onChange: (key: ConfigSection) => void }) {
  return (
    <nav aria-label="配置分区" className="flex gap-1 overflow-x-auto pb-1 xl:flex-col xl:overflow-visible">
      <p className="hidden px-2 pb-2 pt-1 text-[10px] font-medium tracking-wider text-ink-faint xl:block">配置</p>
      {CONFIG_SECTIONS.map((section) => (
        <button
          key={section.key}
          type="button"
          onClick={() => onChange(section.key)}
          className={cn(
            'flex shrink-0 items-center gap-2 rounded-field px-3 py-2 text-xs transition-colors duration-200',
            value === section.key ? 'bg-brand-soft font-medium text-brand' : 'text-ink-muted hover:bg-canvas hover:text-ink',
          )}
        >
          <section.icon className="h-3.5 w-3.5" aria-hidden />
          {section.label}
        </button>
      ))}
    </nav>
  )
}
