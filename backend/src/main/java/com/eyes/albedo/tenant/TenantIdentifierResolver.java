package com.eyes.albedo.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

/**
 * Hibernate 当前租户解析器（discriminator 多租户）。
 *
 * <p>装配方式见 {@code com.eyes.albedo.config.JpaConfig}：通过 {@code HibernatePropertiesCustomizer}
 * 显式设置 {@code hibernate.tenant_identifier_resolver}，不依赖框架的隐式探测。
 *
 * <p>无上下文时返回 {@link TenantContext#NONE_TENANT} 哨兵，使租户实体查询 fail-closed
 * （结果为空而非跨租户全表），业务层仍需以 30010 / 30013 给出确定错误。
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantContext.tenantIdOrNone();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        // 单请求内不会切换租户；SSE 异步任务使用独立 Session，无需校验既有 Session
        return false;
    }

    @Override
    public boolean isRoot(String tenantId) {
        // 不存在"超级租户"：平台级数据一律走不继承 BaseTenantEntity 的实体
        return false;
    }
}
