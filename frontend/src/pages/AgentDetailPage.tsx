import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, Bot, Play, Save, Trash2 } from 'lucide-react'
import type { Agent, LogEntry } from '../types/domain'
import { formatRelative } from '../lib/format'
import { api } from '../lib/api'
import { asError } from '../lib/errors'
import { useUnsavedGuard } from '../hooks/useUnsavedGuard'
import { agentSessions, isAgentPublished } from '../store/selectors'
import { useWorkspace } from '../store/workspace'
import { Button, ConfirmDialog, EmptyState, Tabs, toast } from '../components/ui'
import { GradientAvatar } from '../components/common/GradientAvatar'
import { PublishBadge } from '../components/common/StatusBadge'
import { ConfigNav, type ConfigSection } from './agents/ConfigNav'
import { BasicInfoPanel } from './agents/BasicInfoPanel'
import { PromptPanel } from './agents/PromptPanel'
import { ModelSettingsPanel } from './agents/ModelSettingsPanel'
import { CapabilitiesPanel } from './agents/CapabilitiesPanel'
import { AdvancedPanel } from './agents/AdvancedPanel'
import { TestChatPanel } from './agents/TestChatPanel'
import { HistoryTab } from './agents/HistoryTab'
import { PublishTab } from './agents/PublishTab'

type DetailTab = 'edit' | 'history' | 'publish'

