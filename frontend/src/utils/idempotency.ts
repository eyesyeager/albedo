/**
 * 幂等键生成（api-spec.md §1.4）。
 *
 * 🔴 重试必须复用同一个 key，否则会重复创建用户消息 / 重复消耗模型额度（EX-013）。
 */

/** 生成 UUID v4 形式的幂等键。 */
export function newIdempotencyKey(): string {
  const cryptoObj: Crypto | undefined = globalThis.crypto
  if (cryptoObj !== undefined && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID()
  }
  // 降级：非安全上下文（如 http 局域网访问）下 randomUUID 不可用
  const bytes = new Uint8Array(16)
  if (cryptoObj !== undefined && typeof cryptoObj.getRandomValues === 'function') {
    cryptoObj.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 生成客户端本地 ID（仅用于列表 key 稳定，绝不发送给后端）。 */
export function newClientId(): string {
  return `c_${newIdempotencyKey()}`
}
