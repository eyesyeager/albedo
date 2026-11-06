import { NavLink, useNavigate } from 'react-router-dom'
import {
  Blocks, Bot, ChevronRight, Cpu, LayoutDashboard, LogOut, Plug, ScrollText, Settings2, Sparkles, Wrench, X,
} from 'lucide-react'
import { cn } from '../../lib/cn'
import { api } from '../../lib/api'
import { useWorkspace } from '../../store/workspace'
import { toast } from '../ui'
import { AGENT_THEMES } from '../common/GradientAvatar'

interface NavItem {
  path: string
  label: string
  icon: typeof LayoutDashboard
  end?: boolean
  dot?: boolean
}

const GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: '工作台',
    items: [
      { path: '/', label: '概览', icon: LayoutDashboard, end: true },
      { path: '/agents', label: '智能体', icon: Bot },
    ],
  },
  {
    label: '能力资源',
    items: [
      { path: '/resources/mcp', label: 'MCP 管理', icon: Plug },
      { path: '/resources/skills', label: 'Skills 管理', icon: Blocks },
      { path: '/resources/tools', label: '内置工具', icon: Wrench },
      { path: '/resources/models', label: '模型管理', icon: Cpu },
    ],
  },
  {
    label: '运营与治理',
    items: [{ path: '/logs', label: '调用日志', icon: ScrollText, dot: true }],
  },
]

export function Sidebar({ mobileOpen, onClose }: { mobileOpen: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const workspaceName = useWorkspace((state) => state.settings.workspaceName)
  const version = useWorkspace((state) => state.systemConfig.platform.version)
  const hasAbnormalLogs = useWorkspace((state) => state.logs.some((log) => log.status === 'failed' || log.status === 'timeout'))

  const handleLogout = async () => {
    await api.auth.logout()
    toast.success('已退出登录')
    navigate('/login')
  }

  return (
    <>
      {mobileOpen && <div className="fixed inset-0 z-40 bg-ink/50 lg:hidden" onClick={onClose} aria-hidden />}
      <aside
        className={cn(
          'fixed left-0 top-0 z-40 flex h-screen w-[172px] flex-col bg-sidebar text-white transition-transform duration-200 lg:translate-x-0',
          mobileOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <div className="flex h-14 items-center justify-between px-4">
          <div className="flex items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-[10px] bg-gradient-to-br from-brand-light to-brand-hover shadow-soft">
              <Sparkles className="h-4 w-4" aria-hidden />
            </span>
            <div className="leading-tight">
              <p className="text-md font-semibold tracking-tight">albedo</p>
              <p className="text-[9px] tracking-[0.14em] text-white/40">AGENT WORKSPACE</p>
            </div>
          </div>
          <button type="button" aria-label="关闭导航" onClick={onClose} className="text-white/50 hover:text-white lg:hidden">
            <X className="h-4 w-4" />
          </button>
        </div>

        <nav className="scroll-thin flex-1 overflow-y-auto px-2.5 pb-4" aria-label="主导航">
          {GROUPS.map((group) => (
            <div key={group.label} className="mb-5 mt-3">
              <p className="mb-2 px-2 text-[10px] font-medium tracking-wider text-white/35">{group.label}</p>
              <div className="space-y-0.5">
                {group.items.map((item) => (
                  <NavLink
                    key={item.path}
                    to={item.path}
                    end={item.end}
                    onClick={onClose}
                    className={({ isActive }) =>
                      cn(
                        'relative flex items-center gap-2.5 rounded-field px-2.5 py-2 text-xs transition-colors duration-200',
                        isActive ? 'bg-sidebar-raised font-medium text-white' : 'text-white/60 hover:bg-white/5 hover:text-white',
                      )
                    }
                  >
                    {({ isActive }) => (
                      <>
                        {isActive && <span className="absolute left-0 top-1.5 h-5 w-0.5 rounded-r bg-brand-light" />}
                        <item.icon className={cn('h-3.5 w-3.5', isActive && 'text-brand-light')} aria-hidden />
                        <span>{item.label}</span>
                        {item.dot && hasAbnormalLogs && <span className="ml-auto h-1.5 w-1.5 rounded-full bg-orange-400" aria-label="存在异常日志" />}
                      </>
                    )}
                  </NavLink>
                ))}
              </div>
            </div>
          ))}
        </nav>

        <div className="border-t border-sidebar-line px-2.5 py-3">
          <NavLink
            to="/settings"
            onClick={onClose}
            className={({ isActive }) => cn('flex items-center gap-2.5 rounded-field px-2.5 py-2 text-xs transition-colors', isActive ? 'bg-sidebar-raised text-white' : 'text-white/60 hover:bg-white/5 hover:text-white')}
          >
            <Settings2 className="h-3.5 w-3.5" aria-hidden />系统设置
          </NavLink>
          <div className="mt-2 flex items-center gap-2 rounded-field bg-sidebar-raised px-2 py-2">
            <span className={cn('flex h-7 w-7 shrink-0 items-center justify-center rounded-[8px] bg-gradient-to-br text-2xs font-semibold text-white', AGENT_THEMES.violet)}>{workspaceName.charAt(0) || 'A'}</span>
            <div className="min-w-0 flex-1 leading-tight">
              <p className="truncate text-2xs text-white/90">{workspaceName}</p>
              <p className="text-[10px] text-white/40">本机工作区</p>
            </div>
            <button type="button" aria-label="退出登录" title="退出登录" onClick={handleLogout} className="text-white/45 transition-colors hover:text-white">
              <LogOut className="h-3.5 w-3.5" />
            </button>
          </div>
          <p className="mt-2 flex items-center justify-between px-2 text-[10px] text-white/30">
            <span>VERSION {version || '—'}</span>
            <ChevronRight className="h-3 w-3" aria-hidden />
          </p>
        </div>
      </aside>
    </>
  )
}
