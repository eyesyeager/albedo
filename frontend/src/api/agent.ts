/**
 * Agent 只读接口（契约：docs/api-spec.md §4.4.1）。
 *
 * 权限 `NO`：匿名亦可查看展示信息，因此首页无需登录即可渲染选择器。
 */
import type { PageData } from '@/types/api'
import type { Agent } from '@/types/agent'
import request from '@/utils/request'

export const agentApi = {
  /** 已发布且启用的 Agent 列表（默认 Agent 置顶，由后端排序保证）。 */
  list: (page?: number, pageSize?: number): Promise<PageData<Agent>> =>
    request.get<PageData<Agent>>('/api/v1/agents', { params: { page, pageSize } }),
}
