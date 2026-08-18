package com.eyes.albedo.tool.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 工具调用摘要与状态机（{@code scope=tenant}，architecture.md §13.5.7 / api-spec §7.8.1）。
 *
 * <p>🔴 <b>只存脱敏摘要</b>：{@code argsSummary} / {@code resultSummary} 由
 * {@code ToolSummaryScrubber} 按 api-spec §5.4.3 生成（长度受
 * {@code tool.args_summary_max_chars} / {@code tool.result_summary_max_chars} 限制）；
 * 禁存完整入参与结果、禁存 endpoint / 凭据 / 消息正文。
 * 同一份摘要在 SSE 事件、本表、审计、查询接口<b>四处复用</b>。
 *
 * <p>🔴 <b>状态机唯一裁决点是本表行锁</b>（{@code SELECT … FOR UPDATE}）：
 * 缓存状态会与 {@code 30055} 的冲突判定打架（ADR-008），故本表状态<b>禁入任何缓存</b>。
 *
 * <p>🔴 {@code timed_out} 的两种语义由 {@code errorCode} 区分（api-spec §7.8.1 末尾）：
 * <ul>
 *   <li>{@code 30050} = <b>确认等待超时</b>（语义等同拒绝，用量聚合计入 {@code toolDeniedCount}）</li>
 *   <li>{@code 30051} / {@code 30056} = <b>执行超时</b>（计入 {@code toolFailedCount}）</li>
 * </ul>
 * confirm 接口的冲突判定依赖该区分，🔴 不得混填。
 */
@Getter
@Setter
@Entity
@Table(name = "tool_calls")
public class ToolCall extends BaseTenantEntity {

    /** 冗余列：避免会话维度查询 join {@code messages}。 */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 模型下发的 tool_call id；配合唯一键保证重复投递不重复落库。 */
    @Column(name = "provider_call_id", nullable = false, length = 128)
    private String providerCallId;

    /** 本次生成内的轮次，从 1 计；上限 {@code sys_config: tool.max_rounds}。 */
    @Column(name = "round", nullable = false)
    private Integer round = 1;

    /** local / mcp。 */
    @Column(name = "tool_type", nullable = false, length = 16)
    private String toolType;

    @Column(name = "tool_key", nullable = false, length = 200)
    private String toolKey;

    @Column(name = "tool_name_snapshot", nullable = false, length = 128)
    private String toolNameSnapshot = "";

    /** {@code toolType=mcp} 时的 {@code mcp_servers.id}。 */
    @Column(name = "mcp_id")
    private Long mcpId;

    @Column(name = "schema_digest", nullable = false, length = 80)
    private String schemaDigest = "";

    @Column(name = "risk_level", nullable = false, length = 16)
    private String riskLevel = LocalTool.RISK_HIGH;

    /** 见 {@link #STATUS_PENDING} 等常量（api-spec §7.8.1 枚举全列）。 */
    @Column(name = "status", nullable = false, length = 32)
    private String status = STATUS_PENDING;

    /** 🔴 只允许已登记数字业务码，禁止字符串码。 */
    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "requires_confirmation", nullable = false, columnDefinition = "tinyint")
    private Integer requiresConfirmation = 0;

    /** allow / deny。 */
    @Column(name = "decision", length = 16)
    private String decision;

    @Column(name = "decided_by_uid")
    private Long decidedByUid;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** 🔴 脱敏摘要，禁存完整入参。 */
    @Column(name = "args_summary", nullable = false, length = 1024)
    private String argsSummary = "";

    /** 🔴 脱敏摘要，禁存完整结果 / endpoint / 凭据。 */
    @Column(name = "result_summary", nullable = false, length = 2048)
    private String resultSummary = "";

    /** 结果超 {@code tool.result_max_bytes} 被截断（EX-017）。 */
    @Column(name = "truncated", nullable = false, columnDefinition = "tinyint")
    private Integer truncated = 0;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    // ===== 状态机（api-spec §7.8.1，🔴 枚举全列，终态不可再迁移） =====
    /** 模型请求调用，已落库待校验。 */
    public static final String STATUS_PENDING = "pending";
    /** 需确认，等待用户决定（等待上限 {@code tool.confirm_wait_seconds}）。 */
    public static final String STATUS_AWAITING_CONFIRMATION = "awaiting_confirmation";
    /** 已允许且开始执行。 */
    public static final String STATUS_RUNNING = "running";
    /** 终态：执行成功（含结果被截断）。 */
    public static final String STATUS_SUCCEEDED = "succeeded";
    /** 终态：执行失败（30052/30053/30057/50003）。 */
    public static final String STATUS_FAILED = "failed";
    /** 终态：确认等待超时（30050）或执行超时（30051/30056）。 */
    public static final String STATUS_TIMED_OUT = "timed_out";
    /** 终态：用户停止生成或会话被删除（EX-022）。 */
    public static final String STATUS_CANCELLED = "cancelled";
    /** 终态：用户拒绝 / 未授权 / 未绑定 / SSRF 拒绝（30050）。 */
    public static final String STATUS_DENIED = "denied";

    public static final java.util.Set<String> ALL_STATUSES = java.util.Set.of(
            STATUS_PENDING, STATUS_AWAITING_CONFIRMATION, STATUS_RUNNING, STATUS_SUCCEEDED,
            STATUS_FAILED, STATUS_TIMED_OUT, STATUS_CANCELLED, STATUS_DENIED);

    public static final java.util.Set<String> TERMINAL_STATUSES = java.util.Set.of(
            STATUS_SUCCEEDED, STATUS_FAILED, STATUS_TIMED_OUT, STATUS_CANCELLED, STATUS_DENIED);

    public static final String TOOL_TYPE_LOCAL = "local";
    public static final String TOOL_TYPE_MCP = "mcp";

    public static final String DECISION_ALLOW = "allow";
    public static final String DECISION_DENY = "deny";

    public boolean terminal() {
        return TERMINAL_STATUSES.contains(status);
    }
}
