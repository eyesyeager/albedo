/**
 * 当前用户接口（契约：docs/api-spec.md §4.3.1）。
 *
 * 🔴 这不是登录接口：本项目**不存在** login / register / logout / refresh。
 *    它只是"用已有 Token 读取自身身份"，命中即触发后端惰性建户。
 * 🔴 调用前必须确认本地已有 token，否则后端返回 20001 会触发整页跳 SSO。
 */
import type { MeInfo } from '@/types/user'
import request from '@/utils/request'

export const meApi = {
  get: (): Promise<MeInfo> => request.get<MeInfo>('/api/v1/me'),
}
