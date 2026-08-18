/**
 * 当前用户身份（契约：docs/api-spec.md §4.3.1）。
 *
 * 🔴 后端不返回手机号 / 邮箱 / token；前端也不得声明这些字段。
 */

/** 租户内角色（本地成员关系，与 eyesUser role 无关）。 */
export type TenantRole = 'TENANT_ADMIN' | 'TENANT_OPERATOR' | 'END_USER'

export interface MeInfo {
  uid: string
  nickname: string
  avatarUrl: string
  tenantRole: TenantRole
  /** eyesUser role=ADMIN；🔴 为 true 也不代表拥有任何租户内权限（AC-AUTH-007） */
  platformAdmin: boolean
  memberStatus: string
}
