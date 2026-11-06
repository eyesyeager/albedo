import type { ModelResource } from '../../types/domain'
import { useWorkspace } from '../../store/workspace'
import { Field, Input, Select } from '../../components/ui'
import { IssueHint } from '../../components/common/StatusBadge'
import { SectionCard } from '../../components/common/PageHeader'
import type { PanelProps } from './BasicInfoPanel'


function modelOptions(models: ModelResource[], currentId: string | null) {
  const usable = models.filter((model) => model.enabled && model.type === 'chat' && model.connection === 'normal')
  const current = models.find((model) => model.id === currentId)
  const list = current && !usable.some((model) => model.id === current.id) ? [current, ...usable] : usable
  return list.map((model) => ({
    value: model.id,
    label: `${model.name} · ${model.vendor}${model.enabled && model.connection === 'normal' ? '' : model.enabled ? '（连接待复测）' : '（已停用）'}`,
  }))
}

export function ModelSettingsPanel({ draft, onChange }: PanelProps) {
  const models = useWorkspace((state) => state.models)
  const limits = useWorkspace((state) => state.systemConfig.limits)
  const tokenCeiling = limits.maxOutputTokensCeiling || 32768
  const options = modelOptions(models, draft.defaultModelId)
  const defaultModel = models.find((model) => model.id === draft.defaultModelId)
  const invalid = !defaultModel || !defaultModel.enabled || defaultModel.connection !== 'normal'
  const testOptions = models.filter((model) => model.enabled && model.type === 'chat' && model.connection === 'normal')
    .map((model) => ({ value: model.id, label: `${model.name} · ${model.vendor}` }))

  const setParam = (patch: Partial<AgentParams>) => onChange({ modelParams: { ...draft.modelParams, ...patch } })

  return (
    <div className="space-y-4">
      <SectionCard title="模型选择" description="默认模型用于正式运行；测试模型仅作用于右侧测试会话" bodyClassName="space-y-4 p-5">
        <Field label="默认模型" htmlFor="default-model" required hint="仅展示已启用模型；停用时保留原选择并标记异常">
          <Select
            id="default-model"
            value={draft.defaultModelId ?? ''}
            onChange={(event) => onChange({ defaultModelId: event.target.value, mounts: { ...draft.mounts, modelIds: [event.target.value] } })}
            options={options.length ? options : [{ value: '', label: '暂无可用模型' }]}
          />
          {invalid && <p className="mt-2 flex items-center gap-1.5 text-xs text-warn"><IssueHint text="资源异常" />请检查模型启用状态与连接测试结果</p>}
        </Field>
        <Field label="测试会话模型" htmlFor="test-model" hint="临时切换只影响当前测试，不会覆盖默认模型">
          <Select
            id="test-model"
            value={draft.testModelId ?? draft.defaultModelId ?? ''}
            onChange={(event) => onChange({ testModelId: event.target.value })}
            options={testOptions.length ? testOptions : [{ value: '', label: '暂无可用模型' }]}
          />
        </Field>
      </SectionCard>

      <SectionCard title="生成参数" description="Agent 级参数，与模型发布版本参数相互独立" bodyClassName="p-5">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <Field label="温度" htmlFor="temperature" hint={`${limits.temperatureMin || 0} ~ ${limits.temperatureMax || 2}`}>
            <Input id="temperature" type="number" min={limits.temperatureMin || 0} max={limits.temperatureMax || 2} step={0.1} value={draft.modelParams.temperature} onChange={(event) => setParam({ temperature: Number(event.target.value) })} />
          </Field>
          <Field label="Top P" htmlFor="top-p" hint={`${limits.topPMin || 0} ~ ${limits.topPMax || 1}`}>
            <Input id="top-p" type="number" min={limits.topPMin || 0} max={limits.topPMax || 1} step={0.05} value={draft.modelParams.topP} onChange={(event) => setParam({ topP: Number(event.target.value) })} />
          </Field>
          <Field label="最大输出 Token" htmlFor="max-tokens" hint={`正整数，上限 ${tokenCeiling.toLocaleString()}`}>
            <Input id="max-tokens" type="number" min={1} max={tokenCeiling} value={draft.modelParams.maxOutputTokens} onChange={(event) => setParam({ maxOutputTokens: Number(event.target.value) })} />
          </Field>
        </div>
        <p className="mt-3 text-2xs text-ink-faint">模型资源层不设置默认温度，避免与 Agent 级参数和发布版本配置冲突。</p>
      </SectionCard>
    </div>
  )
}

type AgentParams = { temperature: number; topP: number; maxOutputTokens: number }
