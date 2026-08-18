/**
 * 未发送输入草稿的本地保存与恢复（PRD §6.2.1 / AC-CON-001）。
 *
 * 🔴 草稿只允许在**原租户 Host** 下恢复：key 内含 tenantId，
 *    且 localStorage 本身按 origin 隔离，双重保证不跨租户串草稿。
 * 🔴 草稿只存用户自己的输入，绝不存 token 或任何身份凭据。
 */
const DRAFT_PREFIX = 'albedo:draft'

function draftKey(tenantId: string): string {
  return `${DRAFT_PREFIX}:${tenantId}:${location.host}`
}

export function saveDraft(tenantId: string, content: string): void {
  if (tenantId.length === 0) {
    return
  }
  if (content.trim().length === 0) {
    clearDraft(tenantId)
    return
  }
  localStorage.setItem(draftKey(tenantId), content)
}

export function readDraft(tenantId: string): string {
  if (tenantId.length === 0) {
    return ''
  }
  return localStorage.getItem(draftKey(tenantId)) ?? ''
}

export function clearDraft(tenantId: string): void {
  if (tenantId.length === 0) {
    return
  }
  localStorage.removeItem(draftKey(tenantId))
}
