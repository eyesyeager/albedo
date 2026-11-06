import { useState } from 'react'
import { Outlet } from 'react-router-dom'
import { useHotkeys } from '../../hooks/useHotkeys'
import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'
import { GlobalSearch } from './GlobalSearch'
import { HelpDialog } from './HelpDialog'

export function AppShell() {
  const [mobileOpen, setMobileOpen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [notificationOpen, setNotificationOpen] = useState(false)

  useHotkeys({
    onSearch: () => setSearchOpen((open) => !open),
    onEscape: () => {
      setSearchOpen(false)
      setHelpOpen(false)
      setNotificationOpen(false)
      setMobileOpen(false)
    },
  })

  return (
    <div className="min-h-screen bg-canvas">
      <Sidebar mobileOpen={mobileOpen} onClose={() => setMobileOpen(false)} />
      <div className="flex min-h-screen min-w-0 flex-col lg:ml-[172px]">
        <Topbar
          onOpenMenu={() => setMobileOpen(true)}
          onOpenSearch={() => setSearchOpen(true)}
          onOpenHelp={() => setHelpOpen(true)}
          notificationOpen={notificationOpen}
          onToggleNotification={() => setNotificationOpen((open) => !open)}
          onCloseNotification={() => setNotificationOpen(false)}
        />
        <main className="flex-1 px-4 py-6 lg:px-8">
          <Outlet />
        </main>
      </div>
      <GlobalSearch open={searchOpen} onClose={() => setSearchOpen(false)} />
      <HelpDialog open={helpOpen} onClose={() => setHelpOpen(false)} />
    </div>
  )
}
