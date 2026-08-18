package com.eyes.albedo.site.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 站点配置版本（{@code scope=tenant}）。
 *
 * <p>版本模型（架构 §10）：
 * <ul>
 *   <li>{@code published} 行<b>不可变</b>：再次编辑只能生成新的 {@code draft}</li>
 *   <li>回滚 = 以历史内容<b>生成新版本</b>并切指针，🔴 不修改历史行（AC-CFG-002）</li>
 *   <li>线上读取的版本由 {@code tenants.config_version} 指针决定，不靠"最大版本号"猜测</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "site_config_versions")
public class SiteConfigVersion extends BaseTenantEntity {

    /** 租户内递增版本号，从 1 开始。 */
    @Column(name = "version", nullable = false)
    private Long version;

    /** draft / published / archived。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 站点配置全量快照（JSON）。 */
    @Column(name = "content", nullable = false, columnDefinition = "json")
    private String content;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";
}
