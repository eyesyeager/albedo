const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'

function random(length = 6): string {
  return Array.from({ length }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join('')
}

export function newId(prefix: string): string {
  const separator = prefix.endsWith('_') || prefix.endsWith('-') ? '' : '-'
  return `${prefix}${separator}${random(6)}`
}

export function newRunId(): string {
  return `run_${random(8)}`
}

export function newThreadId(): string {
  return `thd_${random(8)}`
}

export function newRequestId(): string {
  return `req_${random(8)}`
}
