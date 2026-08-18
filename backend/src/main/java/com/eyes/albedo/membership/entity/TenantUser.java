package com.eyes.albedo.membership.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户成员关系（{@code scope=tenant}，Hibernate {@code @TenantId} 自动隔离）。
 *
 * <p>身份唯一键是 eyesUser 的 {@code uid}（DEC-004）：
 * <ul>
 *   <li>🔴 全表<b>没有</b> password / phone / email 等本地认证字段——身份完全在 eyesUser</li>
 *   <li>{@code (tenant_id, uid)} 唯一 → 同一 uid 在不同租户是<b>互不影响</b>的两条关系（AC-AUTH-005）</li>
 *   <li>{@code profileSnapshot} 只存展示必需的昵称/头像，🔴 禁存完整手机号 / 邮箱 / token</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "tenant_users")
public class TenantUser extends BaseTenantEntity {

    @Column(name = "uid", nullable = false)
    private Long uid;

    /** TENANT_ADMIN / TENANT_OPERATOR / END_USER。 */
    @Column(name = "tenant_role", nullable = false, length = 32)
    private String tenantRole;

    /** active / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** JSON：{@code {"nickname":"…","avatarUrl":"…"}}。 */
    @Column(name = "profile_snapshot", columnDefinition = "json")
    private String profileSnapshot;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
