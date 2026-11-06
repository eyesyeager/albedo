import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import { cn } from '../../lib/cn'
import { UnsavedOverlay, useCloseGuard } from './UnsavedOverlay'

export interface DrawerProps {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
  width?: 'md' | 'lg' | 'xl'
  dirty?: boolean
}

const WIDTHS = {
  md: 'max-w-[440px]',
  lg: 'max-w-[560px]',
  xl: 'max-w-[720px]',
}

export function Drawer({ open, onClose, title, subtitle, children, footer, width = 'lg', dirty = false }: DrawerProps) {
  const guard = useCloseGuard(onClose, dirty)
  const guardRef = useRef(guard)
  guardRef.current = guard

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement as HTMLElement | null
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (guardRef.current.confirming) guardRef.current.cancel()
      else guardRef.current.requestClose()
    }
    document.addEventListener('keydown', onKeyDown)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = ''
      previous?.focus()
    }
  }, [open])

  if (!open) return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-ink/40 animate-fade-in" onClick={guard.requestClose} />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn('relative z-10 flex h-full w-full flex-col bg-white shadow-pop animate-slide-left', WIDTHS[width])}
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div className="min-w-0">
            <h2 className="truncate text-md font-semibold text-ink">{title}</h2>
            {subtitle && <p className="mt-1 truncate text-xs text-ink-muted">{subtitle}</p>}
          </div>
          <button
            type="button"
            aria-label="关闭抽屉"
            onClick={guard.requestClose}
            className="rounded-field p-1 text-ink-faint transition-colors hover:bg-canvas hover:text-ink"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="scroll-thin flex-1 overflow-y-auto px-5 py-5">{children}</div>
        {footer && <div className="flex items-center justify-end gap-2 border-t border-line px-5 py-3.5">{footer}</div>}
        <UnsavedOverlay open={guard.confirming} onCancel={guard.cancel} onConfirm={guard.confirm} />
      </div>
    </div>,
    document.body,
  )
}
