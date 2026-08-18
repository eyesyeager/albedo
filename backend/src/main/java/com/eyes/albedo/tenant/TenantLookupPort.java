package com.eyes.albedo.tenant;

import java.util.Optional;

/**
 * 租户查询 SPI —— 由 @后端 在平台模块实现（读 {@code tenant_domains} JOIN {@code tenants}）。
 *
 * <p>骨架只定义契约：{@link TenantResolver} 负责缓存与规范化，本接口只负责"落库查询"，
 * 且<b>必须</b>是平台级查询（不依赖租户上下文，因为上下文正是由它建立的）。
 *
 * <p>实现要求：
 * <ul>
 *   <li>只返回 {@code tenant_domains.status='active'} 的绑定</li>
 *   <li>status 直接映射 {@code tenants.status}（draft/enabled/suspended/archived 全部返回，
 *       由上层按 PRD §5.1 决定 404/403/503 语义）</li>
 *   <li>{@code configVersion} 取 {@code tenants.config_version}</li>
 *   <li>🔴 禁止在此做任何"字符串截取猜测租户"的逻辑，只允许精确匹配</li>
 * </ul>
 */
public interface TenantLookupPort {

    /**
     * 按规范化 Host（小写、无端口、无末尾点）精确查询租户。
     */
    Optional<TenantContext.Snapshot> resolveByHost(String normalizedHost);

    /**
     * 按租户号精确查询租户（用于非生产环境的 dev host 映射）。
     */
    Optional<TenantContext.Snapshot> resolveByTenantId(String tenantId);
}
