package com.eyes.albedo.audit;

import java.time.Instant;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 运行时安全事件审计（🔴 {@code scope=platform}，architecture.md §13.5.1，<b>仅追加</b>）。
 *
 * <p>为什么<b>不</b>继承 {@code BaseTenantEntity}（§6.4 已逐表登记）：平台事件的
 * {@code tenant_id} 为 {@code NULL}，而 Hibernate {@code @TenantId} 会强制填充并追加租户条件，
 * 导致平台审计<b>既写不进也查不出</b>；且平台侧需要跨租户审计查询。
 * 🔴 代价：租户维度查询<b>必须手写</b> {@code where tenant_id = :tenantId}，
 * 写入时<b>必须显式传入</b> {@code scope + tenantId}（AR-013 的防线在 {@link AuditWriter}）。
 *
 * <p>🔴 不可篡改（AC-AUD-001）：
 * <ul>
 *   <li>本实体全部业务字段 {@code updatable = false}</li>
 *   <li>{@link AuditLogRepository} <b>不提供</b> update / delete 方法</li>
 *   <li>租户角色没有任何修改入口（一期连查询接口都没有，核验走 SQL）</li>
 * </ul>
 *
 * <p>字段填写口径与 🔴 六类禁记清单见 architecture.md §11.1.2 与 {@link AuditSanitizer}。
 */
@Getter
@Setter
@Entity
@Table(name = "audit_logs")
public class AuditLog extends BaseAuditEntity {

    /** 32 位 UUID hex（小写无连字符）；对外 {@code auditEventId} 🔴 原样返回，禁止截断。 */
    @Column(name = "event_id", nullable = false, updatable = false, length = 32)
    private String eventId;

    /** {@code platform} / {@code tenant}。 */
    @Column(name = "scope", nullable = false, updatable = false, length = 20)
    private String scope;

    /** 🔴 {@code scope=tenant} 时必填；platform 事件为 null。 */
    @Column(name = "tenant_id", updatable = false, length = 32)
    private String tenantId;

    @Column(name = "request_id", nullable = false, updatable = false, length = 64)
    private String requestId = "";

    /** {@code endUser}/{@code tenantAdmin}/{@code tenantOperator}/{@code platformAdmin}/{@code system}。 */
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private String actorType = AuditActorTypes.SYSTEM;

    /** 操作者 uid（数值；🔴 禁存昵称 / 手机号）。 */
    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    /** {@link AuditActions} 的 10 项枚举之一。 */
    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    @Column(name = "object_type", nullable = false, updatable = false, length = 32)
    private String objectType = "";

    @Column(name = "object_id", nullable = false, updatable = false, length = 64)
    private String objectId = "";

    /** 🔴 只允许 sha256 前 16 hex / changed / unchanged / 脱敏摘要。 */
    @Column(name = "before_digest", nullable = false, updatable = false, length = 64)
    private String beforeDigest = "";

    @Column(name = "after_digest", nullable = false, updatable = false, length = 64)
    private String afterDigest = "";

    /** {@code success} / {@code failed} / {@code denied}。 */
    @Column(name = "result", nullable = false, updatable = false, length = 20)
    private String result;

    @Column(name = "reason", nullable = false, updatable = false, length = 200)
    private String reason = "";

    /** 🔴 只允许已登记数字业务码，禁止字符串码。 */
    @Column(name = "error_code", updatable = false)
    private Integer errorCode;

    @Column(name = "ip", nullable = false, updatable = false, length = 64)
    private String ip = "";

    @Column(name = "user_agent", nullable = false, updatable = false, length = 200)
    private String userAgent = "";

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
