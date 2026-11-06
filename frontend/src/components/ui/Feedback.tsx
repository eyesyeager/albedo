import type { ReactNode } from 'react'
import { create } from 'zustand'
import { CheckCircle2, Copy, Inbox, Info, X, XCircle } from 'lucide-react'
import { cn } from '../../lib/cn'
import { copyText } from '../../lib/clipboard'
import { Button } from './Button'

type ToastKind = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  kind: ToastKind
  message: string
}

interface ToastState {
  items: ToastItem[]
  push: (kind: ToastKind, message: string, duration: number) => void
  remove: (id: number) => void
}

let sequence = 0

export const useToastStore = create<ToastState>((set) => ({
  items: [],
  push: (kind, message, duration) => {
    const id = ++sequence
    set((state) => ({ items: [...state.items, { id, kind, message }] }))
    window.setTimeout(() => set((state) => ({ items: state.items.filter((item) => item.id !== id) })), duration)
  },
  remove: (id) => set((state) => ({ items: state.items.filter((item) => item.id !== id) })),
}))

export const toast = {
  success: (message: string) => useToastStore.getState().push('success', message, 3200),
  error: (message: string) => useToastStore.getState().push('error', message, 5200),
  info: (message: string) => useToastStore.getState().push('info', message, 3200),
}

const TOAST_ICONS: Record<ToastKind, ReactNode> = {
  success: <CheckCircle2 className="h-4 w-4 text-ok" aria-hidden />,
  error: <XCircle className="h-4 w-4 text-danger" aria-hidden />,
  info: <Info className="h-4 w-4 text-brand" aria-hidden />,
}

export function ToastHost() {
  const items = useToastStore((state) => state.items)
  const remove = useToastStore((state) => state.remove)

  return (
    <div className="pointer-events-none fixed right-5 top-5 z-[100] flex w-[340px] max-w-[calc(100vw-40px)] flex-col gap-2" aria-live="polite">
      {items.map((item) => (
        <div
          key={item.id}
          className={cn(
            'pointer-events-auto flex items-start gap-2.5 rounded-card border bg-white px-3.5 py-3 shadow-pop animate-slide-up',
            item.kind === 'error' ? 'border-danger/30' : 'border-line',
          )}
          role="status"
        >
          <span className="mt-0.5">{TOAST_ICONS[item.kind]}</span>
          <p className="flex-1 text-sm leading-relaxed text-ink">{item.message}</p>
          <button
            type="button"
            aria-label="关闭提示"
            onClick={() => remove(item.id)}
            className="text-ink-faint transition-colors hover:text-ink"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
      ))}
    </div>
  )
}

export interface EmptyStateProps {
  icon?: ReactNode
  title: string
  description?: string
  action?: ReactNode
  compact?: boolean
}

export function EmptyState({ icon, title, description, action, compact }: EmptyStateProps) {
  return (
    <div className={cn('flex flex-col items-center justify-center text-center', compact ? 'py-8' : 'py-14')}>
      <div className="mb-3 flex h-11 w-11 items-center justify-center rounded-card bg-brand-faint text-brand">
        {icon ?? <Inbox className="h-5 w-5" aria-hidden />}
      </div>
      <p className="text-sm font-medium text-ink">{title}</p>
      {description && <p className="mt-1 max-w-sm text-xs leading-relaxed text-ink-muted">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded bg-line/80', className)} aria-hidden />
}

export interface CopyButtonProps {
  value: string
  label?: string
  message?: string
  className?: string
  variant?: 'outline' | 'ghost' | 'secondary'
}

export function CopyButton({ value, label = '复制', message, className, variant = 'outline' }: CopyButtonProps) {
  const handleClick = async () => {
    const ok = await copyText(value)
    if (ok) toast.success(message ?? '已复制到剪贴板')
    else toast.error('复制失败，请手动选择文本复制')
  }
  return (
    <Button variant={variant} size="sm" icon={<Copy className="h-3.5 w-3.5" aria-hidden />} onClick={handleClick} className={className}>
      {label}
    </Button>
  )
}
