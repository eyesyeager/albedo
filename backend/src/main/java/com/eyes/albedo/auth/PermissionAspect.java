package com.eyes.albedo.auth;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantFilter;
import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.eyes.eyesAuth.constant.AuthTypeConstant;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;
import com.eyes.eyesAuth.starter.EyesAuthProperties;
import com.eyes.eyesAuth.thrift.TTClientPool;
import com.eyes.eyesAuth.thrift.config.TTSocket;
import com.eyes.eyesAuth.thrift.generate.auth.AuthNeverExpireReturnee;
import com.eyes.eyesAuth.thrift.generate.auth.AuthSingleReturnee;
import com.eyes.eyesAuth.thrift.generate.common.TTCustomException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 耶瞳鉴权切面（<b>Spring Boot 3 / jakarta 版</b>，ADR-002）。
 *
 * <p>为什么需要它：starter 1.1.0 编译于 Spring Boot 2.6.15，其 {@code PermissionAdvice} 通过
 * {@code WebHelper} 使用 {@code javax.servlet.*}，在 Spring Boot 3（jakarta）下必然链接失败；
 * 且其自动配置只注册在 {@code META-INF/spring.factories}，Boot 3 已不再从该文件加载自动配置。
 * 因此本项目<b>复用 starter 的注解 / 常量 / Thrift 客户端 / UserInfoHolder</b>，
 * 但由本切面复刻鉴权语义，保证与《耶瞳用户中心接入文档》100% 一致。
 *
 * <p>行为：
 * <ol>
 *   <li>读取请求头 {@code authorization}</li>
 *   <li>按 {@code eyes-auth.auth-type} 调用 Thrift 校验（本项目为 1 = 单 token）</li>
 *   <li>成功：写入 {@link UserInfoHolder}、回填 {@link TenantContext} 的 uid、
 *       并把新 token 写入响应头 {@code authorization}（前端必须回写 localStorage）</li>
 *   <li>成功且存在租户上下文：触发惰性建户 {@link TenantMembershipPort#ensureMembership(long)}</li>
 *   <li>失败：抛 {@link AuthCodeException}，code 原样透传 eyesUser 的 20000~20008</li>
 * </ol>
 *
 * <p>🔴 本项目没有任何自建登录 / 注册 / 登出 / 刷新令牌接口；鉴权失效由前端整页跳转 SSO。
 */
@Slf4j
@Aspect
@Order(10)
public class PermissionAspect {

    private final EyesAuthProperties properties;
    private final TTClientPool clientPool;
    private final ObjectProvider<TenantMembershipPort> membershipProvider;

    public PermissionAspect(EyesAuthProperties properties,
                            TTClientPool clientPool,
                            ObjectProvider<TenantMembershipPort> membershipProvider) {
        this.properties = properties;
        this.clientPool = clientPool;
        this.membershipProvider = membershipProvider;
    }

    @Before("@annotation(com.eyes.eyesAuth.permission.Permission) "
            + "|| @within(com.eyes.eyesAuth.permission.Permission)")
    public void before(JoinPoint joinPoint) {
        PermissionEnum required = resolveRequired(joinPoint);
        if (required == PermissionEnum.NO) {
            return;
        }

        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName();
        HttpServletRequest request = currentRequest();
        log.info("[PermissionAspect] 鉴权开始 method={} required={} path={}",
                method, required, request != null ? request.getRequestURI() : "-");

        HttpServletRequest req = currentRequest();
        String token = req.getHeader(AuthConfigConstant.HEADER_TOKEN);
        if (token == null || token.isBlank()) {
            log.warn("[PermissionAspect] 鉴权失败：缺少身份凭据 method={} path={}",
                    method, req != null ? req.getRequestURI() : "-");
            throw new AuthCodeException(ErrorCode.AUTH_TOKEN_INVALID, "缺少身份凭据");
        }

        AuthResult authResult = verify(token);
        if (required == PermissionEnum.ADMIN && !AuthConfigConstant.ROLE_ADMIN.equals(authResult.role())) {
            log.warn("[PermissionAspect] 权限不足：需要 ADMIN，实际 role={} uid={}",
                    authResult.role(), authResult.uid());
            throw new AuthCodeException(ErrorCode.AUTH_FORBIDDEN, "权限不足");
        }

        UserInfoHolder.setUserInfo(authResult.uid(), authResult.role());
        TenantContext.bindUid(authResult.uid());
        MDC.put(TenantFilter.MDC_UID, String.valueOf(authResult.uid()));
        log.info("[PermissionAspect] 鉴权成功 uid={} role={} method={}",
                authResult.uid(), authResult.role(), method);

        // auth-type=1（单 token）：每次校验都会签发新 token，必须通过响应头下发（ADR-007）
        if (authResult.newToken() != null && !authResult.newToken().isBlank()) {
            HttpServletResponse response = currentResponse();
            if (response != null && !response.isCommitted()) {
                response.setHeader(AuthConfigConstant.HEADER_TOKEN, authResult.newToken());
                log.info("[PermissionAspect] 已下发续期 token uid={}", authResult.uid());
            }
        }

        ensureMembership(authResult.uid());
    }

    /**
     * 惰性建户（PRD §6.2.4）：仅在存在租户上下文时执行；平台级白名单接口无租户上下文，跳过。
     */
    private void ensureMembership(long uid) {
        if (TenantContext.current().isEmpty()) {
            return;
        }
        TenantMembershipPort port = membershipProvider.getIfAvailable();
        if (port == null) {
            log.warn("TenantMembershipPort 尚未实现（骨架阶段），跳过惰性建户：uid={}", uid);
            return;
        }
        port.ensureMembership(uid);
    }

    private AuthResult verify(String token) {
        String appId = properties.getAppId();
        int authType = properties.getAuthType() == null
                ? AuthTypeConstant.SINGLE : properties.getAuthType();
        TTSocket socket = null;
        try {
            socket = clientPool.getConnect();
            if (authType == AuthTypeConstant.NEVER_EXPIRED) {
                AuthNeverExpireReturnee returnee =
                        socket.getAuthClient().checkAuthByNeverExpire(appId, token);
                return new AuthResult(returnee.getUid(), returnee.getRole(), null);
            }
            AuthSingleReturnee returnee = socket.getAuthClient().checkAuthBySingle(appId, token);
            return new AuthResult(returnee.getUid(), returnee.getRole(), returnee.getToken());
        } catch (TTCustomException e) {
            clientPool.returnConnection(socket);
            socket = null;
            int code = ErrorCode.isAuthSegment(e.getCode()) ? e.getCode() : ErrorCode.AUTH_TOKEN_INVALID;
            throw new AuthCodeException(code, e.getMsg());
        } catch (AuthCodeException e) {
            throw e;
        } catch (Exception e) {
            if (socket != null) {
                clientPool.invalidateObject(socket);
                socket = null;
            }
            log.error("调用 eyesUser 鉴权服务失败", e);
            // 上游不可用不等于凭据无效：以 50002 暴露，避免把用户错误清退（EX-008）
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "鉴权服务暂不可用");
        } finally {
            if (socket != null && socket.isOpen()) {
                clientPool.returnConnection(socket);
            }
        }
    }

    private PermissionEnum resolveRequired(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Permission permission = signature.getMethod().getAnnotation(Permission.class);
        if (permission == null && joinPoint.getTarget() != null) {
            permission = joinPoint.getTarget().getClass().getAnnotation(Permission.class);
        }
        return permission == null ? PermissionEnum.NO : permission.value();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = attributes();
        if (attributes == null) {
            throw new AuthCodeException(ErrorCode.AUTH_PARAM_ILLEGAL, "非 HTTP 请求上下文无法鉴权");
        }
        return attributes.getRequest();
    }

    private HttpServletResponse currentResponse() {
        ServletRequestAttributes attributes = attributes();
        return attributes == null ? null : attributes.getResponse();
    }

    private ServletRequestAttributes attributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes : null;
    }

    /**
     * 鉴权校验结果。
     *
     * @param uid      eyesUser uid
     * @param role     eyesUser 角色（{@code ROLE_admin} / {@code ROLE_user}）
     * @param newToken 续期后的新 token（auth-type=1 时非空）
     */
    private record AuthResult(long uid, String role, String newToken) {
    }
}
