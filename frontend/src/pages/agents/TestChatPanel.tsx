import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { CheckCircle2, Send, Trash2, Wrench } from 'lucide-react'
import type { Agent, ChatMessage } from '../../types/domain'
import { cn } from '../../lib/cn'
import { formatTime } from '../../lib/format'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { useWorkspace } from '../../store/workspace'
import { EmptyState, IconButton, Select, toast } from '../../components/ui'

const EMPTY_MESSAGES: ChatMessage[] = []

function ToolMessage({ message }: { message: ChatMessage }) {
  const call = message.toolCall
  if (!call) return null
  return (
    <div className="rounded-field border border-brand/20 bg-brand-faint/60 p-3">
      <div className="flex items-center gap-2 text-2xs font-medium text-brand">
        <Wrench className="h-3 w-3" aria-hidden />{call.name}
        <span className="ml-auto inline-flex items-center gap-1 text-ok">
          <CheckCircle2 className="h-3 w-3" />{call.status === 'success' ? '执行成功' : '执行失败'}
        </span>
      </div>
      <pre className="mt-2 overflow-auto rounded bg-white/80 p-2 font-mono text-[11px] text-ink-muted">{call.args}</pre>
      <p className="mt-2 text-2xs text-ink-muted">{call.result}</p>
    </div>
  )
}

export function TestChatPanel({ agentId, draft, focusSignal, testModelId, onTestModelChange }: { agentId: string; draft: Agent; focusSignal: number; testModelId: string; onTestModelChange: (id: string) => void }) {
  const chatSessions = useWorkspace((state) => state.chatSessions)
  const models = useWorkspace((state) => state.models)
  const messages = chatSessions[agentId] ?? EMPTY_MESSAGES
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  const testOptions = models.filter((model) => model.enabled && model.type === 'chat' && model.connection === 'normal')
    .map((model) => ({ value: model.id, label: model.name }))

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages.length, sending])

  useEffect(() => {
    if (focusSignal > 0) inputRef.current?.focus()
  }, [focusSignal])

  const send = () => {
    const content = input.trim()
    if (!content || sending) return
    setInput('')
    setSending(true)
    api.chat
      .send(agentId, content, testModelId)
      .then((result) => toast.info(`测试运行完成 · ${result.runId}`))
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 测试会话失败：', message)
        toast.error(message)
      })
      .finally(() => setSending(false))
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      send()
    }
  }

  const clear = () => {
    void api.chat.clear(agentId)
    toast.success('测试对话已清空，历史日志仍保留')
  }

  return (
    <div className="card flex h-[620px] flex-col xl:h-[calc(100vh-220px)] xl:min-h-[520px]">
      <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-3">
        <div className="min-w-0">
          <p className="truncate text-xs font-semibold text-ink">测试会话 · 配置实时生效</p>
          <p className="mt-0.5 flex items-center gap-1 text-2xs text-ok"><span className="h-1.5 w-1.5 rounded-full bg-ok" />草稿配置无需发布即可测试</p>
        </div>
        <div className="flex items-center gap-1">
          <Select
            aria-label="测试会话模型"
            value={testModelId}
            onChange={(event) => onTestModelChange(event.target.value)}
            options={testOptions.length ? testOptions : [{ value: '', label: '暂无模型' }]}
            className="h-7 w-[110px] py-1 text-2xs"
          />
          <IconButton label="清空对话" onClick={clear}><Trash2 className="h-3.5 w-3.5" /></IconButton>
        </div>
      </div>

      <div ref={scrollRef} className="scroll-thin flex-1 space-y-3 overflow-y-auto px-3 py-4">
        {messages.length === 0 && !sending && (
          <EmptyState compact title="开始一组测试对话" description="发送一条消息，验证当前提示词、模型与挂载能力的组合效果。" />
        )}
        {messages.map((message) => (
          <div key={message.id} className={cn('flex flex-col gap-1', message.role === 'user' ? 'items-end' : 'items-start')}>
            {message.role === 'tool' ? (
              <div className="w-full"><ToolMessage message={message} /></div>
            ) : (
              <div className={cn(
                'max-w-[92%] rounded-card px-3 py-2.5 text-xs leading-relaxed',
                message.role === 'user' ? 'bg-brand text-white' : 'border border-line bg-white text-ink shadow-card',
              )}>
                <p className="whitespace-pre-wrap">{message.content}</p>
              </div>
            )}
            <span className="px-1 text-[10px] tabular-nums text-ink-faint">{formatTime(message.timestamp)}</span>
          </div>
        ))}
        {sending && (
          <div className="flex items-center gap-1.5 rounded-card border border-line bg-white px-3 py-3 shadow-card" aria-label="正在生成回复">
            {[0, 150, 300].map((delay) => (
              <span key={delay} className="h-1.5 w-1.5 rounded-full bg-brand animate-breathe" style={{ animationDelay: `${delay}ms` }} />
            ))}
            <span className="ml-1 text-2xs text-ink-faint">正在思考…</span>
          </div>
        )}
      </div>

      <div className="border-t border-line p-3">
        <div className="rounded-field border border-line bg-white p-2 transition-colors focus-within:border-brand focus-within:ring-2 focus-within:ring-brand/10">
          <textarea
            ref={inputRef}
            aria-label="测试消息输入框"
            rows={2}
            value={input}
            disabled={sending}
            placeholder="输入消息测试智能体…"
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={onKeyDown}
            className="w-full resize-none bg-transparent text-xs leading-relaxed text-ink outline-none placeholder:text-ink-faint disabled:opacity-60"
          />
          <div className="flex items-center justify-between gap-2 pt-1">
            <div className="flex items-center gap-1">
              <span className="text-[10px] text-ink-faint">Enter 发送 · Shift + Enter 换行</span>
            </div>
            <button
              type="button"
              aria-label="发送消息"
              disabled={!input.trim() || sending}
              onClick={send}
              className="flex h-7 w-7 items-center justify-center rounded-field bg-brand text-white transition-colors hover:bg-brand-hover disabled:opacity-40"
            >
              <Send className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
