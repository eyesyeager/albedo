package com.eyes.albedo.audit;

/**
 * 一条待写入的审计事件（不可变入参）。
 *
 * <p>🔴 {@code scope} 与 {@code tenantId} 是<b>成组强制参数</b>（AR-013）：
 * {@code scope=TENANT} 且 {@code tenantId} 为空时 {@link AuditWriter} 立即抛
 * {@link AuditWriteException}，绝不"先写进去再说"。
 *
 * <p>🔴 {@code beforeDigest} / {@code afterDigest} 只能是 {@link AuditDigest} 产出的形态；
 * 传入疑似明文会被 {@link AuditSanitizer#digestColumn(String)} 强制摘要化并记 WARN。
 *
 * @param scope       platform / tenant
 * @param tenantId    scope=tenant 时必填；platform 事件必须为 null
 * @param action      {@link AuditActions} 已登记的 10 项之一
 * @param result      {@link AuditResults} 之一
 * @param objectType  对象类型，如 {@code toolCall} / {@code mcpServer} / {@code cacheScope}
 * @param objectId    对象标识（可为非数值，如 cacheScope 的 host）
 * @param beforeDigest 变更前摘要（无则空串）
 * @param afterDigest  变更后摘要（无则空串）
 * @param reason      原因（≤200 字符，入库前脱敏）
 * @param errorCode   已登记数字业务码，无则 null
 */
public record AuditEvent(AuditScope scope,
                         String tenantId,
                         String action,
                         String result,
                         String objectType,
                         String objectId,
                         String beforeDigest,
                         String afterDigest,
                         String reason,
                         Integer errorCode) {

    /**
     * 平台事件（{@code tenant_id = NULL}）。
     */
    public static AuditEvent platform(String action, String result,
                                      String objectType, String objectId,
                                      String reason, Integer errorCode) {
        return new AuditEvent(AuditScope.PLATFORM, null, action, result,
                objectType, objectId, "", "", reason, errorCode);
    }

    /**
     * 租户事件（🔴 {@code tenantId} 必填）。
     */
    public static AuditEvent tenant(String tenantId, String action, String result,
                                    String objectType, String objectId,
                                    String reason, Integer errorCode) {
        return new AuditEvent(AuditScope.TENANT, tenantId, action, result,
                objectType, objectId, "", "", reason, errorCode);
    }

    public AuditEvent withDigests(String before, String after) {
        return new AuditEvent(scope, tenantId, action, result, objectType, objectId,
                before, after, reason, errorCode);
    }
}
