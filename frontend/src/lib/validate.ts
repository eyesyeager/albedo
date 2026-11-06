import type { ModelPublication, PublicationDraft, SystemLimits } from '../types/domain'

export interface ValidationIssue {
  field: string
  message: string
}

export function isBlank(value: string): boolean {
  return value.trim().length === 0
}

export function isHttpUrl(value: string): boolean {
  if (!/^https?:\/\//i.test(value.trim())) return false
  return /^https?:\/\/[^\s/$.?#].[^\s]*$/i.test(value.trim())
}

export function isValidVariableName(value: string): boolean {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(value)
}

export function parseJsonObject(text: string): Record<string, unknown> | null {
  if (isBlank(text)) return {}
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null
  return parsed as Record<string, unknown>
}

export function validateName(value: string, label = '名称', max = 50): string | null {
  if (isBlank(value)) return `${label}不能为空`
  if (value.trim().length > max) return `${label}不能超过 ${max} 个字符`
  return null
}

export function validateNumber(value: number, min: number, max: number, label: string): string | null {
  if (!Number.isFinite(value)) return `${label}必须是数字`
  if (value < min || value > max) return `${label}需在 ${min} ~ ${max} 之间`
  return null
}

export function validatePublication(
  draft: PublicationDraft,
  modelId: string,
  publications: ModelPublication[],
  limits?: Pick<SystemLimits, 'accessKeyMinLength' | 'temperatureMin' | 'temperatureMax' | 'qpmMin'>,
): ValidationIssue[] {
  const minKey = limits?.accessKeyMinLength || 12
  const tempMin = limits?.temperatureMin ?? 0
  const tempMax = limits?.temperatureMax || 2
  const qpmMin = limits?.qpmMin || 1
  const issues: ValidationIssue[] = []
  if (isBlank(draft.version)) issues.push({ field: 'version', message: '版本号不能为空' })
  else if (publications.some((p) => p.modelId === modelId && p.version === draft.version.trim())) {
    issues.push({ field: 'version', message: '该版本号已存在，请使用新的版本号' })
  }
  if (isBlank(draft.callIdentifier)) issues.push({ field: 'callIdentifier', message: '模型调用标识不能为空' })
  else if (!/^[A-Za-z0-9._-]+$/.test(draft.callIdentifier.trim())) {
    issues.push({ field: 'callIdentifier', message: '调用标识仅允许字母、数字、点、下划线和连字符' })
  } else if (publications.some((p) => p.callIdentifier === draft.callIdentifier.trim())) {
    issues.push({ field: 'callIdentifier', message: '模型调用标识已被占用，请更换' })
  }
  if (draft.apiKey.trim().length < minKey) issues.push({ field: 'apiKey', message: `自定义 API Key 至少需要 ${minKey} 个字符` })
  if (parseJsonObject(draft.headers) === null) {
    issues.push({ field: 'headers', message: '自定义请求头必须是合法的 JSON 对象' })
  }
  if (!Number.isFinite(draft.temperature) || draft.temperature < tempMin || draft.temperature > tempMax) {
    issues.push({ field: 'temperature', message: `温度需在 ${tempMin} ~ ${tempMax} 之间` })
  }
  if (!Number.isInteger(draft.maxOutputTokens) || draft.maxOutputTokens < 1) {
    issues.push({ field: 'maxOutputTokens', message: '最大输出 Tokens 必须是正整数' })
  }
  if (!Number.isInteger(draft.qpm) || draft.qpm < qpmMin) {
    issues.push({ field: 'qpm', message: `QPM 必须是不小于 ${qpmMin} 的整数` })
  }
  return issues
}

export function firstIssue(issues: ValidationIssue[], field: string): string | undefined {
  return issues.find((issue) => issue.field === field)?.message
}
