package com.eyes.albedo.tool.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户对平台本地 Tool 的授权（{@code scope=tenant}，architecture.md §13.5.6）。
 *
 * <p>🔴 运行时进入清单需<b>四条件同时满足</b>（api-spec §7.7.2）：
 * <ol>
 *   <li>{@code local_tools.status='enabled'}</li>
 *   <li>{@code tenant_tool_grants.granted=1 AND status='enabled'}</li>
 *   <li>被会话 {@code agentVersion} 通过 {@code agent_capability_bindings}
 *       （{@code capability_type='localTool'}）绑定</li>
 *   <li>{@code agent_versions.tool_policy != 'disabled'}</li>
 * </ol>
 * 缺一即不进清单；未进清单的工具被模型请求调用 → {@code 30050} + 审计
 * {@code tool.grant_denied}（运行时校验是第二道兜底，清单级隔离是第一道）。
 *
 * <p>🔴 {@code config} 是<b>非代码配置</b>（如默认门店、回调白名单）：
 * 禁止出现脚本 / 表达式 / 可执行片段，出现 → {@code 30060}（校验入口与运行时兜底均检测）。
 */
@Getter
@Setter
@Entity
@Table(name = "tenant_tool_grants")
public class TenantToolGrant extends BaseTenantEntity {

    /** 指向 {@code local_tools.tool_key}（弱引用，🔴 不建物理外键）。 */
    @Column(name = "tool_key", nullable = false, length = 64)
    private String toolKey;

    /** 🔴 默认 0；仅 1 且 {@code status=enabled} 才进清单。 */
    @Column(name = "granted", nullable = false, columnDefinition = "tinyint")
    private Integer granted = 0;

    /** 非代码配置（🔴 禁止脚本 / 表达式 / 可执行片段）。 */
    @Column(name = "config", columnDefinition = "json")
    private String config;

    /** enabled / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at")
    private Instant grantedAt;

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";

    public boolean grantedAndEnabled() {
        return Integer.valueOf(1).equals(granted) && STATUS_ENABLED.equals(status);
    }
}
