package com.eyes.albedo.membership.repository;

import java.util.Optional;

import com.eyes.albedo.membership.entity.TenantUser;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 租户成员仓储（tenant scope）。
 *
 * <p>🔴 无需（也不得）手写 {@code tenant_id} 条件：Hibernate discriminator 会自动追加，
 * 因此 {@code findByUid} 在语义上就是「当前租户内的这个 uid」。
 */
public interface TenantUserRepository extends JpaRepository<TenantUser, Long> {

    Optional<TenantUser> findByUid(Long uid);
}
