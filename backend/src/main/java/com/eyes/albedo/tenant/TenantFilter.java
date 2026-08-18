package com.eyes.albedo.tenant;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.eyes.eyesAuth.context.UserInfoHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;

/**
 * 租户上下文过滤器（**最高优先级**，先于一切鉴权与业务逻辑执行）。
 *
 * <p>职责：
 * <ol>
 *   <li>取 Host（可选信任 {@code X-Forwarded-Host}）→ 规范化 → 解析租户 → 绑定只读上下文</li>
 *   <li>忽略客户端传入的 {@code tenantId} 并记安全日志（EX-003，不返回错误、不暴露检测细节）</li>
 *   <li>写入 MDC：{@code requestId} / {@code tenantId}，保证全链路日志含租户维度（AC-TEN-005）</li>
 *   <li>finally 清理 ThreadLocal：{@link TenantContext} 与 {@link UserInfoHolder}</li>
 * </ol>
 *
 * <p>🔴 本过滤器<b>不写响应体</b>：解析失败时不绑定上下文，由业务层
 * {@link TenantContext#requireEnabled()} 抛出 30010 / 30011，经全局异常处理统一返回 HTTP 200 + code。
 * 站点级非 200 仅由 {@code GET /site/status} 承担（ADR-005）。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    /** 平台级白名单：不需要租户上下文即可访问。 */
    private static final List<String> TENANT_FREE_PREFIXES = List.of(
            "/api/v1/sys-config",
            "/api/v1/platform",
            "/actuator"
    );

    /** 客户端伪造检测：出现这些参数/请求头一律忽略并记安全日志。 */
    private static final List<String> FORGERY_PARAM_NAMES = List.of("tenantId", "tenant_id", "tenant");
    private static final List<String> FORGERY_HEADER_NAMES = List.of("X-Tenant-Id", "X-Tenant");

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TENANT_ID = "tenantId";
    public static final String MDC_UID = "uid";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_FORWARDED_HOST = "X-Forwarded-Host";

    private final TenantResolver tenantResolver;
    private final ConfigService configService;

    public TenantFilter(TenantResolver tenantResolver, ConfigService configService) {
        this.tenantResolver = tenantResolver;
        this.configService = configService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(HEADER_REQUEST_ID, requestId);
        try {
            String rawHost = TenantResolver.normalizeRawHost(resolveHost(request));
            detectForgery(request, rawHost);

            if (!isTenantFree(request.getRequestURI())) {
                Optional<TenantContext.Snapshot> snapshot = tenantResolver.resolve(rawHost);
                if (snapshot.isPresent()) {
                    TenantContext.bind(snapshot.get());
                    MDC.put(MDC_TENANT_ID, snapshot.get().tenantId());
                } else {
                    log.debug("未能解析租户，交由业务层返回 30010：host={} uri={}", rawHost, request.getRequestURI());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            UserInfoHolder.removeAll();
            MDC.remove(MDC_TENANT_ID);
            MDC.remove(MDC_UID);
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /**
     * 站点级状态端点与业务接口都需要租户解析，静态资源与平台白名单不需要。
     */
    private boolean isTenantFree(String uri) {
        if (uri == null) {
            return true;
        }
        return TENANT_FREE_PREFIXES.stream().anyMatch(uri::startsWith);
    }

    private String resolveHost(HttpServletRequest request) {
        boolean trustForwarded = configService.getBoolean(ConfigKeys.GROUP_TENANT,
                ConfigKeys.TRUST_FORWARDED_HOST, false);
        if (trustForwarded) {
            String forwarded = request.getHeader(HEADER_FORWARDED_HOST);
            if (forwarded != null && !forwarded.isBlank()) {
                // 可能是逗号分隔链，取第一个（最接近客户端的代理写入值）
                return forwarded.split(",")[0].trim();
            }
        }
        String host = request.getHeader("Host");
        return (host == null || host.isBlank()) ? request.getServerName() : host;
    }

    /**
     * 客户端伪造 tenantId 检测：一律忽略 + 安全日志，业务响应仍为正常结果（EX-003 / AC-TEN-002）。
     */
    private void detectForgery(HttpServletRequest request, String rawHost) {
        for (String param : FORGERY_PARAM_NAMES) {
            if (request.getParameter(param) != null) {
                logForgery("param", param, rawHost, request);
                return;
            }
        }
        for (String header : FORGERY_HEADER_NAMES) {
            if (request.getHeader(header) != null) {
                logForgery("header", header, rawHost, request);
                return;
            }
        }
    }

    private void logForgery(String source, String name, String rawHost, HttpServletRequest request) {
        log.warn("[SECURITY] 忽略客户端传入的租户标识：source={} name={} host={} uri={}",
                source, name, rawHost, request.getRequestURI());
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_REQUEST_ID);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= 64) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
