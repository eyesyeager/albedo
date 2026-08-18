package com.eyes.albedo.platform.entity;

import java.time.Instant;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户注册表（🔴 {@code scope=platform}，不继承 {@code BaseTenantEntity}）。
 *
 * <p>为什么是平台表：{@code tenant_id} 在这里是<b>主数据</b>而非隔离维度；
 * 且 Host 解析发生在租户上下文<b>建立之前</b>，若继承租户基类将永远查不到数据（自锁死）。
 *
 * <p>{@code configVersion} 是「当前已发布站点配置版本指针」，发布 = 原子更新本字段（架构 §10）。
 * {@code version} 为 JPA 乐观锁，发布并发冲突映射为 {@code 30020}。
 */
@Getter
@Setter
@Entity
@Table(name = "tenants")
public class Tenant extends BaseAuditEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 32)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "primary_host", nullable = false, length = 253)
    private String primaryHost;

    /** draft / enabled / suspended / archived。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    /** 当前已发布站点配置版本，0 = 未发布。 */
    @Column(name = "config_version", nullable = false)
    private Long configVersion = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
