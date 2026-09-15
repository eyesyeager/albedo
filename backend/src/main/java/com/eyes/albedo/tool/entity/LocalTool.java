package com.eyes.albedo.tool.entity;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台本地 Tool 注册表（🔴 {@code scope=platform}，architecture.md §13.5.5）。
 *
 * <p>为什么<b>不</b>继承 {@code BaseTenantEntity}（§6.4 已登记）：这是<b>平台注册表</b>，
 * 租户只读。
 *
 * <p>🔴 <b>单行表 + {@code version} 就地递增</b>（V1.1.1 裁定）：
 * {@code uk_tool_key(tool_key)} 全局唯一，同一 {@code toolKey} <b>永远只有一行</b>，
 * 版本变更是 {@code UPDATE … SET version = version + 1}，
 * <b>不存在</b>"一个 toolKey 对应多个版本行"的形态。
 * 因此 {@code agent_capability_bindings.ref_version}（{@code capability_type='localTool'}）
 * 只是<b>审计快照</b>（回答"当时绑定的是哪一版注册元数据"），🔴 <b>不参与运行时解析</b>：
 * 运行时一律读当前行，以保证平台修正元数据后立即生效（AC-CFG-004）；
 * {@code ref_version} 与当前 {@code version} 不一致<b>不构成错误</b>。
 * ⚠️ 与 Skill 的差异不可混用：Skill 的 {@code ref_version} 是<b>精确版本引用</b>（快照语义）。
 *
 * <p>🔴 只登记<b>声明式元数据</b>，禁止任何可执行代码（PRD 非范围项）：
 * 实现体是平台内置 Java 组件，由 {@code LocalToolRegistry} 按 {@code toolKey} 静态注册；
 * 注册表有行但平台无对应实现 → {@code 30060}。
 */
@Getter
@Setter
@Entity
@Table(name = "local_tools")
public class LocalTool extends BaseAuditEntity {

    /** 全局唯一，{@code ^[a-z][a-z0-9_]{1,63}$}。 */
    @Column(name = "tool_key", nullable = false, length = 64)
    private String toolKey;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** 注册版本，🔴 就地递增（非多版本行）。 */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Column(name = "description", nullable = false, length = 500)
    private String description = "";

    /** JSON Schema draft 2020-12，根类型必须 {@code object}；非法 → {@code 30060}。 */
    @Column(name = "input_schema", nullable = false, columnDefinition = "json")
    private String inputSchema;

    @Column(name = "output_constraint", columnDefinition = "text")
    private String outputConstraint;

    /** 0 = 非幂等；🔴 结果未知时一律不自动重试（{@code 30056}，AC-TOL-003）。 */
    @Column(name = "idempotent", nullable = false, columnDefinition = "tinyint")
    private Integer idempotent = 0;

    /** {@code 1 ~ sys_config: tool.max_timeout_seconds}，越界 → {@code 30060}。 */
    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 30;

    /** enabled / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";

    /** {@code tool_key} 格式（api-spec §7.7.1）。 */
    public static final String TOOL_KEY_PATTERN = "^[a-z][a-z0-9_]{1,63}$";

    public boolean idempotentTool() {
        return Integer.valueOf(1).equals(idempotent);
    }
}
