import { useEffect, useRef, type ReactNode, type RefObject } from 'react'
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

/**
 * 所有弹层共享一个栈：只有栈顶响应 ESC，且只有最外层关闭时才恢复页面滚动。
 * 避免「一次 ESC 关掉整叠弹窗」以及内层关闭把 body overflow 清空导致背景可滚动。
 */
const overlayStack: symbol[] = []

export function useOverlayBehavior(
  open: boolean,
  dialogRef: RefObject<HTMLDivElement>,
  onEscape: () => void,
): void {
  const escapeRef = useRef(onEscape)
  escapeRef.current = onEscape

  useEffect(() => {
    if (!open) return
    const token = Symbol('overlay')
    overlayStack.push(token)
    const previous = document.activeElement as HTMLElement | null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'

    const onKeyDown = (event: KeyboardEvent) => {
      if (overlayStack[overlayStack.length - 1] !== token) return
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        escapeRef.current()
        return
      }
      if (event.key !== 'Tab') return
      const root = dialogRef.current
      if (!root) return
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )).filter((element) => element.offsetParent !== null)
      if (focusables.length === 0) {
        event.preventDefault()
        return
      }
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement
      if (event.shiftKey && (active === first || !root.contains(active))) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !root.contains(active))) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown, true)
    const timer = window.setTimeout(() => {
      dialogRef.current?.querySelector<HTMLElement>('input, textarea, select, button')?.focus()
    }, 30)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
      const index = overlayStack.indexOf(token)
      if (index >= 0) overlayStack.splice(index, 1)
      if (overlayStack.length === 0) document.body.style.overflow = previousOverflow
      window.clearTimeout(timer)
      previous?.focus()
    }
  }, [open, dialogRef])
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

  useOverlayBehavior(open, dialogRef, () => {
    if (guardRef.current.confirming) guardRef.current.cancel()
    else guardRef.current.requestClose()
  })

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
