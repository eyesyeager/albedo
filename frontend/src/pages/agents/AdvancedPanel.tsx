import { AlertCircle } from 'lucide-react'
import { useWorkspace } from '../../store/workspace'
import { Field, Input, Switch } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'
import type { PanelProps } from './BasicInfoPanel'

const TOGGLES = [
  { key: 'keepContext', title: '保留会话上下文', description: '同一 threadId 中携带历史消息，支持连续多轮对话' },
  { key: 'autoRetry', title: '自动重试工具调用', description: '工具返回临时错误时自动重试；业务校验错误不重试' },
  { key: 'outputThinkingSummary', title: '输出思考摘要', description: '调试面板展示阶段性摘要，不输出模型私有推理链' },
] as const

export function AdvancedPanel({ draft, onChange }: PanelProps) {
  const limits = useWorkspace((state) => state.systemConfig.limits)
  const defaults = useWorkspace((state) => state.systemConfig.defaults.agent.advanced)
  const callsMin = limits.maxToolCallsMin || 1
  const callsMax = limits.maxToolCallsMax || 100
  const secondsMin = limits.maxRunSecondsMin || 3
  const secondsMax = limits.maxRunSecondsMax || 3600
  const setAdvanced = (patch: Partial<typeof draft.advanced>) => onChange({ advanced: { ...draft.advanced, ...patch } })

  return (
    <div className="space-y-4">
      <SectionCard title="高级设置" description="运行限制、重试与协议细节收纳在此，保存后立即作用于后续 Run">
        <div className="divide-y divide-line">
          {TOGGLES.map((toggle) => (
            <div key={toggle.key} className="flex items-center justify-between gap-4 py-3.5 first:pt-0 last:pb-0">
              <div>
                <p className="text-xs font-medium text-ink">{toggle.title}</p>
                <p className="mt-1 text-2xs leading-relaxed text-ink-faint">{toggle.description}</p>
              </div>
              <Switch
                label={toggle.title}
                checked={draft.advanced[toggle.key]}
                onChange={(checked) => setAdvanced({ [toggle.key]: checked })}
              />
            </div>
          ))}
        </div>
      </SectionCard>

      <SectionCard title="运行限制" description="达到上限后停止继续调用工具并进入结果整理" bodyClassName="p-5">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="最大工具调用次数" htmlFor="max-tool-calls" hint={`${callsMin} ~ ${callsMax} 次，默认 ${defaults.maxToolCalls} 次`}>
            <Input
              id="max-tool-calls"
              type="number"
              min={callsMin}
              max={callsMax}
              value={draft.advanced.maxToolCalls}
              invalid={draft.advanced.maxToolCalls < callsMin || draft.advanced.maxToolCalls > callsMax}
              onChange={(event) => setAdvanced({ maxToolCalls: Number(event.target.value) })}
            />
          </Field>
          <Field label="单次运行最大时长" htmlFor="max-run-seconds" hint={`${secondsMin} ~ ${secondsMax} 秒，默认 ${defaults.maxRunSeconds} 秒`}>
            <Input
              id="max-run-seconds"
              type="number"
              min={secondsMin}
              max={secondsMax}
              value={draft.advanced.maxRunSeconds}
              invalid={draft.advanced.maxRunSeconds < secondsMin || draft.advanced.maxRunSeconds > secondsMax}
              onChange={(event) => setAdvanced({ maxRunSeconds: Number(event.target.value) })}
            />
          </Field>
        </div>
        <div className="mt-4 flex items-start gap-2 rounded-field bg-warn/5 px-3 py-2.5 text-2xs leading-relaxed text-ink-muted">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warn" />
          <span>每次实际向 MCP 或内置工具发出的请求计 1 次（含自动重试）；Skill 加载不计入。达到上限后模型可基于已有结果生成最终回答，终止原因会写入 Run 日志。</span>
        </div>
      </SectionCard>
    </div>
  )
}
