import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { router, RootErrorBoundary } from './App'
import { ToastHost } from './components/ui'
import { useAuth } from './store/auth'
import { asError } from './lib/errors'
import { fetchSession, replaceFromServer } from './lib/http'
import './index.css'

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement)

function AppTree() {
  return (
    <React.StrictMode>
      <RootErrorBoundary>
        <RouterProvider router={router} />
        <ToastHost />
      </RootErrorBoundary>
    </React.StrictMode>
  )
}

function Splash() {
  return (
    <div style={{ display: 'flex', minHeight: '100vh', alignItems: 'center', justifyContent: 'center', background: '#F7F8FA', color: '#6B7280', fontFamily: 'system-ui, sans-serif', fontSize: 14 }}>
      正在连接工作区…
    </div>
  )
}

function LoadError({ message }: { message: string }) {
  return (
    <div style={{ display: 'flex', minHeight: '100vh', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, background: '#F7F8FA', color: '#374151', fontFamily: 'system-ui, sans-serif', fontSize: 14 }}>
      <p style={{ fontSize: 16, fontWeight: 600 }}>工作区数据加载失败</p>
      <p style={{ color: '#6B7280' }}>{message}</p>
      <button
        type="button"
        style={{ padding: '8px 16px', borderRadius: 8, border: '1px solid #D1D5DB', background: '#fff', cursor: 'pointer' }}
        onClick={() => window.location.reload()}
      >重新加载</button>
    </div>
  )
}

async function start(): Promise<void> {
  root.render(<Splash />)
  try {
    const session = await fetchSession()
    useAuth.getState().setAuthenticated(session.authenticated)
  } catch (error) {
    console.error('[albedo] 会话校验失败：', error)
    useAuth.getState().setAuthenticated(false)
  }
  if (useAuth.getState().authenticated) {
    try {
      await replaceFromServer()
    } catch (error) {
      // 快照失败时不能进入一个「全空但看起来正常」的工作台。
      console.error('[albedo] 工作区数据加载失败：', error)
      root.render(<LoadError message={asError(error).message} />)
      return
    }
  }
  root.render(<AppTree />)
}

void start()
