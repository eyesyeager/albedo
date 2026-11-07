import type { Protocol } from '../../components/common/CodeSample'
import { endpointOf, headerLinesOf } from '../../components/common/CodeSample'
import { Badge } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'

interface DocRow {
  field: string
  type: string
  required: string
  description: string
}

const API_REQUEST: DocRow[] = [
  { field: 'messages', type: 'array', required: '是', description: '对话消息列表，至少一条；每条含 role 与 content' },
  { field: 'threadId', type: 'string', required: '否', description: '会话标识，复用可延续平台保存的上下文' },
  { field: 'runId', type: 'string', required: '否', description: '本次运行标识；调用方提供时须在工作区唯一' },
  { field: 'variables', type: 'object', required: '视配置', description: 'Agent 自定义提示词变量，如 { businessContext: "电商" }' },
  { field: 'stream', type: 'boolean', required: '否', description: '是否通过 SSE 流式返回文本，默认 false' },
  { field: 'includeUsage', type: 'boolean', required: '否', description: '是否返回 Token 用量，默认 true' },
  { field: 'metadata', type: 'object', required: '否', description: '调用方自定义追踪信息，仅用于日志检索' },
]

const API_RESPONSE: DocRow[] = [
  { field: 'id / object', type: 'string', required: '是', description: '响应 ID；object 固定为 agent.chat.completion' },
  { field: 'agentId / threadId / runId', type: 'string', required: '是', description: '被调用 Agent、会话与本次运行标识' },
  { field: 'status', type: 'enum', required: '是', description: 'completed、failed、timeout、cancelled' },
  { field: 'message', type: 'object', required: '是', description: 'Agent 最终回复，含 id、role、content' },
  { field: 'usage', type: 'object', required: '否', description: 'inputTokens、outputTokens、totalTokens' },
  { field: 'finishReason', type: 'enum', required: '是', description: 'stop、length、tool_limit、timeout、error' },
  { field: 'metadata', type: 'object', required: '否', description: '耗时与工具调用次数摘要，不含敏感参数' },
]

const AGUI_REQUEST: DocRow[] = [
  { field: 'threadId', type: 'string', required: '是', description: '会话标识，同一前端会话保持不变' },
  { field: 'runId', type: 'string', required: '是', description: '本次运行标识，原始 HTTP 调用方必须提供' },
  { field: 'messages', type: 'array', required: '是', description: '当前历史消息，至少含触发本次运行的用户消息' },
  { field: 'tools', type: 'array', required: '否', description: '前端临时工具定义，不会自动获得平台资源权限' },
  { field: 'context', type: 'object', required: '否', description: '页面与业务上下文，变量放在 variables 子项' },
  { field: 'state', type: 'object', required: '否', description: '前端当前共享状态快照' },
  { field: 'forwardedProps', type: 'object', required: '否', description: '需透传给运行时的扩展属性' },
]

const AGUI_EVENTS: DocRow[] = [
  { field: 'RUN_STARTED', type: '事件', required: '首个', description: '本次运行已创建，携带 Agent ID 与开始时间' },
  { field: 'TEXT_MESSAGE_START / CONTENT / END', type: '事件', required: '配对', description: '文本消息按 START → CONTENT(0~N) → END 输出' },
  { field: 'THINKING_TEXT_MESSAGE_START / CONTENT / END', type: '事件', required: '可选', description: '推理模型的思考增量，与正式回答分开输出' },
  { field: 'TOOL_CALL_START / ARGS / END / RESULT', type: '事件', required: '配对', description: '工具调用参数与结果摘要，默认不返回敏感完整值' },
  { field: 'STATE_SNAPSHOT / STATE_DELTA', type: '事件', required: '可选', description: '下发完整共享状态或 JSON Patch 增量' },
  { field: 'RUN_FINISHED', type: '事件', required: '终止', description: 'Run 正常结束，返回状态、Token 与耗时' },
  { field: 'RUN_ERROR', type: '事件', required: '终止', description: 'Run 失败或超时，含 code、message 与 retryable' },
]

function DocTable({ title, rows }: { title: string; rows: DocRow[] }) {
  return (
    <div>
      <p className="mb-2 text-xs font-medium text-ink">{title}</p>
      <div className="overflow-x-auto rounded-field border border-line">
        <table className="w-full min-w-[560px] text-left">
          <thead className="table-head">
            <tr>
              {['字段 / 事件', '类型', '必填', '说明'].map((head) => <th key={head} className="px-3 py-2 font-medium">{head}</th>)}
            </tr>
          </thead>
          <tbody className="divide-y divide-line/70">
            {rows.map((row) => (
              <tr key={row.field} className="transition-colors hover:bg-canvas/60">
                <td className="px-3 py-2 font-mono text-2xs text-brand">{row.field}</td>
                <td className="px-3 py-2 text-2xs text-ink-muted">{row.type}</td>
                <td className="px-3 py-2 text-2xs text-ink-muted">{row.required}</td>
                <td className="px-3 py-2 text-2xs leading-relaxed text-ink-muted">{row.description}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function ApiDocCard({ protocol, agentId, domain, enabled }: { protocol: Protocol; agentId: string; domain: string; enabled: boolean }) {
  const headers = headerLinesOf(protocol)
  const endpoint = endpointOf(protocol, agentId, domain)

  return (
    <SectionCard
      title="接口文档"
      description="协议版本与示例代码随当前选择同步"
      actions={<Badge tone={enabled ? 'ok' : 'neutral'} dot>{enabled ? '接口已开启' : '接口未开启'}</Badge>}
      bodyClassName="space-y-5 p-5"
    >
      <div>
        <p className="mb-2 text-xs font-medium text-ink">请求行</p>
        <div className="flex items-start gap-2 rounded-field bg-canvas px-3 py-2">
          <span className="font-mono text-2xs font-semibold text-brand">POST</span>
          <code className="min-w-0 break-all font-mono text-2xs text-ink">{endpoint}</code>
        </div>
      </div>

      <div>
        <p className="mb-2 text-xs font-medium text-ink">请求头</p>
        <div className="divide-y divide-line/70 rounded-field border border-line">
          {headers.map((header) => (
            <div key={header.name} className="flex flex-wrap gap-2 px-3 py-2">
              <code className="font-mono text-2xs text-ink">{header.name}</code>
              <code className="min-w-0 break-all font-mono text-2xs text-ink-muted">{header.value}</code>
            </div>
          ))}
        </div>
      </div>

      <DocTable title="请求参数" rows={protocol === 'api' ? API_REQUEST : AGUI_REQUEST} />
      {protocol === 'api' ? (
        <DocTable title="响应参数" rows={API_RESPONSE} />
      ) : (
        <>
          <div>
            <p className="mb-2 text-xs font-medium text-ink">SSE 事件通用结构</p>
            <p className="rounded-field bg-canvas px-3 py-2 font-mono text-2xs leading-relaxed text-ink-muted">
              type · threadId · runId · timestamp · sequence
            </p>
          </div>
          <DocTable title="响应事件" rows={AGUI_EVENTS} />
        </>
      )}
      <p className="text-2xs leading-relaxed text-ink-faint">
        错误发生在 SSE 建立前时返回标准 HTTP 状态与 JSON 错误体（含 code、message、requestId）；建立后标准 API 发送 error 事件，AGUI 发送 RUN_ERROR。所有错误均可用 runId 在调用日志中定位。
      </p>
    </SectionCard>
  )
}
