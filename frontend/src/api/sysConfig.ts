/**
 * 平台配置接口（反硬编码主链路）。契约：docs/api-spec.md §4.1.1
 */
import request from '@/utils/request'

/** 平台配置：按 group 聚合的 KV（value 已按 value_type 转型）。 */
export type SysConfigMap = Record<string, Record<string, unknown>>

export const sysConfigApi = {
  all: (): Promise<SysConfigMap> => request.get<SysConfigMap>('/api/v1/sys-config'),
  byGroup: (group: string): Promise<SysConfigMap> =>
    request.get<SysConfigMap>('/api/v1/sys-config', { params: { group } }),
}
