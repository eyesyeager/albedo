import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { AlertTriangle, X } from 'lucide-react'
import { cn } from '../../lib/cn'
import { Button } from './Button'
import { UnsavedOverlay, useCloseGuard } from './UnsavedOverlay'

export type ModalWidth = 'sm' | 'md' | 'lg' | 'xl'

const WIDTHS: Record<ModalWidth, string> = {
  sm: 'max-w-[400px]',
  md: 'max-w-[480px]',
  lg: 'max-w-[640px]',
  xl: 'max-w-[820px]',
}

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  description?: string
  children: ReactNode
  footer?: ReactNode
  width?: ModalWidth
  dirty?: boolean
}

export function Modal({ open, onClose, title, description, children, footer, width = 'md', dirty = false }: ModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
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
    const timer = window.setTimeout(() => {
      dialogRef.current?.querySelector<HTMLElement>('input, textarea, select, button')?.focus()
    }, 30)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = ''
      window.clearTimeout(timer)
      previous?.focus()
    }
  }, [open])

  if (!open) return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-ink/40 animate-fade-in" onClick={guard.requestClose} />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className={cn('relative z-10 flex max-h-[88vh] w-full flex-col rounded-card bg-white shadow-pop animate-slide-up', WIDTHS[width])}
      >
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div>
            <h2 className="text-md font-semibold text-ink">{title}</h2>
            {description && <p className="mt-1 text-xs text-ink-muted">{description}</p>}
          </div>
          <button
            type="button"
            aria-label="关闭弹窗"
            onClick={guard.requestClose}
            className="rounded-field p-1 text-ink-faint transition-colors hover:bg-canvas hover:text-ink"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="scroll-thin flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex items-center justify-end gap-2 border-t border-line px-5 py-3.5">{footer}</div>}
        <UnsavedOverlay open={guard.confirming} onCancel={guard.cancel} onConfirm={guard.confirm} />
      </div>
    </div>,
    document.body,
  )
}

export interface ConfirmDialogProps {
  open: boolean
  onClose: () => void
  onConfirm: () => void | Promise<void>
  title: string
  description: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  impacts?: string[]
  loading?: boolean
}

export function ConfirmDialog({
  open, onClose, onConfirm, title, description,
  confirmText = '确认', cancelText = '取消', danger, impacts, loading,
}: ConfirmDialogProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      title={title}
      width="sm"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>{cancelText}</Button>
          <Button variant={danger ? 'danger' : 'primary'} loading={loading} onClick={onConfirm}>{confirmText}</Button>
        </>
      }
    >
      <div className="flex gap-3">
        {danger && (
          <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-card bg-danger/10 text-danger">
            <AlertTriangle className="h-4 w-4" aria-hidden />
          </span>
        )}
        <div className="space-y-3">
          <p className="text-sm leading-relaxed text-ink-muted">{description}</p>
          {impacts && impacts.length > 0 && (
            <ul className="space-y-1.5 rounded-field bg-canvas px-3 py-2.5 text-xs text-ink-muted">
              {impacts.map((item) => <li key={item} className="flex gap-2"><span className="text-brand">·</span>{item}</li>)}
            </ul>
          )}
        </div>
      </div>
    </Modal>
  )
}
