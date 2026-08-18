package com.eyes.albedo.mcp.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * MCP 工具发现结果 + 逐项授权（{@code scope=tenant}，architecture.md §13.5.4）。
 *
 * <p>🔴 授权纪律（api-spec §7.4.4 / AC-MCP-005）：
 * <ul>
 *   <li>仅 {@code granted=1 AND status='enabled'} 且所属 {@code mcp_servers.status='enabled'}
 *       的工具进入模型可调用清单</li>
 *   <li>新发现工具一律 {@code granted=0 + status='disabled'}（默认禁用，不进清单）</li>
 *   <li>🔴 {@code schema_changed} 的<b>已授权</b>工具<b>自动降级</b>为 {@code granted=0/disabled} 并写审计
 *       —— 防"先以无害 Schema 拿到授权，再偷换参数"的提权路径</li>
 *   <li>{@code removed} 保留历史行并置 {@code disabled}，🔴 不物理删除（保住 {@code tool_calls} 可追溯语义）</li>
 *   <li>{@code riskLevel} 未知一律 {@code high}，🔴 租户不可下调</li>
 * </ul>
 *
 * <p>🔴 一期<b>不缓存</b>授权清单：直读 MySQL，保证 DBA 改库（取消授权）后运行时立即拒绝
 * （AC-MCP-004；缓存会破坏该验收项）。
 */
@Getter
@Setter
@Entity
@Table(name = "mcp_tools")
public class McpTool extends BaseTenantEntity {

    @Column(name = "mcp_id", nullable = false)
    private Long mcpId;

    /** 上游原名。 */
    @Column(name = "tool_name", nullable = false, length = 128)
    private String toolName;

    /** {@code {mcpKey}:{toolName}}，租户内唯一（避免多 MCP 同名工具冲突）。 */
    @Column(name = "tool_key", nullable = false, length = 200)
    private String toolKey;

    @Column(name = "description", nullable = false, length = 500)
    private String description = "";

    /** JSON Schema draft 2020-12，根类型必须 {@code object}。 */
    @Column(name = "input_schema", columnDefinition = "json")
    private String inputSchema;

    /** {@code sha256(规范化 inputSchema)} 前 16 hex，用于 {@code schema_changed} 判定。 */
    @Column(name = "input_schema_digest", nullable = false, length = 80)
    private String inputSchemaDigest = "";

    /** low / medium / high（未知一律 high）。 */
    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel = RISK_HIGH;

    /** 🔴 默认 0；新发现工具不得自动获得授权。 */
    @Column(name = "granted", nullable = false, columnDefinition = "tinyint")
    private Integer granted = 0;

    /** enabled / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    /** new / unchanged / schema_changed / removed。 */
    @Column(name = "change_type", nullable = false, length = 20)
    private String changeType = "";

    @Column(name = "removed_at")
    private Instant removedAt;

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";

    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";

    public static final String CHANGE_NEW = "new";
    public static final String CHANGE_UNCHANGED = "unchanged";
    public static final String CHANGE_SCHEMA_CHANGED = "schema_changed";
    public static final String CHANGE_REMOVED = "removed";

    /** 是否可进入模型可调用清单（不含"被 agentVersion 绑定"这一条件，由 tool 模块补齐）。 */
    public boolean grantedAndEnabled() {
        return Integer.valueOf(1).equals(granted) && STATUS_ENABLED.equals(status);
    }
}
