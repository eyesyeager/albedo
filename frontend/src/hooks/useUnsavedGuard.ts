import { useEffect } from 'react'
import { useBlocker } from 'react-router-dom'

export function useUnsavedGuard(active: boolean) {
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) => active && currentLocation.pathname !== nextLocation.pathname,
  )

  useEffect(() => {
    if (!active) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [active])

  return blocker
}
