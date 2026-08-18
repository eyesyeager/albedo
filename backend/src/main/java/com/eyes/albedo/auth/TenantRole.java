package com.eyes.albedo.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 租户内角色校验注解（叠加在 {@code @Permission(PermissionEnum.USER)} 之上）。
 *
 * <p>执行顺序：{@code TenantFilter} → {@code PermissionAspect}(@Order 10) →
 * {@code TenantRoleAspect}(@Order 20) → Controller。
 *
 * <p>用法：
 * <pre>
 * &#64;Permission(PermissionEnum.USER)
 * &#64;TenantRole({TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR})
 * &#64;PostMapping("/api/v1/admin/site/config/publish")
 * public Result&lt;Void&gt; publish() { ... }
 * </pre>
 *
 * <p>校验不通过 → {@code 10003 PERMISSION_DENIED}（已登录但业务权限不足）。
 * 🔴 前端隐藏入口不能替代本注解，后端是最终准入依据（PRD §8.9）。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantRole {

    /**
     * 允许访问的租户内角色（任一命中即通过）。
     */
    TenantRoleEnum[] value();
}
