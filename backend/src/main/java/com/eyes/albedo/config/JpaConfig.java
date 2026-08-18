package com.eyes.albedo.config;

import java.util.Map;

import com.eyes.albedo.tenant.TenantIdentifierResolver;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JPA / Hibernate 配置：装配 discriminator 多租户解析器。
 *
 * <p>为什么显式设置：不同 Spring Boot 版本对 {@code CurrentTenantIdentifierResolver} Bean 的
 * 隐式探测行为不一致（且主要面向 DATABASE/SCHEMA 策略）。本项目用 DISCRIMINATOR 策略，
 * 通过 {@link HibernatePropertiesCustomizer} 显式注入，确保行为确定、版本升级不失效。
 *
 * <p>配套 DDL 由 @后端 通过 MySQL MCP 实执行；应用侧 {@code ddl-auto: validate}
 * 只做结构校验，禁止自动建表/改表（避免线上结构漂移）。
 */
@Configuration(proxyBeanMethods = false)
public class JpaConfig {

    @Bean
    public HibernatePropertiesCustomizer multiTenancyCustomizer(TenantIdentifierResolver resolver) {
        return (Map<String, Object> hibernateProperties) ->
                hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
