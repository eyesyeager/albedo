package com.eyes.albedo.platform.dto;

/**
 * 租户主数据视图（跨模块只读传递用）。
 *
 * <p>存在意义：{@code tenants} 是平台表，其他模块（site / agent）需要它的
 * {@code timezone / locale / configVersion / version}，但架构规定<b>禁止跨模块直接使用别人的 Repository</b>，
 * 因此统一由 {@code TenantService} 返回本视图。
 *
 * @param tenantId      租户号
 * @param name          租户名称（内部管理用）
 * @param primaryHost   主 Host
 * @param status        draft / enabled / suspended / archived
 * @param timezone      展示时区（IANA）
 * @param locale        语言
 * @param configVersion 当前已发布站点配置版本指针，0 = 未发布
 * @param version       JPA 乐观锁版本（站点配置编辑/发布的并发令牌）
 */
public record TenantProfile(String tenantId,
                            String name,
                            String primaryHost,
                            String status,
                            String timezone,
                            String locale,
                            long configVersion,
                            int version) {
}
