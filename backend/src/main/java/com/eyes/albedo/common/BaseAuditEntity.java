package com.eyes.albedo.common;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/**
 * 实体基类：主键 + 创建/更新时间（UTC）。
 *
 * <p>数据模型约定（docs/architecture.md §13.2）：
 * <ul>
 *   <li>主键 BIGINT AUTO_INCREMENT；🔴 对外 JSON 序列化为 string（DTO 层转换，ADR-004）</li>
 *   <li>时间列统一 {@code DATETIME(3)}，Java 侧用 {@link Instant}，存储 UTC
 *       （{@code hibernate.jdbc.time_zone=UTC}）</li>
 * </ul>
 *
 * <p>平台级表（tenants / tenant_domains / sys_config / local_tools / audit_logs …）直接继承本类；
 * 租户级表必须继承 {@code com.eyes.albedo.tenant.BaseTenantEntity}。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