export function AgentDetailPage() {
  const { agentId = '' } = useParams()
  const navigate = useNavigate()
  const data = useWorkspace()
  const agent = data.agents.find((item) => item.id === agentId)
  const [draft, setDraft] = useState<Agent | null>(agent ? { ...agent } : null)
  const [dirty, setDirty] = useState(false)
  const [tab, setTab] = useState<DetailTab>('edit')
  const [section, setSection] = useState<ConfigSection>('basic')
  const [focusSignal, setFocusSignal] = useState(0)
  const [saving, setSaving] = useState(false)
  const [testModelId, setTestModelId] = useState('')
  const [history, setHistory] = useState<LogEntry[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [historyError, setHistoryError] = useState('')
  const [historyVersion, setHistoryVersion] = useState(0)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleted, setDeleted] = useState(false)
  const blocker = useUnsavedGuard(dirty)

  useEffect(() => {
    let canceled = false
    // 先展示快照中的同 Agent 会话，再由服务端历史覆盖，避免只统计最近 50 条日志。
    setHistory(agentSessions({ logs: useWorkspace.getState().logs }, agentId))
    setHistoryLoading(true)
    setHistoryError('')
    api.agents
      .history(agentId)
      .then((result) => {
        if (!canceled) setHistory(result.items)
      })
      .catch((reason: unknown) => {
        if (canceled) return
        setHistoryError(asError(reason).message)
      })
      .finally(() => {
        if (!canceled) setHistoryLoading(false)
      })
    return () => {
      canceled = true
    }
  }, [agentId, historyVersion, data.logs.length])

  useEffect(() => {
    const current = useWorkspace.getState().agents.find((item) => item.id === agentId)
    setDraft(current ? { ...current } : null)
    setTestModelId(current?.testModelId ?? current?.defaultModelId ?? '')
    setDirty(false)
    setTab('edit')
    setSection('basic')
  }, [agentId])

  useEffect(() => {
    // 删除成功后在 dirty 复位的那次渲染再跳转，避免触发未保存修改拦截。
    if (deleted && !dirty) navigate('/agents', { replace: true })
  }, [deleted, dirty, navigate])

  const change = (patch: Partial<Agent>) => {
    setDraft((current) => (current ? { ...current, ...patch } : current))
    setDirty(true)
  }

  const save = () => {
    if (!draft || saving) return
    const { apiEnabled, aguiEnabled, credentialSuffix, sessionCount, testModelId: _testModelId, ...config } = draft
    setSaving(true)
    api.agents
      .update(agentId, config)
      .then(() => {
        setDirty(false)
        toast.success('配置已保存，更新时间：刚刚')
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 保存配置失败：', message)
        toast.error(message)
      })
      .finally(() => setSaving(false))
  }

  const run = () => {
    setTab('edit')
    setSection('basic')
    setFocusSignal((value) => value + 1)
    toast.info('已进入测试会话，可直接提问验证配置')
  }

  const remove = () => {
    if (deleting) return
    setDeleting(true)
    api.agents
      .remove(agentId)
      .then(() => {
        setDeleteOpen(false)
        toast.success('智能体已删除，挂载配置与凭证同步清理')
        setDirty(false)
        setDeleted(true)
      })
      .catch((reason: unknown) => toast.error(asError(reason).message))
      .finally(() => setDeleting(false))
  }

  if (!agent || !draft) {
    return (
      <div className="card mx-auto max-w-[720px]">
        <EmptyState
          icon={<Bot className="h-5 w-5" />}
          title="未找到该智能体"
          description="它可能已被删除，或链接中的 Agent ID 不正确。"
          action={<Button variant="outline" icon={<ArrowLeft className="h-3.5 w-3.5" />} onClick={() => navigate('/agents')}>返回智能体列表</Button>}
        />
      </div>
    )
  }

  const historyCount = history.length
  const panelProps = { draft, onChange: change }

  return (
    <div className="mx-auto max-w-[1600px]">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <button type="button" aria-label="返回智能体列表" onClick={() => navigate('/agents')} className="rounded-field p-1.5 text-ink-muted transition-colors hover:bg-white hover:text-ink">
            <ArrowLeft className="h-4 w-4" />
          </button>
          <GradientAvatar theme={agent.theme} name={agent.name} />
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <h1 className="truncate text-md font-semibold text-ink">{draft.name}</h1>
              <PublishBadge published={isAgentPublished(agent)} />
            </div>
            <p className="mt-0.5 truncate text-2xs text-ink-faint">更新于 {formatRelative(agent.updatedAt)} · ID: {agent.id}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" icon={<Play className="h-3.5 w-3.5" />} onClick={run}>运行</Button>
          <Button icon={<Save className="h-3.5 w-3.5" />} loading={saving} onClick={save}>保存配置</Button>
          <Button
            variant="ghost"
            icon={<Trash2 className="h-3.5 w-3.5" />}
            aria-label="删除智能体"
            className="text-ink-faint hover:bg-danger/10 hover:text-danger"
            onClick={() => setDeleteOpen(true)}
          >
            删除
          </Button>
        </div>
      </div>

      <Tabs
        className="mb-4"
        value={tab}
        onChange={(value) => setTab(value as DetailTab)}
        items={[
          { key: 'edit', label: '编排与调试' },
          { key: 'history', label: '对话历史', count: historyCount },
          { key: 'publish', label: '发布' },
        ]}
      />

      {tab === 'edit' && (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-[150px_minmax(0,1fr)_360px]">
          <ConfigNav value={section} onChange={setSection} />
          <div className="min-w-0">
            {section === 'basic' && <BasicInfoPanel {...panelProps} />}
            {section === 'prompt' && <PromptPanel {...panelProps} />}
            {section === 'model' && <ModelSettingsPanel {...panelProps} />}
            {section === 'capabilities' && <CapabilitiesPanel {...panelProps} />}
            {section === 'advanced' && <AdvancedPanel {...panelProps} />}
          </div>
          <TestChatPanel agentId={agentId} draft={draft} focusSignal={focusSignal} testModelId={testModelId} onTestModelChange={setTestModelId} />
        </div>
      )}

      {tab === 'history' && (
        <HistoryTab
          items={history}
          loading={historyLoading}
          error={historyError}
          onRefresh={() => setHistoryVersion((value) => value + 1)}
        />
      )}
      {tab === 'publish' && <PublishTab agentId={agentId} dirty={dirty} />}

      <ConfirmDialog
        open={blocker.state === 'blocked'}
        onClose={() => blocker.reset?.()}
        onConfirm={() => blocker.proceed?.()}
        title="放弃未保存的修改？"
        description="当前智能体配置有尚未保存的修改，离开页面后这些修改会丢失。"
        confirmText="放弃并离开"
        cancelText="继续编辑"
        danger
        impacts={['未保存的名称、提示词、模型参数、能力挂载与高级设置都会被丢弃']}
      />

      <ConfirmDialog
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        onConfirm={remove}
        title={`删除智能体 · ${agent.name}`}
        description="删除后该智能体及其测试会话、能力挂载与访问凭证都将不可恢复，操作会写入系统操作日志。"
        confirmText="删除"
        danger
        loading={deleting}
        impacts={[
          isAgentPublished(agent) ? '已开启的 API / AGUI 对外接口将立即不可用' : '该智能体将不再出现在列表中',
          '已挂载的 MCP、Skill、工具与模型配置会一并解除',
          '测试会话、访问凭证与对话历史会被清除',
        ]}
      />
    </div>
  )
}
