package com.eyes.albedo.tenant;

/**
 * 租户状态（PRD §10.1）。
 *
 * <p>这是权限与可用性模型的一部分（不是可变业务配置），因此以枚举固化在代码中。
 */
public enum TenantStatus {

    /** 创建后默认：对外站点级 404，不提供任何业务能力。 */
    DRAFT,

    /** 启用校验通过：正常服务。 */
    ENABLED,

    /** 平台管理员暂停：对外站点级 403，阻断全部租户业务访问。 */
    SUSPENDED,

    /** 已归档：站点级 404，不可恢复，仅平台审计可见。 */
    ARCHIVED;

    public static TenantStatus of(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        return switch (value.trim().toLowerCase()) {
            case "enabled" -> ENABLED;
            case "suspended" -> SUSPENDED;
            case "archived" -> ARCHIVED;
            default -> DRAFT;
        };
    }

    public String code() {
        return name().toLowerCase();
    }

    public boolean isEnabled() {
        return this == ENABLED;
    }
}
