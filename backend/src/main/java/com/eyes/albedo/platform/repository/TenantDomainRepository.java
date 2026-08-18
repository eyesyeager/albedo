package com.eyes.albedo.platform.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.platform.entity.TenantDomain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Host 绑定仓储（platform scope）。
 *
 * <p>🔴 只允许按<b>完整规范化 Host</b> 精确匹配；禁止 like / 后缀匹配等猜测式查询。
 */
public interface TenantDomainRepository extends JpaRepository<TenantDomain, Long> {

    /**
     * 按规范化 Host 查询处于 active 状态的绑定。
     */
    Optional<TenantDomain> findByHostAndStatus(String host, String status);

    /**
     * 某租户的全部 Host 绑定（含 inactive）。
     *
     * <p>用途：缓存失效接口 {@code scope=tenant} 必须失效该租户<b>全部绑定 Host</b> 的解析键
     * （api-spec §7.2.1）。🔴 含 inactive 是有意的：刚被停用的绑定其缓存也必须清掉，
     * 否则它在 TTL 内仍会把请求解析到该租户。
     */
    List<TenantDomain> findByTenantId(String tenantId);

    /**
     * 按 Host 精确查询（不限状态），用于 {@code scope=host} 反查租户号以成对失效。
     */
    Optional<TenantDomain> findByHost(String host);
}
