import { useEffect, type ReactNode } from 'react'
import { createBrowserRouter, Navigate, useLocation } from 'react-router-dom'
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
      .catch(() => {
        if (!cancelled) useAuth.getState().setAuthenticated(false)
      })
    return () => { cancelled = true }
  }, [authenticated])

  if (!authenticated) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <>{children}</>
}

function LoginRoute() {
  const authenticated = useAuth((state) => state.authenticated)
  return authenticated ? <Navigate to="/" replace /> : <LoginPage />
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginRoute /> },
  {
    path: '/',
    element: <RequireAuth><AppShell /></RequireAuth>,
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
