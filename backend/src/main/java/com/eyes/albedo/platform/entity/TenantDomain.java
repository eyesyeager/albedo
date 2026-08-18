package com.eyes.albedo.platform.entity;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Host ↔ 租户绑定（🔴 {@code scope=platform}）。
 *
 * <p>{@code host} 必须是规范化值：小写、<b>无端口</b>、无末尾点，全局唯一（{@code uk_host}）。
 * 解析只允许<b>精确匹配</b>，禁止任何字符串截取猜测（PRD §5.1.2）。
 */
@Getter
@Setter
@Entity
@Table(name = "tenant_domains")
public class TenantDomain extends BaseAuditEntity {

    @Column(name = "host", nullable = false, length = 253)
    private String host;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId;

    @Column(name = "is_primary", nullable = false, columnDefinition = "tinyint")
    private Integer isPrimary = 1;

    /** active / inactive。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;
}
