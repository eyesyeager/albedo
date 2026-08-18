package com.eyes.albedo.support;

import java.util.UUID;

import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 临时测试租户（写入型服务测试专用）。
 *
 * <p>为什么不复用 {@code gift} / {@code redbook}：这两个租户是<b>验收种子数据</b>，
 * 发布/回滚测试会推进 {@code config_version} 与 Agent 版本号，污染 @测试 与 @前端 的验收基线。
 * 因此每个测试类创建自己的一次性租户，结束后彻底删除。
 */
public final class TempTenant implements AutoCloseable {

    private final JdbcTemplate jdbc;
    private final String tenantId;
    private final String host;
    private final Long tenantPk;

    private TempTenant(JdbcTemplate jdbc, String tenantId, String host, Long tenantPk) {
        this.jdbc = jdbc;
        this.tenantId = tenantId;
        this.host = host;
        this.tenantPk = tenantPk;
    }

    /**
     * 创建启用状态的临时租户（含 Host 绑定），并绑定租户上下文。
     */
    public static TempTenant create(JdbcTemplate jdbc) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantId = "it" + suffix;
        String host = "it-" + suffix + ".test.invalid";
        jdbc.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone, locale,"
                        + " config_version, version) VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "接口测试租户 " + suffix, host);
        Long pk = jdbc.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?", Long.class, tenantId);
        jdbc.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);

        TempTenant tenant = new TempTenant(jdbc, tenantId, host, pk);
        tenant.bind();
        return tenant;
    }

    /**
     * 绑定租户上下文（Hibernate discriminator 依赖它；uid 由调用方按需绑定）。
     */
    public void bind() {
        long configVersion = currentConfigVersion();
        TenantContext.bind(new TenantContext.Snapshot(tenantId, tenantPk, host,
                TenantStatus.ENABLED, configVersion, null));
    }

    /**
     * 重新绑定上下文以刷新 configVersion（发布后指针会变化）。
     */
    public void rebind() {
        TenantContext.clear();
        bind();
    }

    public String tenantId() {
        return tenantId;
    }

    public String host() {
        return host;
    }

    public long currentConfigVersion() {
        Long value = jdbc.queryForObject("SELECT config_version FROM tenants WHERE tenant_id = ?",
                Long.class, tenantId);
        return value == null ? 0L : value;
    }

    public int currentTenantVersion() {
        Integer value = jdbc.queryForObject("SELECT version FROM tenants WHERE tenant_id = ?",
                Integer.class, tenantId);
        return value == null ? 0 : value;
    }

    /**
     * 删除该租户的全部数据（DELETE 一律带 WHERE tenant_id，绝不误伤其他租户）。
     */
    @Override
    public void close() {
        TenantContext.clear();
        jdbc.update("DELETE FROM messages WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM conversations WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM site_config_versions WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }
}
