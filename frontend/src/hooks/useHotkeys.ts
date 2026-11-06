import { useEffect, useRef } from 'react'

interface HotkeyHandlers {
  onSearch?: () => void
  onEscape?: () => void
}

export function useHotkeys(handlers: HotkeyHandlers): void {
  const ref = useRef(handlers)
  ref.current = handlers

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        ref.current.onSearch?.()
      }
      if (event.key === 'Escape') {
        ref.current.onEscape?.()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])
}
