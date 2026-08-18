package com.eyes.albedo.site.dto;

/**
 * 站点配置版本元信息（版本列表 / 回滚选择用）。
 *
 * @param version     版本号
 * @param status      draft / published / archived
 * @param current     是否为当前生效版本（{@code tenants.config_version} 指向）
 * @param publishedBy 发布人 uid（string，可空）
 * @param publishedAt 发布时间（ISO-8601 UTC，可空）
 * @param createdAt   创建时间（ISO-8601 UTC）
 */
public record SiteConfigVersionDTO(long version,
                                   String status,
                                   boolean current,
                                   String publishedBy,
                                   String publishedAt,
                                   String createdAt) {
}
