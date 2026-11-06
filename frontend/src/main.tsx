import React from 'react'
import ReactDOM from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { router } from './App'
import { ToastHost } from './components/ui'
import { useAuth } from './store/auth'
import { fetchSession, replaceFromServer } from './lib/http'
import './index.css'

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement)

function AppTree() {
  return (
    <React.StrictMode>
      <RouterProvider router={router} />
      <ToastHost />
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

async function start(): Promise<void> {
  root.render(<Splash />)
  try {
    const session = await fetchSession()
    useAuth.getState().setAuthenticated(session.authenticated)
    if (session.authenticated) await replaceFromServer()
  } catch {
    useAuth.getState().setAuthenticated(false)
  }
  root.render(<AppTree />)
}

void start()
