package com.eyes.albedo.platform.service;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.platform.dto.TenantProfile;
import com.eyes.albedo.platform.entity.Tenant;
import com.eyes.albedo.platform.repository.TenantRepository;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantResolver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户主数据服务（平台表 {@code tenants} 的唯一出口）。
 *
 * <p>对外只暴露只读视图 {@link TenantProfile} 与「切换配置版本指针」这一个写操作，
 * 避免其他模块拿到可变实体后绕开乐观锁或篡改租户身份。
 */
@Slf4j
@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final TenantResolver tenantResolver;

    public TenantService(TenantRepository tenantRepository, TenantResolver tenantResolver) {
        this.tenantRepository = tenantRepository;
        this.tenantResolver = tenantResolver;
    }

    /**
     * 当前请求上下文对应的租户主数据。
     *
     * @throws BusinessException 30010 / 30011 上下文缺失或租户不可用
     */
    @Transactional(readOnly = true)
    public TenantProfile currentProfile() {
        TenantContext.Snapshot snapshot = TenantContext.requireEnabled();
        return toProfile(load(snapshot.tenantId()));
    }

    /**
     * 按租户号读取主数据（平台任务用）。
     */
    @Transactional(readOnly = true)
    public TenantProfile profileOf(String tenantId) {
        return toProfile(load(tenantId));
    }

    /**
     * 原子切换「当前已发布站点配置版本」指针。
     *
     * <p>并发控制：以 {@code expectedVersion}（{@code tenants.version}）作为乐观锁令牌，
     * 不一致立即抛 {@code 30020}，🔴 绝不静默覆盖他人的发布结果（EX-012）。
     * 版本号自增由 JPA {@code @Version} 在提交时完成。
     *
     * @param tenantId        租户号
     * @param newConfigVersion 新的已发布版本号
     * @param expectedVersion  期望的乐观锁版本
     */
    @Transactional
    public void switchConfigVersion(String tenantId, long newConfigVersion, int expectedVersion) {
        Tenant tenant = load(tenantId);
        if (tenant.getVersion() == null || tenant.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "站点配置已被其他操作更新，请刷新后重试");
        }
        tenant.setConfigVersion(newConfigVersion);
        tenantRepository.saveAndFlush(tenant);
        // 租户解析缓存内含 configVersion，必须失效，否则新版本 30s 内不生效（PRD §8.2）
        // 🔴 Host 键与租户号键（dev 映射路径）必须同时失效，漏一个就会出现「发布了不生效」
        tenantResolver.evictHost(tenant.getPrimaryHost());
        tenantResolver.evictTenantId(tenantId);
        log.info("站点配置版本指针已切换：tenantId={} configVersion={}", tenantId, newConfigVersion);
    }

    private Tenant load(String tenantId) {
        return tenantRepository.findByTenantIdAndDeletedAtIsNull(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_NOT_FOUND));
    }

    private TenantProfile toProfile(Tenant tenant) {
        return new TenantProfile(
                tenant.getTenantId(),
                tenant.getName(),
                tenant.getPrimaryHost(),
                tenant.getStatus(),
                tenant.getTimezone(),
                tenant.getLocale(),
                tenant.getConfigVersion() == null ? 0L : tenant.getConfigVersion(),
                tenant.getVersion() == null ? 0 : tenant.getVersion());
    }
}
