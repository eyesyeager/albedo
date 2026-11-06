import { useEffect, useMemo, useState } from 'react'
import { CheckCircle2, CornerDownLeft, RotateCcw, Play, XCircle } from 'lucide-react'
import type { McpTool, McpToolInputSchema, McpToolSchemaProperty, McpToolTestResult } from '../../types/domain'
import { api } from '../../lib/api'
import { asError } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { Button, Field, Modal, Textarea, toast } from '../../components/ui'

function resolveType(prop: McpToolSchemaProperty): string {
  if (prop.type) return prop.type
  const candidate = prop.anyOf?.find((item) => item.type && item.type !== 'null')
    ?? prop.oneOf?.find((item) => item.type && item.type !== 'null')
  return candidate?.type ?? 'string'
}

function sampleValue(prop: McpToolSchemaProperty): unknown {
  if (prop.default !== undefined && prop.default !== null) return prop.default
  if (prop.enum && prop.enum.length > 0 && prop.enum[0] !== null) return prop.enum[0]
  switch (resolveType(prop)) {
    case 'integer':
    case 'number':
      return 0
    case 'boolean':
      return false
    case 'array':
      return []
    case 'object':
      return {}
    default:
      return ''
  }
}

function normalizeSchema(input?: McpToolInputSchema): McpToolInputSchema | undefined {
  if (!input || input.properties) return input
  const values = Object.values(input as Record<string, unknown>)
  // 兼容仅返回 properties 映射的旧数据。
  if (values.length > 0 && values.every((value) => value !== null && typeof value === 'object')) {
    return { type: 'object', properties: input as Record<string, McpToolSchemaProperty> }
  }
  return input
}

function buildSkeleton(schema?: McpToolInputSchema): Record<string, unknown> {
  const properties = schema?.properties ?? {}
  const requiredNames = schema?.required
  const required = new Set(requiredNames ?? [])
  // 服务端未声明 required 时无法区分必填，按全部参数预填，避免用户面对空对象。
  const prefillAll = !requiredNames || requiredNames.length === 0
  const skeleton: Record<string, unknown> = {}
  Object.entries(properties).forEach(([name, prop]) => {
    if (prefillAll || required.has(name)) skeleton[name] = sampleValue(prop)
  })
  return skeleton
}

function pretty(value: unknown): string {
  return JSON.stringify(value, null, 2)
}

function describeValue(value: unknown): string {
  return typeof value === 'string' ? value : JSON.stringify(value)
}

