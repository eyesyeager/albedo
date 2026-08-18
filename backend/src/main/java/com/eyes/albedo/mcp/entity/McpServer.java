package com.eyes.albedo.mcp.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * MCP 服务配置（{@code scope=tenant}，architecture.md §13.5.3）。
 *
 * <p>🔴 安全约束：
 * <ul>
 *   <li>{@code transport='stdio'} → {@code 30060}（不向租户开放，不落库、不调用）</li>
 *   <li>{@code endpoint} 必须 HTTPS，且 <b>每次调用前</b>重新做 SSRF 校验（ADR-009）——
 *       不能依赖保存时的结论，因为一期由 DBA 直接改库（AC-MCP-004）</li>
 *   <li>{@code endpoint} 🔴 禁止出现在任何对外响应、审计、日志、异常消息中（属基础设施细节）</li>
 *   <li>{@code credentialCipher} 只写不回显；{@code credentialLast4} 由离线工具产出
 *       （明文 &lt;8 位一律 {@code ****}），🔴 禁止用于任何比较 / 校验 / 鉴权逻辑</li>
 *   <li>{@code timeoutSeconds} 与 {@code sys_config: mcp.call_timeout_seconds} 取<b>较小值</b></li>
 * </ul>
 *
 * <p>🔴 本表<b>禁入任何缓存</b>（L1/L2 均禁，architecture.md §12.1.1）：
 * 任何层级缓存都会造成"DBA 改库为非法地址后运行时仍放行"的绕过。
 */
@Getter
@Setter
@Entity
@Table(name = "mcp_servers")
public class McpServer extends BaseTenantEntity {

    /** 租户内唯一；🔴 变更后凭据必须重新加密（AAD 绑定 {@code mcp:{tenantId}:{mcpKey}}）。 */
    @Column(name = "mcp_key", nullable = false, length = 64)
    private String mcpKey;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** streamable_http / sse；🔴 stdio 一律拒绝。 */
    @Column(name = "transport", nullable = false, length = 32)
    private String transport = TRANSPORT_STREAMABLE_HTTP;

    /** 🔴 禁止对外回显。 */
    @Column(name = "endpoint", nullable = false, length = 2048)
    private String endpoint;

    /** none / bearer / header。 */
    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = AUTH_TYPE_NONE;

    /** {@code v{keyVersion}:{base64url(iv)}:{base64url(ct||tag)}}；🔴 只写不回显。 */
    @Column(name = "credential_cipher", length = 2048)
    private String credentialCipher;

    /** 明文末 4 位（&lt;8 位为 {@code ****}）；🔴 仅供人工核对。 */
    @Column(name = "credential_last4", nullable = false, length = 8)
    private String credentialLast4 = "";

    /** 一期恒为 1（{@code authType=none} 时为 0）；非 1 → {@code 30060}。 */
    @Column(name = "credential_key_version", nullable = false)
    private Integer credentialKeyVersion = 0;

    @Column(name = "credential_updated_at")
    private Instant credentialUpdatedAt;

    @Column(name = "timeout_seconds", nullable = false)
    private Integer timeoutSeconds = 30;

    /** enabled / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    /** healthy / unhealthy。 */
    @Column(name = "last_check_status", nullable = false, length = 20)
    private String lastCheckStatus = "";

    /** 连接测试分类结果（api-spec §7.4.2 的 8 个字面量）。 */
    @Column(name = "last_check_result", nullable = false, length = 32)
    private String lastCheckResult = "";

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    /** 乐观锁列（一期由 DBA 写库，暂不启用 {@code @Version}）。 */
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static final String TRANSPORT_STREAMABLE_HTTP = "streamable_http";
    /**
     * MCP <b>HTTP+SSE</b> 传输。
     *
     * <p>🔴 <b>V1.4.0（ADR-016 / api-spec G6′）起本取值同时覆盖两种形态</b>：
     * ① <b>同步应答形态</b>（POST 响应体内直接返回 JSON-RPC 结果）
     * ② <b>2024-11-05 异步推送形态</b>（POST 回 {@code 202} 空体，结果由同一次 exchange 内持有的
     * GET 事件流推送）。形态由 {@code SseTransport} 在<b>单次 exchange 内自适应判定</b>
     * （判据 = 首个 POST 的响应体形态）。
     *
     * <p>🔴 因此<b>不存在</b> {@code sse_legacy} 之类的第三个枚举值：{@code transport} 的合法值
     * <b>仍恰为 2 个</b>（本列 + {@link #TRANSPORT_STREAMABLE_HTTP}），DDL 与
     * {@code RuntimeConfigValidator} 的枚举校验<b>零变更</b> ——
     * 运维只需知道"这是 sse"，不必分辨上游属哪种形态（architecture.md §13.5.3）。
     */
    public static final String TRANSPORT_SSE = "sse";
    /** 🔴 一律拒绝的传输（api-spec §7.6.1）。 */
    public static final String TRANSPORT_STDIO = "stdio";

    public static final String AUTH_TYPE_NONE = "none";
    public static final String AUTH_TYPE_BEARER = "bearer";
    public static final String AUTH_TYPE_HEADER = "header";

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";

    public static final String CHECK_STATUS_HEALTHY = "healthy";
    public static final String CHECK_STATUS_UNHEALTHY = "unhealthy";
}
