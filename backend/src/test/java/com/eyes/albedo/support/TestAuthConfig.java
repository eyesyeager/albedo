package com.eyes.albedo.support;

import com.eyes.albedo.auth.TenantMembershipPort;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.eyes.eyesAuth.context.UserInfoHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 测试专用鉴权桩（<b>仅存在于 src/test，不进生产 jar</b>）。
 *
 * <p>为什么需要：真实鉴权走 eyesUser Thrift（{@code PermissionAspect}），
 * 接口测试不应依赖外部账号中心可用性与真实 token。测试 profile 下
 * {@code eyes-auth.enabled=false} 使切面不装配，由本拦截器复刻其<b>全部副作用</b>：
 * 写 {@link UserInfoHolder}、回填 {@link TenantContext} 的 uid、触发惰性建户。
 *
 * <p>🔴 用 {@code HandlerInterceptor} 而不是 {@code Filter}：惰性建户可能抛
 * {@code BusinessException(10003)}（成员被禁用），只有在 MVC 内部抛出才会被
 * {@code GlobalExceptionHandler} 映射为「HTTP 200 + code=10003」——
 * 在 Filter 里抛会直接变成 500，测出来的就不是真实契约行为了。
 */
@TestConfiguration
public class TestAuthConfig implements WebMvcConfigurer {

    public static final String HEADER_TEST_UID = "X-Test-Uid";
    public static final String HEADER_TEST_ROLE = "X-Test-Role";

    private final ObjectProvider<TenantMembershipPort> membershipProvider;

    public TestAuthConfig(ObjectProvider<TenantMembershipPort> membershipProvider) {
        this.membershipProvider = membershipProvider;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull Object handler) {
                String uidHeader = request.getHeader(HEADER_TEST_UID);
                if (uidHeader == null || uidHeader.isBlank()) {
                    return true;
                }
                long uid = Long.parseLong(uidHeader.trim());
                String role = request.getHeader(HEADER_TEST_ROLE);
                UserInfoHolder.setUserInfo(uid, role == null ? AuthConfigConstant.ROLE_USER : role);
                TenantContext.bindUid(uid);
                if (TenantContext.current().isPresent()) {
                    TenantMembershipPort port = membershipProvider.getIfAvailable();
                    if (port != null) {
                        port.ensureMembership(uid);
                    }
                }
                return true;
            }
        }).addPathPatterns("/**");
    }
}
