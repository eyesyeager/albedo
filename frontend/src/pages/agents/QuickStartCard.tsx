import { buildCodeSample, endpointOf, type Protocol, type SampleLanguage } from '../../components/common/CodeSample'
import { CodeBlock, Segmented, Select, CopyButton } from '../../components/ui'
import { SectionCard } from '../../components/common/PageHeader'

export function QuickStartCard({
  protocol, onProtocolChange, language, onLanguageChange, agentId, domain,
}: {
  protocol: Protocol
  onProtocolChange: (protocol: Protocol) => void
  language: SampleLanguage
  onLanguageChange: (language: SampleLanguage) => void
  agentId: string
  domain: string
}) {
  const sample = buildCodeSample(protocol, language, agentId, domain)
  const endpoint = endpointOf(protocol, agentId, domain)

  return (
    <SectionCard
      title="快速接入"
      description="示例使用当前 Agent ID 与实际 Endpoint，密钥通过环境变量注入"
      actions={
        <Select
          aria-label="示例语言"
          value={language}
          onChange={(event) => onLanguageChange(event.target.value as SampleLanguage)}
          options={[
            { value: 'curl', label: 'cURL' },
            { value: 'python', label: 'Python' },
            { value: 'javascript', label: 'JavaScript' },
          ]}
          className="h-8 w-[120px] py-1 text-xs"
        />
      }
      bodyClassName="p-4"
    >
      <Segmented
        className="mb-4"
        value={protocol}
        onChange={(value) => onProtocolChange(value as Protocol)}
        items={[{ key: 'api', label: '标准对话 API' }, { key: 'agui', label: 'AGUI 协议接口' }]}
      />
      <div className="mb-3 flex items-start gap-2 rounded-field border border-line bg-canvas/70 px-3 py-2">
        <span className="shrink-0 rounded bg-brand-soft px-1.5 py-0.5 font-mono text-2xs font-semibold text-brand">POST</span>
        <code className="min-w-0 flex-1 break-all font-mono text-2xs leading-relaxed text-ink">{endpoint}</code>
        <CopyButton value={endpoint} label="复制地址" message="Endpoint 已复制" />
      </div>
      <CodeBlock code={sample} language={language} maxHeight="360px" />
      <p className="mt-3 text-2xs leading-relaxed text-ink-faint">
        {protocol === 'api'
          ? '同步模式返回完整 JSON；将 stream 设为 true 并把 Accept 改为 text/event-stream 可获得最小文本 SSE 事件。'
          : 'AGUI 始终返回结构化 SSE 事件；客户端应按 sequence 排序去重，并按 START → CONTENT → END 配对文本消息。'}
      </p>
    </SectionCard>
  )
}
