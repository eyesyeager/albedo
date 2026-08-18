package com.eyes.albedo.auth;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.context.UserInfoHolder;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;

/**
 * 租户内角色校验切面（在 {@link PermissionAspect} 之后执行）。
 *
 * <p>判定链：{@code @Permission} 确认"是谁"（eyesUser 身份）→ 本切面确认"在本租户能做什么"
 * （{@code tenant_users.tenant_role}）。
 *
 * <p>🔴 eyesUser {@code role=ADMIN}（平台管理员）<b>不自动获得</b>租户内角色（AC-AUTH-007）；
 * 平台管理员访问租户数据必须走受控跨租户授权（M2 {@code platform_access_grants}）。
 *
 * <p>校验不通过 → {@code 10003}。
 */
@Slf4j
@Aspect
@Order(20)
public class TenantRoleAspect {

    private final ObjectProvider<TenantMembershipPort> membershipProvider;

    public TenantRoleAspect(ObjectProvider<TenantMembershipPort> membershipProvider) {
        this.membershipProvider = membershipProvider;
    }

    @Before("@annotation(com.eyes.albedo.auth.TenantRole) || @within(com.eyes.albedo.auth.TenantRole)")
    public void before(JoinPoint joinPoint) {
        TenantRole annotation = resolveAnnotation(joinPoint);
        if (annotation == null || annotation.value().length == 0) {
            return;
        }
        // 必须已建立租户上下文（30010/30011）与登录身份
        TenantContext.requireEnabled();
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            throw new AuthCodeException(ErrorCode.AUTH_TOKEN_INVALID, "缺少身份凭据");
        }

        TenantMembershipPort port = membershipProvider.getIfAvailable();
        if (port == null) {
            // 骨架阶段缺少实现：拒绝而非放行（fail-closed，避免越权）
            log.error("TenantMembershipPort 未实现，无法校验租户内角色，按拒绝处理：uid={}", uid);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        TenantRoleEnum actual = port.ensureMembership(uid);
        Set<TenantRoleEnum> allowed = Arrays.stream(annotation.value()).collect(Collectors.toSet());
        if (!allowed.contains(actual)) {
            log.warn("租户内角色不足：uid={} actual={} allowed={}", uid, actual, allowed);
            throw BusinessException.permissionDenied("无权执行该操作");
        }
    }

    private TenantRole resolveAnnotation(JoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        TenantRole annotation = signature.getMethod().getAnnotation(TenantRole.class);
        if (annotation == null && joinPoint.getTarget() != null) {
            annotation = joinPoint.getTarget().getClass().getAnnotation(TenantRole.class);
        }
        return annotation;
    }
}