export function McpToolTestDialog({
  mcpId,
  mcpName,
  tool,
  timeoutSeconds,
  onClose,
}: {
  mcpId: string
  mcpName: string
  tool: McpTool | null
  timeoutSeconds: number
  onClose: () => void
}) {
  const [args, setArgs] = useState('{}')
  const [schema, setSchema] = useState<McpToolInputSchema | undefined>(undefined)
  const [schemaLoading, setSchemaLoading] = useState(false)
  const [schemaError, setSchemaError] = useState('')
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<McpToolTestResult | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setResult(null)
    setError('')
    setSchemaError('')
    if (!tool) {
      setSchema(undefined)
      setArgs('{}')
      return
    }
    const cached = normalizeSchema(tool.inputSchema)
    if (cached && Object.keys(cached.properties ?? {}).length > 0) {
      setSchema(cached)
      setArgs(pretty(buildSkeleton(cached)))
      setSchemaLoading(false)
      return
    }
    setSchema(undefined)
    setSchemaLoading(true)
    setArgs('{}')
    api.mcp
      .toolSchema(mcpId, tool.id)
      .then((value) => {
        if (cancelled) return
        const normalized = normalizeSchema(value)
        setSchema(normalized)
        setArgs(pretty(buildSkeleton(normalized)))
      })
      .catch((reason: unknown) => {
        if (cancelled) return
        console.error('[albedo] 读取 MCP 工具入参定义失败：', asError(reason).message)
        setSchemaError(asError(reason).message)
      })
      .finally(() => {
        if (!cancelled) setSchemaLoading(false)
      })
    return () => { cancelled = true }
  }, [tool?.id, mcpId])

  const params = useMemo(() => {
    const properties = schema?.properties ?? {}
    const required = new Set(schema?.required ?? [])
    return Object.entries(properties)
      .map(([name, prop]) => ({ name, prop, required: required.has(name) }))
      .sort((left, right) => Number(right.required) - Number(left.required) || left.name.localeCompare(right.name))
  }, [schema])

  const resetArgs = () => {
    setArgs(pretty(buildSkeleton(schema)))
    setError('')
    setResult(null)
  }

  const insertParam = (name: string, prop: McpToolSchemaProperty) => {
    setArgs((current) => {
      try {
        const parsed = JSON.parse(current.trim() || '{}')
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return current
        return pretty({ ...(parsed as Record<string, unknown>), [name]: sampleValue(prop) })
      } catch {
        return current
      }
    })
  }

  const run = () => {
    if (!tool || running) return
    let parsed: unknown
    try {
      parsed = JSON.parse(args.trim() || '{}')
    } catch {
      setError('参数必须是合法 JSON，例如 {"query":"hello"}')
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setError('参数必须是 JSON 对象，例如 {"query":"hello"}')
      return
    }
    setError('')
    setRunning(true)
    api.mcp
      .testTool(mcpId, tool.id, parsed as Record<string, unknown>, timeoutSeconds)
      .then((value) => {
        setResult(value)
        if (value.status === 'success') toast.success('工具调用成功')
        else toast.error('工具调用失败')
      })
      .catch((reason: unknown) => {
        const message = asError(reason).message
        console.error('[albedo] MCP 工具调用测试失败：', message)
        setError(message)
        toast.error(message)
      })
      .finally(() => setRunning(false))
  }

  return (
    <Modal
      open={Boolean(tool)}
      onClose={onClose}
      title={tool ? `调用测试 · ${tool.name}` : '调用测试'}
      description={`${mcpName} / ${tool?.key ?? ''}`}
      width="lg"
      footer={
        <>
          <Button variant="outline" onClick={onClose}>关闭</Button>
          <Button icon={<Play className="h-3.5 w-3.5" />} loading={running} onClick={run}>执行调用</Button>
        </>
      }
    >
      <div className="space-y-4">
        {tool?.description && (
          <p className="rounded-field bg-canvas px-3 py-2 text-2xs leading-relaxed text-ink-muted">{tool.description}</p>
        )}

        <div className="rounded-field border border-line">
          <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-2">
            <p className="text-2xs font-medium text-ink">
              参数说明
              {params.length > 0 && (
                <span className="ml-1.5 font-normal text-ink-faint">
                  共 {params.length} 个，必填 {params.filter((item) => item.required).length} 个
                </span>
              )}
            </p>
            <Button
              variant="ghost"
              size="sm"
              icon={<RotateCcw className="h-3 w-3" />}
              disabled={schemaLoading || params.length === 0}
              onClick={resetArgs}
            >
              重置为默认
            </Button>
          </div>
          {schemaLoading ? (
            <p className="px-3 py-2 text-2xs text-ink-faint">正在从 MCP 服务读取入参定义…</p>
          ) : params.length === 0 ? (
            <p className="px-3 py-2 text-2xs leading-relaxed text-ink-faint">
              {schemaError
                ? `读取入参定义失败：${schemaError}；可按工具说明手动填写 JSON`
                : '该工具未声明入参定义，可按工具说明手动填写 JSON 对象'}
            </p>
          ) : (
            <div className="scroll-thin max-h-52 overflow-y-auto divide-y divide-line">
              {params.map(({ name, prop, required }) => (
                <div key={name} className="px-3 py-2">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <code className="font-mono text-2xs text-brand">{name}</code>
                    <span className="text-2xs text-ink-faint">{resolveType(prop)}</span>
                    {required ? (
                      <span className="rounded bg-warn/10 px-1.5 py-0.5 text-2xs text-warn">必填</span>
                    ) : (
                      <span className="rounded bg-canvas px-1.5 py-0.5 text-2xs text-ink-faint">可选</span>
                    )}
                    <Button
                      variant="ghost"
                      size="sm"
                      icon={<CornerDownLeft className="h-3 w-3" />}
                      onClick={() => insertParam(name, prop)}
                    >
                      插入
                    </Button>
                  </div>
                  <p className="mt-1 text-2xs leading-relaxed text-ink-muted">{prop.description || '未提供说明'}</p>
                  {(prop.enum?.length || prop.default !== undefined) && (
                    <p className="mt-0.5 text-2xs text-ink-faint">
                      {prop.enum?.length ? `可选值：${prop.enum.map(describeValue).join(' / ')}` : ''}
                      {prop.enum?.length && prop.default !== undefined ? '；' : ''}
                      {prop.default !== undefined ? `默认：${describeValue(prop.default)}` : ''}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>

        <Field
          label="调用参数（JSON 对象）"
          htmlFor="mcp-tool-args"
          hint="已按入参定义预填必填项，可点击「插入」补充可选参数"
        >
          <Textarea
            id="mcp-tool-args"
            rows={7}
            value={args}
            className="font-mono text-xs"
            placeholder='{"query": "hello"}'
            onChange={(event) => setArgs(event.target.value)}
          />
        </Field>

        {result && (
          <div className="space-y-2">
            <div
              className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-field px-3 py-2 text-xs ${
                result.status === 'success' ? 'bg-ok/10 text-ok' : 'bg-danger/10 text-danger'
              }`}
            >
              {result.status === 'success' ? <CheckCircle2 className="h-3.5 w-3.5" /> : <XCircle className="h-3.5 w-3.5" />}
              <span className="font-medium">{result.status === 'success' ? '调用成功' : '调用失败'}</span>
              <span className="tabular-nums">耗时 {result.durationMs} ms</span>
              <span className="tabular-nums text-ink-faint">{formatDateTime(result.timestamp)}</span>
            </div>
            {result.error && (
              <p className="rounded-field bg-danger/10 px-3 py-2 font-mono text-2xs leading-relaxed text-danger break-all">{result.error}</p>
            )}
            {result.output && (
              <pre className="max-h-56 overflow-auto rounded-field border border-line bg-canvas px-3 py-2 font-mono text-2xs leading-relaxed whitespace-pre-wrap break-all text-ink">
                {result.output}
              </pre>
            )}
          </div>
        )}

        {error && <p className="field-error" role="alert">{error}</p>}
        <p className="text-2xs leading-relaxed text-ink-faint">
          调用测试会真实请求 MCP 服务，并计入该服务的调用次数与成功率；失败记录同样可在「调用记录」中查看。
        </p>
      </div>
    </Modal>
  )
}
