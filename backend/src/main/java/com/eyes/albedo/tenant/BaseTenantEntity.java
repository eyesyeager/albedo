package com.eyes.albedo.tenant;

import org.hibernate.annotations.TenantId;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户级实体基类（方案 D：同库同表 + Hibernate 6 原生 discriminator 多租户）。
 *
 * <p>继承本类后，Hibernate 会<b>自动</b>：
 * <ul>
 *   <li>INSERT 时填充 {@code tenant_id}（取 {@link TenantIdentifierResolver} 的解析值）</li>
 *   <li>SELECT / UPDATE / DELETE（含 {@code findById}、派生查询、JPQL、Criteria）自动追加
 *       {@code tenant_id = ?} 条件</li>
 * </ul>
 *
 * <p>🔴 纪律：
 * <ul>
 *   <li>平台级表（tenants / tenant_domains / sys_config / local_tools / audit_logs /
 *       platform_access_grants）<b>不得</b>继承本类，必须在 architecture.md §6.4 标注 {@code scope=platform}
 *       并在需要时手写租户条件</li>
 *   <li>租户级表的业务唯一键<b>必须包含 tenant_id</b>（保证 AC-TEN-003）</li>
 *   <li>🔴 禁止对租户级表使用原生 SQL（{@code nativeQuery=true}）——Hibernate 不会为其追加租户条件</li>
 *   <li>{@code tenantId} 由框架维护，业务代码禁止 set</li>
 * </ul>
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseTenantEntity extends BaseAuditEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 32)
    private String tenantId;
}
