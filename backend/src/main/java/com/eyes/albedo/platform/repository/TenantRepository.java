package com.eyes.albedo.platform.repository;

import java.util.Optional;

import com.eyes.albedo.platform.entity.Tenant;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 租户仓储（platform scope，无 Hibernate 租户条件）。
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /**
     * 按租户号查询未删除的租户。
     */
    Optional<Tenant> findByTenantIdAndDeletedAtIsNull(String tenantId);
}
