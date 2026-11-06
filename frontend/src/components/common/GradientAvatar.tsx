import { Bot } from 'lucide-react'
import type { AgentTheme } from '../../types/domain'
import { cn } from '../../lib/cn'

export const AGENT_THEMES: Record<AgentTheme, string> = {
  violet: 'from-[#8B7CFF] to-[#5B4BE1]',
  blue: 'from-[#5AA9F8] to-[#2E7BE4]',
  mint: 'from-[#4FD1A5] to-[#1FA97B]',
  orange: 'from-[#FFB35C] to-[#F5822B]',
}

export const AGENT_THEME_OPTIONS: { key: AgentTheme; label: string }[] = [
  { key: 'violet', label: '紫色' },
  { key: 'blue', label: '蓝色' },
  { key: 'mint', label: '薄荷绿' },
  { key: 'orange', label: '橙色' },
]

export function GradientAvatar({ theme, name, size = 'md', className }: { theme: AgentTheme; name?: string; size?: 'sm' | 'md' | 'lg'; className?: string }) {
  const sizes = { sm: 'h-8 w-8 rounded-[9px]', md: 'h-10 w-10 rounded-[11px]', lg: 'h-12 w-12 rounded-card' }
  return (
    <span
      aria-label={name ? `${name} 头像` : '智能体头像'}
      className={cn('inline-flex shrink-0 items-center justify-center bg-gradient-to-br text-white shadow-soft', AGENT_THEMES[theme], sizes[size], className)}
    >
      <Bot className={size === 'sm' ? 'h-4 w-4' : 'h-5 w-5'} aria-hidden />
    </span>
  )
}

const SKILL_COLORS = [
  'bg-brand-soft text-brand',
  'bg-blue-50 text-blue-600',
  'bg-orange-50 text-orange-600',
]

export function SkillAvatar({ name, index = 0, size = 'md', className }: { name: string; index?: number; size?: 'sm' | 'md'; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        'inline-flex shrink-0 items-center justify-center font-semibold',
        size === 'sm' ? 'h-8 w-8 rounded-[9px] text-sm' : 'h-10 w-10 rounded-[11px] text-md',
        SKILL_COLORS[index % SKILL_COLORS.length],
        className,
      )}
    >
      {name.slice(0, 1)}
    </span>
  )
}
