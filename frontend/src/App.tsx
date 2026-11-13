import { Component, useEffect, type ErrorInfo, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, useLocation, useRouteError } from 'react-router-dom'
import { useAuth } from './store/auth'
import { fetchSession } from './lib/http'
import { AppShell } from './components/layout/AppShell'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { AgentsPage } from './pages/AgentsPage'
import { AgentDetailPage } from './pages/AgentDetailPage'
import { McpPage } from './pages/McpPage'
import { SkillsPage } from './pages/SkillsPage'
import { ToolsPage } from './pages/ToolsPage'
import { ModelsPage } from './pages/ModelsPage'
import { LogsPage } from './pages/LogsPage'
import { SettingsPage } from './pages/SettingsPage'

function FatalError({ message }: { message: string }) {
  return (
    <div style={{ display: 'flex', minHeight: '100vh', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, background: '#F7F8FA', color: '#374151', fontFamily: 'system-ui, sans-serif', fontSize: 14 }}>
      <p style={{ fontSize: 16, fontWeight: 600 }}>页面加载失败</p>
      <p style={{ color: '#6B7280', maxWidth: 480, textAlign: 'center' }}>{message}</p>
      <button
        type="button"
        style={{ padding: '8px 16px', borderRadius: 8, border: '1px solid #D1D5DB', background: '#fff', cursor: 'pointer' }}
        onClick={() => window.location.reload()}
      >重新加载</button>
    </div>
  )
}

/** 根错误边界：捕获 RouterProvider 之外以及组件树中的渲染异常，避免整站白屏。 */
export class RootErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[albedo] 渲染异常：', error, info.componentStack)
  }

  render() {
    if (this.state.error) return <FatalError message={this.state.error.message} />
    return this.props.children
  }
}

function RouteError() {
  const error = useRouteError()
  const message = error instanceof Error ? error.message : String(error ?? '未知错误')
  return <FatalError message={message} />
}

function RequireAuth({ children }: { children: ReactNode }) {
  const authenticated = useAuth((state) => state.authenticated)
  const location = useLocation()

  useEffect(() => {
    if (!authenticated) return
    let cancelled = false
    fetchSession()
      .then((session) => {
        if (!cancelled && !session.authenticated) useAuth.getState().setAuthenticated(false)
      })
      .catch((error: unknown) => {
        // 只有服务端明确返回未登录才跳转；网络抖动不应把页面踢回登录页。
        if (!cancelled) console.warn('[albedo] 会话复核失败，保持当前登录态：', error)
      })
    return () => { cancelled = true }
    // 路由切换时也复核一次会话，避免长时间停留在单页时会话过期不被发现。
  }, [authenticated, location.pathname])

  if (!authenticated) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <>{children}</>
}

function LoginRoute() {
  const authenticated = useAuth((state) => state.authenticated)
  return authenticated ? <Navigate to="/" replace /> : <LoginPage />
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginRoute />, errorElement: <RouteError /> },
  {
    path: '/',
    element: <RequireAuth><AppShell /></RequireAuth>,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: 'agents', element: <AgentsPage /> },
      { path: 'agents/:agentId', element: <AgentDetailPage /> },
      { path: 'resources/mcp', element: <McpPage /> },
      { path: 'resources/skills', element: <SkillsPage /> },
      { path: 'resources/tools', element: <ToolsPage /> },
      { path: 'resources/models', element: <ModelsPage /> },
      { path: 'logs', element: <LogsPage /> },
      { path: 'settings', element: <SettingsPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
