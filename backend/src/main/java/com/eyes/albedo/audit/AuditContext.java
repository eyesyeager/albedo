package com.eyes.albedo.audit;

/**
 * 审计的请求上下文（requestId / 操作者 / 来源）。
 *
 * <p>与 {@link AuditEvent} 分离的理由：事件是"发生了什么"（由业务模块给出），
 * 上下文是"谁在哪次请求里做的"（可由 {@link AuditService} 从 MDC / 鉴权上下文 / HTTP 请求自动补齐）。
 * 分开后业务模块无需关心 Servlet，流式线程也能显式传入抓取好的上下文
 * （异步段读不到 Servlet 请求，architecture.md §6.5）。
 *
 * @param requestId MDC 的 {@code requestId}（异步段由 TenantAwareTaskDecorator 透传）
 * @param actorType {@link AuditActorTypes} 之一
 * @param actorId   操作者 uid（系统事件为 null）
 * @param ip        原始 IP（入库前按 /24、/48 截断）
 * @param userAgent 原始 UA（入库前截断并去 Token 片段）
 */
public record AuditContext(String requestId,
                           String actorType,
                           Long actorId,
                           String ip,
                           String userAgent) {

    public AuditContext {
        requestId = requestId == null ? "" : requestId;
        actorType = actorType == null ? AuditActorTypes.SYSTEM : actorType;
        ip = ip == null ? "" : ip;
        userAgent = userAgent == null ? "" : userAgent;
    }

    /** 系统事件（无人触发，如生成线程写"确认等待超时"）。 */
    public static AuditContext system() {
        return new AuditContext("", AuditActorTypes.SYSTEM, null, "", "");
    }

    /** 系统事件但保留 requestId（流式内的安全事件需要串联链路）。 */
    public static AuditContext system(String requestId) {
        return new AuditContext(requestId, AuditActorTypes.SYSTEM, null, "", "");
    }
}
