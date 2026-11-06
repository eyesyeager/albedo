const CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0123456789'

export function maskedToken(prefix: string, suffix: string, dots = 12): string {
  return `${prefix}${'•'.repeat(dots)}${suffix}`
}

export function maskedAccessKey(suffix: string): string {
  return maskedToken('alb_access_', suffix)
}

export function maskedAgentToken(suffix: string): string {
  return maskedToken('alb_agt_', suffix)
}

export function maskedUpstreamKey(suffix: string): string {
  return maskedToken('sk-', suffix, 8)
}

export function newSecretKey(prefix = 'alb_sk_', length = 40): string {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  const body = Array.from(bytes, (byte) => CHARS[byte % CHARS.length]).join('')
  return `${prefix}${body}`
}

