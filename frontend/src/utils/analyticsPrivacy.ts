/**
 * 埋点隐私过滤（api-spec.md §7.10.1 的字段与禁止项规则）。
 *
 * 从 `utils/analytics.ts` 抽出的理由：过滤规则是本项目**隐私红线**的实现，
 * 需要独立、密集的用例覆盖，且宿主文件已超 300 行。
 *
 * 🔴 纪律：
 *   1. 字段白名单外的字段一律剔除
 *   2. 禁止键名（不区分大小写子串匹配）命中 → 🔴 整条事件丢弃
 *   3. 值级命中手机号 / 邮箱 / ≥12 位长数字 → 🔴 整条事件丢弃
 *   4. 豁免只允许**精确键名**，且值形态被强约束（见 FORBIDDEN_KEY_EXACT_EXEMPT）
 */

/** 🔴 禁止键名（不区分大小写的子串匹配，api-spec §7.10.1）。 */
const FORBIDDEN_KEY_FRAGMENTS: readonly string[] = [
  'messagecontent',
  'content',
  'text',
  'prompt',
  'systemprompt',
  'skillinstruction',
  'instruction',
  'token',
  'authorization',
  'jwt',
  'credential',
  'apikey',
  'secret',
  'password',
  'phone',
  'mobile',
  'email',
  'idcard',
  'bankcard',
]

/**
 * 🔴 禁止键名的**精确豁免**（仅限契约白名单字段且值形态被强约束者）。
 *
 * 存在理由（D-008）：禁止键名用「不区分大小写子串匹配」，
 * 合法字段 `tokenUsage` 会命中 `token` 片段而被整条丢弃 ——
 * 结果是 api-spec §7.10.1 明确要求上报的 token 用量永久缺失。
 * 🔴 豁免必须是**精确键名**匹配，且该字段值经 `pickTokenUsage` 归一为三个 number，
 * 不存在携带凭据的可能；禁止扩大为前缀 / 子串豁免。
 */
const FORBIDDEN_KEY_EXACT_EXEMPT: readonly string[] = ['tokenUsage']

/** 允许上报的字段名（字段白名单，api-spec §7.10.1）。 */
export const ALLOWED_FIELDS: readonly string[] = [
  'clientEventId',
  'eventName',
  'occurredAt',
  'loginState',
  'configVersion',
  'conversationId',
  'agentId',
  'agentVersion',
  'toolType',
  'toolKey',
  'status',
  'result',
  'errorCode',
  'durationMs',
  'latencyMs',
  'charCount',
  'tokenUsage',
  'source',
  'pagePath',
  'action',
]

/** 值级敏感判定：手机号 / 邮箱 / ≥12 位长数字（身份证、银行卡）。 */
const SENSITIVE_VALUE_PATTERNS: readonly RegExp[] = [
  /^\+?\d{1,3}?[-\s]?1[3-9]\d{9}$/,
  /^1[3-9]\d{9}$/,
  /[^\s@]+@[^\s@]+\.[^\s@]+/,
  /\d{12,}/,
]

/**
 * 免值级扫描的字段：系统标识（雪花 ID 天然是 18~19 位长数字，
 * 若参与"≥12 位长数字"判定会把全部合法事件误杀）。
 * 🔴 这些字段本身不含个人信息，且已在服务端做同租户归属校验。
 */
export const IDENTIFIER_FIELDS: readonly string[] = ['conversationId', 'agentId', 'toolKey']

/** `tokenUsage` 的合法子字段。 */
const TOKEN_USAGE_FIELDS: readonly string[] = ['promptTokens', 'completionTokens', 'totalTokens']

/** 是否命中敏感值（导出以便测试直接断言拦截规则）。 */
export function hasSensitiveValue(value: unknown): boolean {
  if (value === undefined || value === null) {
    return false
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return false
  }
  if (typeof value === 'string') {
    return SENSITIVE_VALUE_PATTERNS.some((pattern) => pattern.test(value))
  }
  if (typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some(hasSensitiveValue)
  }
  // 函数 / symbol 等一律视为不可上报
  return true
}

/** 是否命中禁止键名。 */
export function hasForbiddenKey(payload: Readonly<Record<string, unknown>>): boolean {
  return Object.keys(payload).some((key) => {
    if (FORBIDDEN_KEY_EXACT_EXEMPT.includes(key)) {
      return false
    }
    const lower = key.toLowerCase()
    return FORBIDDEN_KEY_FRAGMENTS.some((fragment) => lower.includes(fragment))
  })
}

/** 是否存在值级敏感命中（系统标识字段除外）。 */
export function findSensitiveField(
  payload: Readonly<Record<string, unknown>>,
): string | undefined {
  return Object.entries(payload).find(
    ([key, value]) => !IDENTIFIER_FIELDS.includes(key) && hasSensitiveValue(value),
  )?.[0]
}

/** 归一 `tokenUsage`：🔴 只保留三个 number，非数字归零（防字符串携带凭据）。 */
export function pickTokenUsage(
  value: unknown,
): { promptTokens: number; completionTokens: number; totalTokens: number } | null {
  if (value === null || typeof value !== 'object') {
    return null
  }
  const source = value as Record<string, unknown>
  const picked: Record<string, number> = {}
  TOKEN_USAGE_FIELDS.forEach((field) => {
    const item = source[field]
    picked[field] = typeof item === 'number' ? item : 0
  })
  return {
    promptTokens: picked.promptTokens,
    completionTokens: picked.completionTokens,
    totalTokens: picked.totalTokens,
  }
}
