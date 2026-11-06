import { useNavigate } from 'react-router-dom'
import { AlertCircle, CheckCircle2, Info } from 'lucide-react'
import { formatRelative } from '../../lib/format'
import { useWorkspace } from '../../store/workspace'
import { api } from '../../lib/api'
import { Badge, Button, toast } from '../ui'

const ICONS = {
  error: <AlertCircle className="h-4 w-4 text-danger" />,
  info: <Info className="h-4 w-4 text-brand" />,
  success: <CheckCircle2 className="h-4 w-4 text-ok" />,
}

export function NotificationPanel({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const notifications = useWorkspace((state) => state.notifications)
  const unread = notifications.filter((item) => !item.read).length

  const markAll = () => {
    void api.notifications.readAll()
    toast.success('已全部标记为已读')
  }

  return (
    <div className="w-[340px] max-w-[calc(100vw-32px)]">
      <div className="flex items-center justify-between border-b border-line px-4 py-3">
        <div className="flex items-center gap-2">
          <h3 className="text-sm font-semibold text-ink">通知</h3>
          {unread > 0 && <Badge tone="danger">{unread} 条未读</Badge>}
        </div>
        <Button variant="ghost" size="sm" disabled={unread === 0} onClick={markAll}>
          {unread === 0 ? '已全部读完' : '全部已读'}
        </Button>
      </div>
      <div className="scroll-thin max-h-[320px] overflow-y-auto">
        {notifications.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => {
              onClose()
              navigate(item.targetPath)
            }}
            className="flex w-full gap-3 border-b border-line/70 px-4 py-3 text-left transition-colors hover:bg-brand-faint"
          >
            <span className="mt-0.5 shrink-0">{ICONS[item.kind]}</span>
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2">
                <span className="truncate text-xs font-medium text-ink">{item.title}</span>
                {!item.read && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-brand" aria-label="未读" />}
              </span>
              <span className="mt-1 block text-2xs leading-relaxed text-ink-muted">{item.summary}</span>
              <span className="mt-1 block text-2xs text-ink-faint">{formatRelative(item.timestamp)}</span>
            </span>
          </button>
        ))}
      </div>
      <button
        type="button"
        onClick={() => { onClose(); navigate('/logs') }}
        className="w-full rounded-b-card py-2.5 text-center text-xs font-medium text-brand transition-colors hover:bg-brand-faint"
      >
        查看全部调用日志
      </button>
    </div>
  )
}
