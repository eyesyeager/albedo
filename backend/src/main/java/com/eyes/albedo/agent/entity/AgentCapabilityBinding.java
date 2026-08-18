package com.eyes.albedo.agent.entity;

import java.util.Set;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 版本的能力绑定（{@code scope=tenant}，architecture.md <b>§13.5.10</b>（正式登记）/ §13.4 ER 图）。
 *
 * <p>🔴 <b>它是运行时事实来源</b>：{@code agent_versions.capability_snapshot} 与本表双写，
 * 但快照仅供审计对照（§13.2 第 7 条禁止 JSON 内藏可查询状态），
 * 运行时的 Skill 注入与工具清单构造<b>一律读本表</b>。
 *
 * <p><b>{@code ref_id} / {@code ref_version} 的语义按 {@code capability_type} 分三种（🔴 不可混用）</b>：
 * <table border="1">
 *   <caption>引用语义</caption>
 *   <tr><th>capability_type</th><th>ref_id</th><th>ref_version</th></tr>
 *   <tr>
 *     <td>{@code skill}</td><td>{@code skills.id}</td>
 *     <td>🔴 <b>精确版本引用</b>（{@code skill_versions.version}）：禁止"最新"语义，
 *         否则新增 Skill 版本会改变旧会话行为（AC-SKL-002）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code mcpTool}</td><td>{@code mcp_tools.id}</td>
 *     <td>发现批次号（审计对照，不参与运行时解析）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code localTool}</td><td>{@code tenant_tool_grants.id}</td>
 *     <td>绑定时 {@code local_tools.version} 的<b>审计快照</b>；🔴 <b>不参与</b>运行时解析，
 *         与当前 {@code version} 不一致<b>不构成错误</b>（api-spec §7.7.1 版本语义裁定 ②③）</td>
 *   </tr>
 * </table>
 *
 * <p>✅ <b>已裁决并正式登记</b>（api-spec V1.1.2 §7.4.4 G1 + architecture.md V1.3.1 <b>§13.5.10</b>）：
 * 本表已从"ER 图中出现但未进 DDL"的登记漏项升级为<b>正式表</b>（含 as-built DDL、唯一键
 * {@code uk_tenant_binding}、索引 {@code idx_tenant_version_sort}），
 * 与 @后端 M3 第二阶段实执行的结构<b>逐字一致，无需改表</b>。
 * 🔴 {@code localTool} 的 {@code ref_id} 指向 {@code tenant_tool_grants.id} 是<b>正式契约</b>
 * （不再是推导）。
 *
 * <p>🔴 <b>运维纪律（已写入契约 §7.4.4）</b>：{@code tenant_tool_grants} 行被 {@code DELETE}
 * 后重建会得到新 {@code id} → 既有绑定悬挂 → 该工具<b>不进清单</b>（fail-closed，不报错也不越权）。
 * 调整授权一律用 <b>{@code UPDATE granted/status}</b>，🔴 <b>禁止 {@code DELETE + INSERT}</b>。
 *
 * <p>🔴 一期<b>不缓存</b>（api-spec §7.1.2 末尾）：直读 MySQL 以保证 DBA 改绑定后立即生效
 * （AC-CFG-004）。
 */
@Getter
@Setter
@Entity
@Table(name = "agent_capability_bindings")
public class AgentCapabilityBinding extends BaseTenantEntity {

    @Column(name = "agent_version_id", nullable = false)
    private Long agentVersionId;

    /** skill / mcpTool / localTool。 */
    @Column(name = "capability_type", nullable = false, length = 16)
    private String capabilityType;

    /** 见类注释的三种语义。 */
    @Column(name = "ref_id", nullable = false)
    private Long refId;

    /** 见类注释的三种语义（🔴 skill 为精确版本，其余为审计快照）。 */
    @Column(name = "ref_version", nullable = false)
    private Integer refVersion = 0;

    /** Skill 变量取值 KV（DBA 写入）。 */
    @Column(name = "variable_values", columnDefinition = "json")
    private String variableValues;

    /** 注入顺序（ASC；同序再按 refId ASC，api-spec §7.5.2）。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public static final String TYPE_SKILL = "skill";
    public static final String TYPE_MCP_TOOL = "mcpTool";
    public static final String TYPE_LOCAL_TOOL = "localTool";

    public static final Set<String> ALL_TYPES = Set.of(TYPE_SKILL, TYPE_MCP_TOOL, TYPE_LOCAL_TOOL);
}
