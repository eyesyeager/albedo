import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle } from 'lucide-react'
import { Button } from './Button'

export function useCloseGuard(onClose: () => void, dirty: boolean) {
  const [confirming, setConfirming] = useState(false)
  const closeRef = useRef(onClose)
  const dirtyRef = useRef(dirty)
  const confirmingRef = useRef(confirming)
  closeRef.current = onClose
  dirtyRef.current = dirty
  confirmingRef.current = confirming

  const requestClose = useCallback(() => {
    if (dirtyRef.current && !confirmingRef.current) {
      setConfirming(true)
      return
    }
    setConfirming(false)
    closeRef.current()
  }, [])

  const cancel = useCallback(() => setConfirming(false), [])
  const confirm = useCallback(() => {
    setConfirming(false)
    closeRef.current()
  }, [])

  useEffect(() => {
    if (!dirty) setConfirming(false)
  }, [dirty])

  return { confirming, requestClose, cancel, confirm }
}

export function UnsavedOverlay({
  open, onCancel, onConfirm,
}: {
  open: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  if (!open) return null
  return (
    <div className="absolute inset-0 z-20 flex items-center justify-center rounded-card bg-ink/50 px-6 animate-fade-in">
      <div className="w-full max-w-[320px] rounded-card bg-white p-5 shadow-pop">
        <div className="flex items-center gap-2">
          <AlertTriangle className="h-4 w-4 text-warn" aria-hidden />
          <p className="text-sm font-semibold text-ink">放弃未保存的修改？</p>
        </div>
        <p className="mt-2 text-xs leading-relaxed text-ink-muted">关闭后当前填写或编辑的内容会丢失，且无法恢复。</p>
        <div className="mt-4 flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onCancel}>继续编辑</Button>
          <Button variant="danger" size="sm" onClick={onConfirm}>放弃修改</Button>
        </div>
      </div>
    </div>
  )
}
