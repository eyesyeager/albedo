package com.eyes.albedo.metrics.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 产品埋点事件（{@code scope=tenant}，architecture.md §13.5.8 / api-spec §7.10.1）。
 *
 * <p>🔴 本表即旧文档中的 {@code product_events}，V1.3 起统一为 {@code analytics_events}，
 * 实现与 DDL <b>只认新名</b>。
 *
 * <p>🔴 严格<b>字段白名单</b>：未在 api-spec §7.10.1 列出的字段静默丢弃；
 * 禁记消息正文 / {@code systemPrompt} / Skill 正文 / 凭据 / Token / 完整手机号邮箱，
 * 命中即整条 {@code discarded} 并记安全日志。
 *
 * <p>🔴 其他纪律：
 * <ul>
 *   <li>{@code pagePath} 服务端强制去 query 与 hash（防 Token 经 URL 泄露，AC-AUTH-002）</li>
 *   <li>{@code conversationId} / {@code agentId} 不属当前租户 + 当前 uid 时<b>置空</b>
 *       （不报错、不泄露存在性）</li>
 *   <li>匿名事件 {@code uid=NULL} 且 {@code loginState='anonymous'}</li>
 *   <li>{@code charCount} 🔴 只允许长度，禁止正文</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "analytics_events")
public class AnalyticsEvent extends BaseTenantEntity {

    /** {@code ^[A-Za-z0-9_-]{8,64}$}；去重键（{@code uk_tenant_client_event}）。 */
    @Column(name = "client_event_id", nullable = false, length = 64)
    private String clientEventId;

    /** 必须命中 {@code observability.analytics_allowed_events} 白名单。 */
    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    /** UTC；与服务端时间偏差 &gt;24h 整条丢弃。 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** NULL = 匿名事件。 */
    @Column(name = "uid")
    private Long uid;

    /** anonymous / logged_in。 */
    @Column(name = "login_state", nullable = false, length = 16)
    private String loginState = LOGIN_STATE_ANONYMOUS;

    @Column(name = "config_version")
    private Long configVersion;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_version")
    private Long agentVersion;

    /** local / mcp。 */
    @Column(name = "tool_type", length = 16)
    private String toolType;

    @Column(name = "tool_key", length = 200)
    private String toolKey;

    @Column(name = "status", length = 32)
    private String status;

    /** success / failed / denied。 */
    @Column(name = "result", length = 16)
    private String result;

    /** 🔴 必须是 api-spec §2.2 已登记码，否则置空。 */
    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 🔴 只允许长度，禁止正文。 */
    @Column(name = "char_count")
    private Integer charCount;

    /** {@code {promptTokens, completionTokens, totalTokens}}。 */
    @Column(name = "token_usage", columnDefinition = "json")
    private String tokenUsage;

    @Column(name = "source", length = 32)
    private String source;

    /** 🔴 仅站内 path，服务端强制去除 query 与 hash。 */
    @Column(name = "page_path", length = 512)
    private String pagePath;

    @Column(name = "action", length = 32)
    private String action;

    public static final String LOGIN_STATE_ANONYMOUS = "anonymous";
    public static final String LOGIN_STATE_LOGGED_IN = "logged_in";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_DENIED = "denied";

    /** {@code clientEventId} 格式（api-spec §7.10.1）。 */
    public static final String CLIENT_EVENT_ID_PATTERN = "^[A-Za-z0-9_-]{8,64}$";
}
