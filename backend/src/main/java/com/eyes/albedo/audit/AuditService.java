package com.eyes.albedo.audit;

import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.eyes.eyesAuth.context.UserInfoHolder;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计门面：自动补齐请求上下文后委托 {@link AuditWriter}。
 *
 * <p>分工：
 * <ul>
 *   <li>{@link AuditWriter} —— 事务边界 + 强制校验 + 脱敏 + 落库（不感知 HTTP）</li>
 *   <li>本类 —— 从 MDC / {@link UserInfoHolder} / 当前 HTTP 请求抓取
 *       {@code requestId} / {@code actorType} / {@code actorId} / {@code ip} / {@code userAgent}</li>
 * </ul>
 *
 * <p>🔴 本类<b>不读取</b> {@code TenantContext}：{@code scope + tenantId} 必须由调用方显式给出
 * （AR-013）。理由是审计的租户维度不能依赖"当时上下文恰好是对的"——
 * 平台接口（如缓存失效）根本没有租户上下文，却要为<b>目标租户</b>留痕。
 *
 * <p>🔴 两个入口的语义差异与 {@link AuditWriter} 完全一致，此处只是转发，不改变事务边界。
 */
@Slf4j
@Service
public class AuditService {

    private final AuditWriter writer;
    private final AuditLogRepository repository;

    public AuditService(AuditWriter writer, AuditLogRepository repository) {
        this.writer = writer;
        this.repository = repository;
    }

    /**
     * 该对象上是否<b>已存在</b>同 action 的审计行（🔴 用于"至多一条"类防刷去重）。
     *
     * <p>唯一用途：api-spec §7.8.2 ⑤ 的 {@code tool.confirm_conflict} —— 同一 {@code toolCallId}
     * 至多写一条冲突审计。🔴 调用方必须在<b>行锁内</b>点查，否则并发提交仍可能写出两条。
     *
     * <p>🔴 为什么把它放在 {@code audit} 门面而不是让调用方注入 {@link AuditLogRepository}：
     * architecture.md §5.1.2 明令「任何包 ✗→ 别人的 Repository」，跨模块只允许 Service → Service。
     *
     * @param tenantId   🔴 必填：{@code audit_logs} 是平台表，缺条件会跨租户误判"已留痕"
     */
    public boolean alreadyRecorded(String tenantId, String action, String objectType,
                                  String objectId) {
        if (tenantId == null || tenantId.isBlank() || objectId == null) {
            return false;
        }
        return repository.countTenantObjectEvents(tenantId, action, objectType, objectId) > 0;
    }

    /**
     * 非流式安全/管理操作：<b>与业务同事务</b>，审计失败 → 整体失败（{@code 50003}，EX-024）。
     *
     * @return {@code eventId}（32 位小写 hex，对外原样返回）
     */
    public String record(AuditEvent event) {
        return writer.write(event, currentContext());
    }

    /**
     * 非流式安全/管理操作（<b>显式上下文</b>）：仍与业务同事务。
     *
     * <p>🔴 存在理由：部分事件契约要求 {@code actorType=system}
     * （如 {@code mcp.tool_grant_revoked} 是"系统主动撤销授权"，即便由管理员触发的 discover
     * 引起，动作主体也是系统，api-spec §7.4.3 G3 裁决），
     * 而 {@link #currentContext()} 会按当前登录身份判为 {@code endUser}。
     */
    public String record(AuditEvent event, AuditContext context) {
        return writer.write(event, context);
    }

    /**
     * 流式内安全事件：<b>独立短事务</b>；失败只使该次工具调用失败，
     * 🔴 <b>绝不中断 SSE 流</b>（调用方必须捕获 {@link AuditWriteException}）。
     */
    public String recordInNewTransaction(AuditEvent event) {
        return writer.writeInNewTransaction(event, currentContext());
    }

    /**
     * 流式内安全事件（异步线程显式传入上下文；异步段读不到 Servlet 请求）。
     */
    public String recordInNewTransaction(AuditEvent event, AuditContext context) {
        return writer.writeInNewTransaction(event, context);
    }

    /**
     * 抓取当前请求上下文。
     *
     * <p>无 HTTP 请求（异步线程 / 定时任务）时退化为 {@code system} + MDC requestId。
     */
    public AuditContext currentContext() {
        String requestId = MDC.get("requestId");
        Long uid = safeUid();
        String actorType = resolveActorType(uid);
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return new AuditContext(requestId, actorType, uid, "", "");
        }
        return new AuditContext(requestId, actorType, uid, clientIp(request),
                request.getHeader("User-Agent"));
    }

    /**
     * actor 类型判定：平台管理员由 eyesUser {@code role=ADMIN} 判定；
     * 租户内角色（tenantAdmin / tenantOperator）需要成员关系，属业务信息，
     * 🔴 由调用方在需要时通过 {@link AuditContext} 显式覆盖（audit 不得依赖业务包）。
     */
    private String resolveActorType(Long uid) {
        if (uid == null) {
            return AuditActorTypes.SYSTEM;
        }
        String role = safeRole();
        return AuthConfigConstant.ROLE_ADMIN.equals(role)
                ? AuditActorTypes.PLATFORM_ADMIN
                : AuditActorTypes.END_USER;
    }

    private Long safeUid() {
        try {
            return UserInfoHolder.getUid();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safeRole() {
        try {
            return UserInfoHolder.getRole();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 客户端 IP：优先 {@code X-Forwarded-For} 首个值（Nginx 反代部署）。
     *
     * <p>入库前由 {@link AuditSanitizer#ip(String)} 截断为 /24 或 /48。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
