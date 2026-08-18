package com.eyes.albedo.agent.entity;

import java.math.BigDecimal;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 发布快照（{@code scope=tenant}，🔴 <b>不可变</b>）。
 *
 * <p>为什么必须不可变（RISK-005 / AC-AGT-003）：会话在创建时绑定
 * {@code (agentId, agentVersion)}，若快照可改，历史会话的行为将不可复现。
 * 因此：任何修改都必须<b>生成新版本号</b>，本表已有行只允许 {@code published → archived} 的状态流转。
 *
 * <p>{@code systemPrompt} 属于内部资产，🔴 严禁通过任何面向终端用户的接口返回（api-spec §4.4.1）。
 */
@Getter
@Setter
@Entity
@Table(name = "agent_versions")
public class AgentVersion extends BaseTenantEntity {

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "longtext")
    private String systemPrompt;

    @Column(name = "provider_key", nullable = false, length = 64)
    private String providerKey;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "temperature", nullable = false, precision = 3, scale = 2)
    private BigDecimal temperature;

    @Column(name = "max_output_tokens", nullable = false)
    private Integer maxOutputTokens;

    /** 上下文策略，默认 {@code summary_then_window}。 */
    @Column(name = "context_strategy", nullable = false, length = 32)
    private String contextStrategy;

    @Column(name = "request_timeout_seconds", nullable = false)
    private Integer requestTimeoutSeconds;

    /** disabled / auto / confirm（M1 固定 disabled，工具编排在 M3）。 */
    @Column(name = "tool_policy", nullable = false, length = 16)
    private String toolPolicy;

    /** Skill / MCP / Tool 绑定快照（JSON，M2 起写入）。 */
    @Column(name = "capability_snapshot", columnDefinition = "json")
    private String capabilitySnapshot;

    /** published / archived。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PUBLISHED;

    @Column(name = "published_by")
    private Long publishedBy;

    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    public static final String CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW = "summary_then_window";
    public static final String CONTEXT_STRATEGY_WINDOW = "window";
    public static final String TOOL_POLICY_DISABLED = "disabled";
}
