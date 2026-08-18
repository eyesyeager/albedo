package com.eyes.albedo.auth;

/**
 * 租户内角色（本地成员关系，PRD §3.2）。
 *
 * <p>🔴 与 eyesUser 的平台角色严格分离：
 * <ul>
 *   <li>平台管理员 = eyesUser {@code role=ADMIN} → 用 {@code @Permission(PermissionEnum.ADMIN)}</li>
 *   <li>租户内角色 = {@code tenant_users.tenant_role} → 用 {@link TenantRole}</li>
 *   <li>eyesUser ADMIN <b>不自动获得</b>任何租户内角色（AC-AUTH-007）</li>
 * </ul>
 */
public enum TenantRoleEnum {

    /** 租户管理员：本租户全部治理能力，含成员角色管理与 Tool 授权。 */
    TENANT_ADMIN,

    /** 租户运营：文案与 AI 能力资源管理、查看审计；不可管理成员角色。 */
    TENANT_OPERATOR,

    /** 终端用户：使用已发布 Agent、管理本人会话（惰性建户默认角色）。 */
    END_USER;

    public static TenantRoleEnum of(String value) {
        if (value == null || value.isBlank()) {
            return END_USER;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return END_USER;
        }
    }
}
