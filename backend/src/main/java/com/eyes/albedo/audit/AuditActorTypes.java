package com.eyes.albedo.audit;

/**
 * 审计 {@code actor_type} 字面量（architecture.md §11.1.2）。
 *
 * <p>{@code actor_id} 一律是 eyesUser uid（数值），🔴 禁存昵称 / 手机号 / 邮箱。
 */
public final class AuditActorTypes {

    private AuditActorTypes() {
    }

    /** 终端用户。 */
    public static final String END_USER = "endUser";
    /** 租户管理员。 */
    public static final String TENANT_ADMIN = "tenantAdmin";
    /** 租户运营。 */
    public static final String TENANT_OPERATOR = "tenantOperator";
    /** 平台管理员（eyesUser role=ADMIN）。 */
    public static final String PLATFORM_ADMIN = "platformAdmin";
    /** 系统自身（无人触发，如生成线程写确认超时）。 */
    public static final String SYSTEM = "system";
}
