import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertCircle, Sparkles } from 'lucide-react'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { usableChatModels } from '../../store/selectors'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Modal, Textarea, Select, toast } from '../../components/ui'

const EMPTY = { name: '', description: '', defaultModelId: '', systemPrompt: '' }

export function CreateAgentDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const models = useWorkspace((state) => state.models)
  const available = usableChatModels(models)
  const defaults = useWorkspace((state) => state.systemConfig.defaults.agent)
  const limits = useWorkspace((state) => state.systemConfig.limits)
  const [form, setForm] = useState(EMPTY)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (!open) return
    setForm({ ...EMPTY, defaultModelId: available[0]?.id ?? '' })
    setError('')
  }, [open])

  const update = (patch: Partial<typeof EMPTY>) => {
    setForm((current) => ({ ...current, ...patch }))
    setError('')
  }

  const submit = () => {
    if (submitting) return
    setSubmitting(true)
    api.agents
      .create(form)
      .then((agent) => {
        toast.success(`「${agent.name}」已创建，继续完成配置`)
        onClose()
        navigate(`/agents/${agent.id}`)
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] 创建智能体失败：', message)
        setError(message)
      })
      .finally(() => setSubmitting(false))
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="创建智能体"
      description="填写基础信息后即可进入配置页继续编排能力"
      width="md"
      dirty={Boolean(form.name || form.description || form.systemPrompt)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>取消</Button>
          <Button loading={submitting} disabled={!form.name.trim() || !form.defaultModelId} onClick={submit}>创建并配置</Button>
        </>
      }
    >
      <div className="space-y-4">
        <Field label="名称" htmlFor="agent-name" required hint="1～50 个字符，将用于列表、对话与发布文档">
          <Input id="agent-name" value={form.name} maxLength={limits.agentNameMaxLength || 50} placeholder="例如：研究助手" onChange={(event) => update({ name: event.target.value })} />
        </Field>
        <Field label="功能介绍" htmlFor="agent-desc" hint="留空时自动生成「一个全新的智能体，等待进一步配置」">
          <Textarea id="agent-desc" rows={2} value={form.description} maxLength={200} placeholder="描述这个智能体擅长解决什么问题" onChange={(event) => update({ description: event.target.value })} />
        </Field>
        <Field label="默认模型" htmlFor="agent-model" required>
          <Select
            id="agent-model"
            value={form.defaultModelId}
            onChange={(event) => update({ defaultModelId: event.target.value })}
            options={available.length
              ? available.map((model) => ({ value: model.id, label: `${model.name} · ${model.vendor}` }))
              : [{ value: '', label: '暂无可用模型' }]}
          />
          {available.length === 0 && (
            <p className="mt-2 flex items-start gap-1.5 text-xs text-warn">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              当前没有已启用的模型，请先在「模型管理」中启用
            </p>
          )}
        </Field>
        <Field label="System Prompt" htmlFor="agent-prompt" hint="留空时按「你是{名称}……」模板自动生成">
          <Textarea id="agent-prompt" rows={3} value={form.systemPrompt} maxLength={limits.promptMaxChars || 20000} placeholder={defaults.systemPrompt.replace('{name}', '智能体')} onChange={(event) => update({ systemPrompt: event.target.value })} />
        </Field>
        <div className="flex items-start gap-2 rounded-field bg-brand-faint px-3 py-2.5 text-2xs leading-relaxed text-brand">
          <Sparkles className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>创建后默认为草稿，变量、MCP、Skill 与内置工具列表为空，最大工具调用次数为 {defaults.advanced.maxToolCalls}，标准 API 与 AGUI 均关闭。</span>
        </div>
        {error && <p className="field-error" role="alert">{error}</p>}
      </div>
    </Modal>
  )
}
