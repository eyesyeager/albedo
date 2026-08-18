package com.eyes.albedo.audit;

/**
 * 审计作用域（architecture.md §11.1.2）。
 *
 * <p>🔴 存在意义 = AR-013 的防线：{@code audit_logs} 是<b>平台表</b>，不受 Hibernate
 * discriminator 自动填充保护。若调用方漏填 {@code tenantId}，租户维度审计完整率会 &lt;100%，
 * AC-AUD-001 直接失败。因此写入入参把 scope 与 tenantId 绑成一组强制参数：
 * {@code TENANT} 且 {@code tenantId} 为空 → {@link AuditWriter} 立即抛异常。
 */
public enum AuditScope {

    /** 平台事件：{@code tenant_id = NULL}（如缓存失效、排障票据签发）。 */
    PLATFORM("platform"),

    /** 租户事件：{@code tenant_id} 🔴 必填。 */
    TENANT("tenant");

    private final String value;

    AuditScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
