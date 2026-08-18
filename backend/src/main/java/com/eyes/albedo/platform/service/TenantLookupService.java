package com.eyes.albedo.platform.service;

import java.util.Optional;

import com.eyes.albedo.platform.entity.Tenant;
import com.eyes.albedo.platform.entity.TenantDomain;
import com.eyes.albedo.platform.repository.TenantDomainRepository;
import com.eyes.albedo.platform.repository.TenantRepository;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantLookupPort;
import com.eyes.albedo.tenant.TenantStatus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantLookupPort} 实现：{@code tenant_domains} → {@code tenants} 的落库解析。
 *
 * <p>职责边界（缓存与规范化在 {@code TenantResolver}，此处只做查询）：
 * <ul>
 *   <li>只接受<b>已规范化</b>的 Host（小写、无端口、无末尾点），只做精确匹配</li>
 *   <li>只认 {@code tenant_domains.status='active'} 的绑定</li>
 *   <li>租户 status 原样返回（draft/enabled/suspended/archived），
 *       由 {@code TenantContext.requireEnabled()} 与 {@code /site/status} 决定 404/403/503 语义</li>
 *   <li>软删除租户视为不存在</li>
 * </ul>
 */
@Slf4j
@Service
public class TenantLookupService implements TenantLookupPort {

    private static final String DOMAIN_STATUS_ACTIVE = "active";

    private final TenantDomainRepository domainRepository;
    private final TenantRepository tenantRepository;

    public TenantLookupService(TenantDomainRepository domainRepository,
                               TenantRepository tenantRepository) {
        this.domainRepository = domainRepository;
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantContext.Snapshot> resolveByHost(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isBlank()) {
            return Optional.empty();
        }
        Optional<TenantDomain> domain =
                domainRepository.findByHostAndStatus(normalizedHost, DOMAIN_STATUS_ACTIVE);
        if (domain.isEmpty()) {
            return Optional.empty();
        }
        return loadTenant(domain.get().getTenantId(), normalizedHost);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantContext.Snapshot> resolveByTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return loadTenant(tenantId, null);
    }

    private Optional<TenantContext.Snapshot> loadTenant(String tenantId, String matchedHost) {
        return tenantRepository.findByTenantIdAndDeletedAtIsNull(tenantId)
                .map(tenant -> toSnapshot(tenant, matchedHost));
    }

    private TenantContext.Snapshot toSnapshot(Tenant tenant, String matchedHost) {
        return new TenantContext.Snapshot(
                tenant.getTenantId(),
                tenant.getId(),
                matchedHost == null ? tenant.getPrimaryHost() : matchedHost,
                TenantStatus.of(tenant.getStatus()),
                tenant.getConfigVersion() == null ? 0L : tenant.getConfigVersion(),
                null);
    }
}
