package com.eyes.albedo.auth;

import com.eyes.eyesAuth.starter.EyesAuthProperties;
import com.eyes.eyesAuth.thrift.TTClientPool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 耶瞳鉴权手工装配（ADR-002）。
 *
 * <p>为什么要手工装配：starter 的 {@code EyesAuthAutoConfiguration} 仅注册在
 * {@code META-INF/spring.factories}，Spring Boot 3 已不再从该文件加载自动配置；
 * 且其装配的 {@code PermissionAdvice} 依赖 {@code javax.servlet}，在 Boot 3 下不可用。
 *
 * <p>本类只装配 <b>与 Servlet API 无关</b>的部分（配置属性 + Thrift 连接池），
 * 鉴权切面使用本项目的 jakarta 实现 {@link PermissionAspect}。
 *
 * <p>🔴 禁止在本项目任何位置 {@code @Import(EyesAuthAutoConfiguration.class)}，
 * 也禁止使用 {@code com.eyes.eyesAuth.utils.WebHelper} 与 {@code PermissionAdvice}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EyesAuthProperties.class)
@ConditionalOnProperty(prefix = "eyes-auth", name = "enabled", havingValue = "true")
public class EyesAuthConfig {

    /**
     * Thrift 连接池（starter 提供，纯网络组件，与 Servlet API 无关）。
     */
    @Bean(initMethod = "init")
    public TTClientPool ttClientPool(EyesAuthProperties properties) {
        return new TTClientPool(properties);
    }

    /**
     * jakarta 版 {@code @Permission} 鉴权切面。
     */
    @Bean
    public PermissionAspect permissionAspect(EyesAuthProperties properties,
                                            TTClientPool ttClientPool,
                                            ObjectProvider<TenantMembershipPort> membershipProvider) {
        return new PermissionAspect(properties, ttClientPool, membershipProvider);
    }

    /**
     * 租户内角色校验切面（在鉴权切面之后执行）。
     */
    @Bean
    public TenantRoleAspect tenantRoleAspect(ObjectProvider<TenantMembershipPort> membershipProvider) {
        return new TenantRoleAspect(membershipProvider);
    }
}
