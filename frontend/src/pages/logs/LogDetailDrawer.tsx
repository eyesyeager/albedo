import { useEffect, useState } from 'react'
import { AlertTriangle, ArrowRight, Clock3 } from 'lucide-react'
import { cn } from '../../lib/cn'
import { formatDateTime, formatDuration, formatNumber } from '../../lib/format'
import { adminApi } from '../../lib/api'
import { asError } from '../../lib/errors'
import { executionChain, LOG_SOURCE_LABEL, LOG_TYPE_LABEL } from '../../store/selectors'
import { useWorkspace } from '../../store/workspace'
import type { LogEntry } from '../../types/domain'
import { Badge, Button, CodeBlock, Drawer } from '../../components/ui'
import { LogTypeIcon, RunStatusBadge } from '../../components/common/StatusBadge'

function SummaryCell({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-field border border-line bg-canvas/40 p-2.5">
      <p className="text-2xs text-ink-faint">{label}</p>
      <p className={cn('mt-1 truncate text-xs font-medium text-ink', mono && 'font-mono text-2xs')} title={value}>{value}</p>
    </div>
  )
}

export function LogDetailDrawer({ logId, onClose }: { logId: string | null; onClose: () => void }) {
  const data = useWorkspace()
  const local = logId ? data.logs.find((item) => item.logId === logId) : undefined
  const [remote, setRemote] = useState<(LogEntry & { chain: LogEntry[] }) | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    if (!logId) {
      setRemote(null)
      setError(null)
      setLoading(false)
      return
    }
    let canceled = false
    setRemote(null)
    setError(null)
    setLoading(true)
    adminApi.logs
      .detail(logId)
      .then((value) => {
        if (!canceled) setRemote(value)
      })
      .catch((reason: unknown) => {
        if (!canceled) setError(asError(reason).message)
      })
      .finally(() => {
        if (!canceled) setLoading(false)
      })
    return () => {
      canceled = true
    }
  }, [logId, reloadKey])

  // 列表来自服务端分页，详情必须以远端结果为准；bootstrap 快照只作即时回退。
  const log = remote ?? local
  if (!logId) return null

  if (!log) {
    return (
      <Drawer open onClose={onClose} title="日志详情" subtitle={logId} width="xl">
        <div className="space-y-3">
          <p className="text-sm text-ink-muted">
            {loading ? '正在加载日志详情…' : error ? `加载失败：${error}` : '未找到该日志记录'}
          </p>
          {!loading && (
            <Button variant="outline" onClick={() => setReloadKey((value) => value + 1)}>重试</Button>
          )}
        </div>
      </Drawer>
    )
  }

  const chain = remote?.chain?.length ? remote.chain : executionChain(data.logs, log.runId)
  const firstTime = chain.length ? new Date(chain[0].timestamp).getTime() : new Date(log.timestamp).getTime()
  const agent = data.agents.find((item) => item.id === log.agentId)
  const isFailure = log.status === 'failed' || log.status === 'timeout'

  return (
    <Drawer open onClose={onClose} title={log.name} subtitle={`${LOG_TYPE_LABEL[log.type]} · ${formatDateTime(log.timestamp)}`} width="xl">
      <div className="space-y-5">
        <div className="flex flex-wrap items-center gap-2">
          <RunStatusBadge status={log.status} />
          <Badge tone="brand">{LOG_SOURCE_LABEL[log.source]}</Badge>
          <Badge tone="neutral"><Clock3 className="h-3 w-3" />耗时 {formatDuration(log.durationMs)}</Badge>
        </div>
        <p className="text-xs leading-relaxed text-ink-muted">{log.summary}</p>

        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
          <SummaryCell label="Run ID" value={log.runId ?? '—'} mono />
          <SummaryCell label="Thread ID" value={log.threadId ?? '—'} mono />
          <SummaryCell label="智能体" value={agent?.name ?? log.agentId ?? '—'} />
          <SummaryCell label="Token 用量" value={log.tokenUsage ? formatNumber(log.tokenUsage.totalTokens) : '—'} />
        </div>

        <div>
          <p className="mb-2 text-xs font-medium text-ink">执行链路</p>
          <p className="mb-3 text-2xs text-ink-faint">同 runId 的全部日志按真实时间戳排序，偏移以链路首条记录为基准</p>
          <div className="relative space-y-0 border-l border-line pl-5">
            {(chain.length ? chain : [log]).map((node) => {
              const offset = (new Date(node.timestamp).getTime() - firstTime) / 1000
              return (
                <div key={node.logId} className="relative pb-4 last:pb-0">
                  <span className={cn(
                    'absolute -left-[26px] top-1.5 h-2.5 w-2.5 rounded-full ring-4 ring-white',
                    node.status === 'success' ? 'bg-ok' : node.status === 'failed' ? 'bg-danger' : node.status === 'timeout' ? 'bg-warn' : 'bg-brand',
                  )} />
                  <div className="flex items-start gap-2.5 rounded-field border border-line p-2.5">
                    <LogTypeIcon type={node.type} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-xs font-medium text-ink">{node.name}</p>
                      <p className="mt-0.5 line-clamp-2 text-2xs leading-relaxed text-ink-muted">{node.summary}</p>
                    </div>
                    <span className="shrink-0 font-mono text-2xs tabular-nums text-ink-faint">+{offset.toFixed(2)}s</span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
          <div>
            <p className="mb-2 text-xs font-medium text-ink">请求参数 / 输入</p>
            <CodeBlock code={log.inputSummary ?? '// 该记录未采集输入内容'} language="json" maxHeight="220px" />
          </div>
          <div className={cn(isFailure && 'rounded-field ring-1 ring-danger/30')}>
            <p className="mb-2 text-xs font-medium text-ink">响应结果 / 输出</p>
            <CodeBlock code={log.outputSummary ?? '// 该记录未采集输出内容'} language={isFailure ? 'error' : 'json'} maxHeight="220px" />
          </div>
        </div>

        {isFailure && (
          <div className="rounded-card border border-danger/30 bg-danger/5 p-4">
            <p className="flex items-center gap-2 text-xs font-semibold text-danger"><AlertTriangle className="h-3.5 w-3.5" />调用异常</p>
            <p className="mt-2 text-xs leading-relaxed text-ink">{log.errorMessage ?? log.summary}</p>
            <p className="mt-1.5 font-mono text-2xs text-danger">{log.errorCode ?? 'unknown_error'}</p>
            <p className="mt-2 flex items-center gap-1.5 text-2xs text-ink-muted">
              <ArrowRight className="h-3 w-3" />排查建议：请检查上游服务与配额配置，并在调整后通过测试会话复现。
            </p>
          </div>
        )}

        <p className="text-2xs leading-relaxed text-ink-faint">
          输入与输出内容已按脱敏规则输出，完整内容可下载；密钥、鉴权 Header 与敏感字段不会出现在日志与导出文件中。
        </p>
      </div>
    </Drawer>
  )
}
