import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { Bot, CornerDownLeft, Cpu, Search, X } from 'lucide-react'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { useWorkspace } from '../../store/workspace'
import { adminApi } from '../../lib/api'
import type { SearchResultLike } from '../../types/domain'
import { EmptyState } from '../ui'

export function GlobalSearch({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [keyword, setKeyword] = useState('')
  const debounced = useDebouncedValue(keyword, 300)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const maxResults = useWorkspace((state) => state.systemConfig.limits.searchMaxResults || 7)
  const [results, setResults] = useState<SearchResultLike[]>([])

  useEffect(() => {
    if (!debounced.trim()) {
      setResults([])
      return
    }
    let canceled = false
    adminApi.search(debounced)
      .then((items) => { if (!canceled) setResults(items) })
      .catch(() => { if (!canceled) setResults([]) })
    return () => { canceled = true }
  }, [debounced])

  useEffect(() => {
    if (!open) return
    const timer = window.setTimeout(() => inputRef.current?.focus(), 30)
    return () => window.clearTimeout(timer)
  }, [open])

  useEffect(() => {
    if (!open) setKeyword('')
  }, [open])

  if (!open) return null

  const go = (path: string) => {
    onClose()
    setKeyword('')
    navigate(path)
  }

  return createPortal(
    <div className="fixed inset-0 z-[60] flex items-start justify-center px-4 pt-[12vh]">
      <div className="absolute inset-0 bg-ink/40 animate-fade-in" onClick={onClose} />
      <div role="dialog" aria-modal="true" aria-label="全局搜索" className="relative z-10 w-full max-w-[560px] overflow-hidden rounded-card bg-white shadow-pop animate-slide-up">
        <div className="flex items-center gap-3 border-b border-line px-4">
          <Search className="h-4 w-4 shrink-0 text-ink-faint" aria-hidden />
          <input
            ref={inputRef}
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Escape') onClose()
              if (event.key === 'Enter' && results[0]) go(results[0].path)
            }}
            placeholder="搜索智能体、模型…"
            aria-label="搜索智能体与模型"
            className="h-12 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-faint"
          />
          <button type="button" aria-label="关闭搜索" onClick={onClose} className="rounded-field p-1 text-ink-faint hover:text-ink">
            <X className="h-3.5 w-3.5" />
          </button>
        </div>
        <div className="scroll-thin max-h-[340px] overflow-y-auto p-2">
          {!debounced.trim() && (
            <p className="px-3 py-6 text-center text-xs text-ink-faint">输入关键词搜索智能体名称或模型展示名称</p>
          )}
          {debounced.trim() && results.length === 0 && (
            <EmptyState compact title="没有找到匹配结果" description="换个关键词试试，搜索不区分大小写。" />
          )}
          {results.map((result) => (
            <button
              key={`${result.kind}-${result.id}`}
              type="button"
              onClick={() => go(result.path)}
              className="flex w-full items-center gap-3 rounded-field px-3 py-2.5 text-left transition-colors hover:bg-brand-faint"
            >
              <span className="flex h-8 w-8 items-center justify-center rounded-field bg-brand-soft text-brand">
                {result.kind === 'agent' ? <Bot className="h-4 w-4" /> : <Cpu className="h-4 w-4" />}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-ink">{result.name}</span>
                <span className="block truncate text-2xs text-ink-faint">{result.subtitle}</span>
              </span>
              <CornerDownLeft className="h-3.5 w-3.5 text-ink-faint" aria-hidden />
            </button>
          ))}
        </div>
        <div className="flex items-center justify-between border-t border-line bg-canvas/60 px-4 py-2 text-2xs text-ink-faint">
          <span>最多展示 {maxResults} 条匹配结果 · 搜索不区分大小写</span>
          <span><kbd className="rounded border border-line bg-white px-1.5 py-0.5">Esc</kbd> 关闭</span>
        </div>
      </div>
    </div>,
    document.body,
  )
}
