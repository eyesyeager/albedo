import { useState } from 'react'
import { AlertCircle, ArrowRight, KeyRound, Radio, RefreshCw, ShieldCheck, Sparkles } from 'lucide-react'
import type { Protocol, SampleLanguage } from '../../components/common/CodeSample'
import { endpointOf } from '../../components/common/CodeSample'
import { maskedAgentToken } from '../../lib/mask'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { publishChecks } from '../../store/selectors'
import { useWorkspace } from '../../store/workspace'
import { Badge, Button, CopyButton, Modal, Switch, toast } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'
import { QuickStartCard } from './QuickStartCard'
import { ApiDocCard } from './ApiDocCard'

function ProtocolCard({
  protocol, enabled, endpoint, onToggle, busy,
}: {
  protocol: Protocol
  enabled: boolean
  endpoint: string
  onToggle: (enabled: boolean) => void
  busy: boolean
}) {
  const isAgui = protocol === 'agui'
  return (
    <div className={`card p-4 transition-colors ${enabled ? 'border-brand/30' : ''}`}>
      <div className="flex items-start gap-3">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-field ${isAgui ? 'bg-brand-soft text-brand' : 'bg-blue-50 text-blue-600'}`}>
          {isAgui ? <Sparkles className="h-4 w-4" /> : <Radio className="h-4 w-4" />}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-ink">{isAgui ? 'AGUI 协议接口' : '标准对话 API'}</h3>
            {isAgui && <Badge tone="brand">推荐</Badge>}
          </div>
          <p className="mt-1 text-2xs leading-relaxed text-ink-muted">
            {isAgui
              ? '面向 Agent 前端的结构化 SSE 事件流，实时呈现流式文本、工具调用与共享状态。'
              : '面向后端服务与脚本的简化 HTTP 对话接口，只返回最终回答与运行摘要。'}
          </p>
        </div>
        <Switch label={isAgui ? '发布为 AGUI 接口' : '发布为标准对话 API'} checked={enabled} disabled={busy} onChange={onToggle} />
      </div>
      {enabled && (
        <div className="mt-3 space-y-2 border-t border-line pt-3 animate-fade-in">
          <div className="flex items-start gap-2 rounded-field bg-canvas/70 px-3 py-2">
            <span className="shrink-0 font-mono text-2xs font-semibold text-brand">POST</span>
            <code className="min-w-0 flex-1 break-all font-mono text-2xs text-ink">{endpoint}</code>
            <CopyButton value={endpoint} label="复制" message="Endpoint 已复制" />
          </div>
          {isAgui && (
            <div className="flex flex-wrap gap-1.5">
              {['TEXT MESSAGE', 'TOOL CALL', 'STATE DELTA'].map((tag) => <Badge key={tag} tone="brand">{tag}</Badge>)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export function PublishTab({ agentId, dirty }: { agentId: string; dirty: boolean }) {
  const data = useWorkspace()
  const agent = data.agents.find((item) => item.id === agentId)
  const [protocol, setProtocol] = useState<Protocol>('api')
  const [language, setLanguage] = useState<SampleLanguage>('curl')
  const [busy, setBusy] = useState(false)
  const [oneTimeKey, setOneTimeKey] = useState<string | null>(null)
  const domain = data.settings.domain
  const issues = agent ? publishChecks(data, agent) : []
  const activeEnabled = agent ? (protocol === 'api' ? agent.apiEnabled : agent.aguiEnabled) : false

  if (!agent) return null

  const toggle = (target: Protocol, enabled: boolean) => {
    if (enabled && dirty) {
      toast.error('存在未保存的配置，请先点击「保存配置」后再发布')
      return
    }
    setBusy(true)
    api.agents
      .setProtocol(agentId, target, enabled)
      .then(() => toast.success(`${target === 'api' ? '标准对话 API' : 'AGUI 协议接口'}已${enabled ? '开启' : '关闭'}`))
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 发布开关失败：', message)
        toast.error(message)
      })
      .finally(() => setBusy(false))
  }

  const resetCredential = () => {
    setBusy(true)
    api.agents
      .resetCredential(agentId)
      .then((full) => {
        setOneTimeKey(full)
        toast.success('访问凭证已重置，旧密钥立即失效')
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setBusy(false))
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_1.1fr]">
        <div className="space-y-4">
          <ProtocolCard
            protocol="api"
            enabled={agent.apiEnabled}
            endpoint={endpointOf('api', agentId, domain)}
            onToggle={(enabled) => toggle('api', enabled)}
            busy={busy}
          />
          <ProtocolCard
            protocol="agui"
            enabled={agent.aguiEnabled}
            endpoint={endpointOf('agui', agentId, domain)}
            onToggle={(enabled) => toggle('agui', enabled)}
            busy={busy}
          />

          <SectionCard title="访问凭证" description="每个 Agent 使用独立 Bearer Token，与管理访问 Key 隔离">
            <div className="flex flex-wrap items-center gap-2">
              <KeyRound className="h-3.5 w-3.5 text-brand" aria-hidden />
              <code className="min-w-0 flex-1 truncate font-mono text-xs text-ink">{maskedAgentToken(agent.credentialSuffix)}</code>
              <CopyButton value={agent.credentialSuffix} label="复制后缀" message="Key 后缀已复制" />
              <Button variant="outline" size="sm" icon={<RefreshCw className="h-3 w-3" />} disabled={busy} onClick={resetCredential}>重置密钥</Button>
            </div>
            <p className="mt-2.5 flex items-start gap-1.5 text-2xs leading-relaxed text-ink-faint">
              <ShieldCheck className="mt-0.5 h-3 w-3 shrink-0 text-ok" />
              完整密钥仅在重置时展示一次，请妥善保存；示例代码从环境变量读取密钥，日志不记录明文。
            </p>
          </SectionCard>

          {(issues.length > 0 || dirty) && (
            <div className="rounded-card border border-warn/30 bg-warn/5 p-4">
              <p className="flex items-center gap-2 text-xs font-medium text-ink">
                <AlertCircle className="h-3.5 w-3.5 text-warn" />发布前检查
              </p>
              <ul className="mt-2 space-y-1 text-2xs leading-relaxed text-ink-muted">
                {dirty && <li>· 存在未保存的配置修改，保存后方可开启发布接口</li>}
                {issues.map((issue) => <li key={issue}>· {issue}</li>)}
                {issues.length === 0 && <li>· 建议在开启前完成至少一次成功的测试运行</li>}
              </ul>
            </div>
          )}
        </div>

        <QuickStartCard
          protocol={protocol}
          onProtocolChange={setProtocol}
          language={language}
          onLanguageChange={setLanguage}
          agentId={agentId}
          domain={domain}
        />
      </div>

      <ApiDocCard protocol={protocol} agentId={agentId} domain={domain} enabled={activeEnabled} />

      <Modal
        open={oneTimeKey !== null}
        onClose={() => setOneTimeKey(null)}
        title="新的 Agent 访问凭证"
        description="完整密钥仅展示这一次，关闭后不可再次查看"
        width="md"
        footer={<Button onClick={() => setOneTimeKey(null)} icon={<ArrowRight className="h-3.5 w-3.5" />}>我已保存</Button>}
      >
        <div className="space-y-3">
          <code className="block break-all rounded-field bg-canvas px-3 py-3 font-mono text-xs text-ink">{oneTimeKey}</code>
          <CopyButton value={oneTimeKey ?? ''} label="复制完整密钥" message="完整密钥已复制，请立即保存" />
          <p className="text-2xs leading-relaxed text-ink-muted">旧密钥已立即失效。请同步更新所有下游应用与脚本中的 Bearer Token。</p>
        </div>
      </Modal>
    </div>
  )
}
