import { useLocation } from 'react-router-dom'
import { Bell, CircleHelp, Menu, Search } from 'lucide-react'
import { useWorkspace } from '../../store/workspace'
import { IconButton, Popover } from '../ui'
import { NotificationPanel } from './NotificationPanel'

const LABELS: { match: (path: string) => boolean; label: string }[] = [
  { match: (path) => path === '/', label: '概览' },
  { match: (path) => /^\/agents\/[^/]+/.test(path), label: '智能体配置' },
  { match: (path) => path.startsWith('/agents'), label: '智能体' },
  { match: (path) => path.endsWith('/mcp'), label: 'MCP 管理' },
  { match: (path) => path.endsWith('/skills'), label: 'Skills 管理' },
  { match: (path) => path.endsWith('/tools'), label: '内置工具' },
  { match: (path) => path.endsWith('/models'), label: '模型管理' },
  { match: (path) => path.startsWith('/logs'), label: '调用日志' },
  { match: (path) => path.startsWith('/settings'), label: '系统设置' },
]

function breadcrumb(pathname: string): string {
  return LABELS.find((item) => item.match(pathname))?.label ?? '概览'
}

interface TopbarProps {
  onOpenMenu: () => void
  onOpenSearch: () => void
  onOpenHelp: () => void
  notificationOpen: boolean
  onToggleNotification: () => void
  onCloseNotification: () => void
}

export function Topbar({ onOpenMenu, onOpenSearch, onOpenHelp, notificationOpen, onToggleNotification, onCloseNotification }: TopbarProps) {
  const location = useLocation()
  const hasUnread = useWorkspace((state) => state.notifications.some((item) => !item.read))

  return (
    <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-line bg-white/95 px-4 backdrop-blur lg:px-6">
      <div className="flex min-w-0 items-center gap-3">
        <button type="button" aria-label="打开导航菜单" onClick={onOpenMenu} className="rounded-field p-1.5 text-ink-muted hover:bg-canvas lg:hidden">
          <Menu className="h-4 w-4" />
        </button>
        <p className="truncate text-xs text-ink-faint">
          albedo <span className="mx-1.5 text-line">/</span> <span className="font-medium text-ink">{breadcrumb(location.pathname)}</span>
        </p>
      </div>

      <div className="flex shrink-0 items-center gap-1.5">
        <button
          type="button"
          aria-label="打开全局搜索"
          onClick={onOpenSearch}
          className="hidden h-8 w-64 items-center gap-2 rounded-field border border-line bg-canvas/60 px-3 text-xs text-ink-faint transition-colors hover:border-brand/40 hover:bg-white md:flex"
        >
          <Search className="h-3.5 w-3.5" aria-hidden />
          <span className="flex-1 text-left">搜索智能体、模型…</span>
          <kbd className="rounded border border-line bg-white px-1.5 py-0.5 font-mono text-[10px]">⌘ K</kbd>
        </button>
        <IconButton label="打开全局搜索" className="md:hidden" onClick={onOpenSearch}>
          <Search className="h-4 w-4" />
        </IconButton>
        <IconButton label="帮助与快捷键" onClick={onOpenHelp}>
          <CircleHelp className="h-4 w-4" />
        </IconButton>
        <Popover
          open={notificationOpen}
          onClose={onCloseNotification}
          trigger={
            <IconButton label="通知" badge={hasUnread} active={notificationOpen} onClick={onToggleNotification}>
              <Bell className="h-4 w-4" />
            </IconButton>
          }
        >
          <NotificationPanel onClose={onCloseNotification} />
        </Popover>
      </div>
    </header>
  )
}
