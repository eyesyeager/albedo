import { useState } from 'react'
import { Plus, Sparkles, Trash2 } from 'lucide-react'
import { isValidVariableName } from '../../lib/validate'
import { useWorkspace } from '../../store/workspace'
import { Button, Field, Input, Textarea, Badge, toast } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'
import type { PanelProps } from './BasicInfoPanel'


export function PromptPanel({ draft, onChange }: PanelProps) {
  const limits = useWorkspace((state) => state.systemConfig.limits)
  const maxPrompt = limits.promptMaxChars || 20000
  const maxVariables = limits.promptMaxVariables || 50
  const [variableInput, setVariableInput] = useState('')
  const [variableError, setVariableError] = useState('')
  const length = draft.systemPrompt.length
  const unresolved = draft.variables.filter((name) => !draft.systemPrompt.includes(`{{${name}}}`))

  const addVariable = () => {
    const name = variableInput.trim()
    if (!isValidVariableName(name)) {
      setVariableError('变量名仅支持字母、数字与下划线，且不能以数字开头或包含空格')
      return
    }
    if (draft.variables.includes(name)) {
      setVariableError('重复变量不可再次添加')
      return
    }
    if (draft.variables.length >= maxVariables) {
      setVariableError(`自定义变量最多 ${maxVariables} 个`)
      return
    }
    onChange({ variables: [...draft.variables, name] })
    setVariableInput('')
    setVariableError('')
    toast.success(`已添加变量 {{${name}}}`)
  }

  const removeVariable = (name: string) => {
    onChange({ variables: draft.variables.filter((item) => item !== name) })
    if (draft.systemPrompt.includes(`{{${name}}}`)) {
      toast.info(`已移除变量 {{${name}}}，Prompt 中仍存在引用，请手动清理`)
    }
  }

  const generate = () => {
    const abilities = draft.mounts.mcpIds.length + draft.mounts.skillIds.length + draft.mounts.toolIds.length
    const next = [
      `你是「${draft.name}」，${draft.description || '一个专注于解决具体问题的智能体'}。`,
      '',
      '工作方式：',
      '1. 先理解用户目标与约束，必要时澄清关键信息。',
      '2. 结合已挂载的能力与上下文给出可执行的结论。',
      abilities > 0 ? '3. 需要外部信息或计算时，优先调用已授权的工具，并说明调用依据。' : '3. 输出结构清晰、可复用的结果。',
      `4. 回答使用简体中文，当前时间：{{currentTime}}。`,
      draft.variables.length ? `\n业务上下文变量：${draft.variables.map((name) => `{{${name}}}`).join('、')}。` : '',
    ].filter(Boolean).join('\n')
    onChange({ systemPrompt: next })
    toast.info('已生成 Prompt 草稿，确认后请点击「保存配置」')
  }

  return (
    <div className="space-y-4">
      <SectionCard
        title="System Prompt"
        description="智能体的核心系统指令，保存前不可为空"
        actions={<Button variant="secondary" size="sm" icon={<Sparkles className="h-3.5 w-3.5" />} onClick={generate}>AI 辅助生成</Button>}
        bodyClassName="p-5"
      >
        <Textarea
          aria-label="System Prompt"
          rows={12}
          value={draft.systemPrompt}
          maxLength={maxPrompt}
          invalid={length === 0}
          className="font-mono text-xs"
          onChange={(event) => onChange({ systemPrompt: event.target.value })}
        />
        <div className="mt-1.5 flex items-center justify-between">
          <p className="text-2xs text-ink-faint">使用双花括号引用变量，例如 {'{{businessContext}}'}</p>
          <p className={length > maxPrompt * 0.9 ? 'text-2xs font-medium text-warn' : 'text-2xs text-ink-faint'}>
            {length.toLocaleString()} / {maxPrompt.toLocaleString()} 字符
          </p>
        </div>
        {length === 0 && <p className="field-error">System Prompt 保存前不可为空</p>}
      </SectionCard>

      <SectionCard title="提示词变量" description="运行时由调用方传值替换，系统变量由平台自动注入" bodyClassName="p-5">
        <div className="space-y-3">
          <div>
            <p className="mb-2 text-xs font-medium text-ink">系统变量</p>
            <Badge tone="neutral">{'{{currentTime}}'}</Badge>
            <span className="ml-2 text-2xs text-ink-faint">平台按工作区时区自动注入</span>
          </div>
          <div className="border-t border-line pt-3">
            <p className="mb-2 text-xs font-medium text-ink">自定义变量 <span className="text-ink-faint">（{draft.variables.length} / {maxVariables}）</span></p>
            {draft.variables.length > 0 && (
              <div className="mb-3 flex flex-wrap gap-2">
                {draft.variables.map((name) => (
                  <span key={name} className="inline-flex items-center gap-1.5 rounded-pill bg-brand-soft px-2.5 py-1 font-mono text-2xs text-brand">
                    {`{{${name}}}`}
                    <button type="button" aria-label={`删除变量 ${name}`} onClick={() => removeVariable(name)} className="text-brand/70 hover:text-danger">
                      <Trash2 className="h-3 w-3" />
                    </button>
                  </span>
                ))}
              </div>
            )}
            <div className="flex items-start gap-2">
              <Input
                aria-label="新增变量名"
                placeholder="输入变量名，如 businessContext"
                value={variableInput}
                invalid={Boolean(variableError)}
                onChange={(event) => { setVariableInput(event.target.value); setVariableError('') }}
                onKeyDown={(event) => { if (event.key === 'Enter') addVariable() }}
              />
              <Button variant="outline" icon={<Plus className="h-3.5 w-3.5" />} onClick={addVariable}>添加</Button>
            </div>
            {variableError && <p className="field-error" role="alert">{variableError}</p>}
            {unresolved.length > 0 && (
              <p className="mt-2 text-2xs text-warn">存在未解析引用：{unresolved.map((name) => `{{${name}}}`).join('、')}，运行前请检查 Prompt 中的变量引用。</p>
            )}
          </div>
        </div>
      </SectionCard>
    </div>
  )
}
