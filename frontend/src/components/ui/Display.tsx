import { useEffect, useRef, type ReactNode } from 'react'
import { ChevronLeft, ChevronRight, Copy } from 'lucide-react'
import { cn } from '../../lib/cn'
import { copyText } from '../../lib/clipboard'
import { toast } from './Feedback'

export type BadgeTone = 'brand' | 'ok' | 'warn' | 'danger' | 'neutral' | 'info'

const BADGE_TONES: Record<BadgeTone, string> = {
  brand: 'bg-brand-soft text-brand',
  ok: 'bg-ok/10 text-ok',
  warn: 'bg-warn/10 text-warn',
  danger: 'bg-danger/10 text-danger',
  neutral: 'bg-canvas text-ink-muted',
  info: 'bg-blue-50 text-blue-600',
}

export function Badge({ tone = 'neutral', dot, children, className }: { tone?: BadgeTone; dot?: boolean; children: ReactNode; className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-2xs font-medium', BADGE_TONES[tone], className)}>
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />}
      {children}
    </span>
  )
}

export interface TabItem {
  key: string
  label: string
  count?: number
}

export function Tabs({ items, value, onChange, className }: { items: TabItem[]; value: string; onChange: (key: string) => void; className?: string }) {
  return (
    <div className={cn('flex items-center gap-5 border-b border-line', className)} role="tablist">
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          role="tab"
          aria-selected={value === item.key}
          onClick={() => onChange(item.key)}
          className={cn(
            'relative -mb-px flex items-center gap-1.5 border-b-2 px-0.5 pb-2.5 pt-1 text-sm font-medium transition-colors duration-200',
            value === item.key ? 'border-brand text-brand' : 'border-transparent text-ink-muted hover:text-ink',
          )}
        >
          {item.label}
          {item.count !== undefined && (
            <span className={cn('rounded-pill px-1.5 py-0.5 text-2xs', value === item.key ? 'bg-brand-soft text-brand' : 'bg-canvas text-ink-faint')}>
              {item.count}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}

export function Segmented({ items, value, onChange, className }: { items: TabItem[]; value: string; onChange: (key: string) => void; className?: string }) {
  return (
    <div className={cn('inline-flex items-center gap-0.5 rounded-field bg-canvas p-1', className)}>
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          onClick={() => onChange(item.key)}
          className={cn(
            'rounded-[6px] px-3 py-1.5 text-xs font-medium transition-all duration-200',
            value === item.key ? 'bg-white text-brand shadow-card' : 'text-ink-muted hover:text-ink',
          )}
        >
          {item.label}
          {item.count !== undefined && <span className="ml-1 text-2xs text-ink-faint">{item.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function Pagination({ page, pageCount, total, onChange }: { page: number; pageCount: number; total: number; onChange: (page: number) => void }) {
  const pages = Array.from({ length: Math.min(pageCount, 5) }, (_, index) => {
    const start = Math.max(1, Math.min(page - 2, pageCount - 4))
    return start + index
  }).filter((value) => value >= 1 && value <= pageCount)

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line px-4 py-3">
      <p className="text-xs text-ink-faint">共 {total} 条记录 · 第 {page} / {Math.max(1, pageCount)} 页</p>
      <div className="flex items-center gap-1">
        <button type="button" aria-label="上一页" disabled={page <= 1} onClick={() => onChange(page - 1)} className="flex h-7 w-7 items-center justify-center rounded-field border border-line text-ink-muted transition-colors hover:border-brand/40 hover:text-brand disabled:opacity-40">
          <ChevronLeft className="h-3.5 w-3.5" />
        </button>
        {pages.map((value) => (
          <button
            key={value}
            type="button"
            onClick={() => onChange(value)}
            className={cn('h-7 min-w-7 rounded-field px-2 text-xs transition-colors', value === page ? 'bg-brand text-white' : 'border border-line text-ink-muted hover:border-brand/40 hover:text-brand')}
          >
            {value}
          </button>
        ))}
        <button type="button" aria-label="下一页" disabled={page >= pageCount} onClick={() => onChange(page + 1)} className="flex h-7 w-7 items-center justify-center rounded-field border border-line text-ink-muted transition-colors hover:border-brand/40 hover:text-brand disabled:opacity-40">
          <ChevronRight className="h-3.5 w-3.5" />
        </button>
      </div>
    </div>
  )
}

export function CodeBlock({ code, language = 'bash', maxHeight = '320px', className }: { code: string; language?: string; maxHeight?: string; className?: string }) {
  const copy = async () => {
    const ok = await copyText(code)
    if (ok) toast.success('代码已复制到剪贴板')
    else toast.error('复制失败，请手动选择代码复制')
  }
  return (
    <div className={cn('overflow-hidden rounded-field bg-[#151821]', className)}>
      <div className="flex items-center justify-between border-b border-white/10 px-3 py-2">
        <span className="font-mono text-2xs uppercase tracking-wide text-gray-400">{language}</span>
        <button type="button" aria-label="复制代码" onClick={copy} className="inline-flex items-center gap-1 text-2xs text-gray-400 transition-colors hover:text-white">
          <Copy className="h-3 w-3" aria-hidden />复制
        </button>
      </div>
      <pre className="scroll-thin overflow-auto p-4 font-mono text-xs leading-relaxed text-gray-100" style={{ maxHeight }}>
        <code>{code}</code>
      </pre>
    </div>
  )
}

export interface PopoverProps {
  open: boolean
  onClose: () => void
  trigger: ReactNode
  children: ReactNode
  align?: 'left' | 'right'
  panelClassName?: string
  className?: string
}

export function Popover({ open, onClose, trigger, children, align = 'right', panelClassName, className }: PopoverProps) {
  const ref = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose

  useEffect(() => {
    if (!open) return
    const onPointerDown = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) closeRef.current()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') closeRef.current()
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div ref={ref} className={cn('relative', className)}>
      {trigger}
      {open && (
        <div className={cn('absolute top-[calc(100%+8px)] z-40 rounded-card border border-line bg-white shadow-pop animate-slide-up', align === 'right' ? 'right-0' : 'left-0', panelClassName)}>
          {children}
        </div>
      )}
    </div>
  )
}
