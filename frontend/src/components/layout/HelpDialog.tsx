import { Keyboard, Sparkles } from 'lucide-react'
import { Modal, Badge } from '../ui'

const SHORTCUTS = [
  { keys: '⌘ K / Ctrl + K', usage: '在任意页面唤起全局搜索，查找智能体与模型' },
  { keys: 'Esc', usage: '关闭当前弹窗、抽屉、搜索或通知面板' },
  { keys: 'Enter', usage: '在测试会话中发送消息；在搜索结果中打开首个匹配项' },
  { keys: 'Shift + Enter', usage: '在测试会话输入框中换行，而不发送消息' },
]

export function HelpDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} title="帮助与快捷键" description="让日常操作更顺手的一组键盘快捷方式" width="md">
      <div className="space-y-4">
        <div className="rounded-field border border-line bg-canvas/60 p-3">
          <div className="mb-2 flex items-center gap-2">
            <Sparkles className="h-3.5 w-3.5 text-brand" aria-hidden />
            <span className="text-xs font-medium text-ink">关于当前环境</span>
            <Badge tone="brand">已连接后端</Badge>
          </div>
          <p className="text-xs leading-relaxed text-ink-muted">
            本工作台连接管理面后端，资源、日志、系统配置与设置统一保存在服务端数据库；模型、MCP 与工具调用会真实请求所配置的上游服务。可在「系统设置」中查看并调整系统配置。
          </p>
        </div>
        <div>
          <div className="mb-2 flex items-center gap-2 text-xs font-medium text-ink">
            <Keyboard className="h-3.5 w-3.5 text-ink-muted" aria-hidden />快捷键
          </div>
          <div className="divide-y divide-line rounded-field border border-line">
            {SHORTCUTS.map((item) => (
              <div key={item.keys} className="flex items-start gap-3 px-3 py-2.5">
                <kbd className="shrink-0 rounded border border-line bg-canvas px-2 py-1 font-mono text-2xs text-ink">{item.keys}</kbd>
                <p className="text-xs leading-relaxed text-ink-muted">{item.usage}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </Modal>
  )
}
