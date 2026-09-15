package com.eyes.albedo.audit;

import java.util.Set;

/**
 * 审计 {@code action} 字面量（唯一实现基线，architecture.md §11.1.1 / api-spec §7.14）。
 *
 * <p>🔴 纪律：新增 action <b>必须先回写 api-spec §7.14 与 architecture.md §11.1.1</b>，
 * 再在本类登记；@测试 以本类的 12 项枚举做断言（AC-AUD-003）。
 *
 * <p>🔴 本项目<b>不引入</b> {@code event_type} 第二概念：列名与字段名统一为 {@code action}。
 */
public final class AuditActions {

    private AuditActions() {
    }

    /** 工具未授权 / 未绑定 / 已停用 / {@code tool_policy=disabled} 被模型请求调用（流式内独立短事务，result=denied）。 */
    public static final String TOOL_GRANT_DENIED = "tool.grant_denied";
    /** 保存时或每次调用前的 SSRF 校验拒绝（result=denied）。 */
    public static final String MCP_SSRF_REJECTED = "mcp.ssrf_rejected";
    /** MCP 连接测试（🔴 含成功；非流式同事务，result=success/failed）。 */
    public static final String MCP_CONNECTION_TEST = "mcp.connection_test";
    /** MCP 凭据变更首次生效（非流式同事务，result=success）。 */
    public static final String MCP_CREDENTIAL_CHANGED = "mcp.credential_changed";
    /**
     * 🔴 <b>系统发起的授权撤销</b>（api-spec V1.1.2 §7.14 新增，G3 裁决）。
     *
     * <p>触发点：工具发现时 {@code schema_changed} 的已授权工具自动降级（{@code reason=schemaChanged}）
     * ／ {@code removed} 且原 {@code granted=1} 被置停用（{@code reason=toolRemoved}）。
     *
     * <p>🔴 <b>为什么不能复用 {@link #TOOL_GRANT_DENIED}</b>（三处语义全不同）：
     * <table border="1">
     *   <caption>对照</caption>
     *   <tr><th>项</th><th>tool.grant_denied</th><th>mcp.tool_grant_revoked</th></tr>
     *   <tr><td>语义</td><td><b>模型请求调用</b>一个不可用工具</td><td><b>系统主动撤销一条授权</b>（无任何调用发生）</td></tr>
     *   <tr><td>actorType</td><td>{@code endUser}</td><td>{@code system}</td></tr>
     *   <tr><td>objectType / objectId</td><td>{@code toolCall} / {@code tool_calls.id}</td>
     *       <td>{@code mcpTool} / {@code mcp_tools.id}</td></tr>
     *   <tr><td>result</td><td>{@code denied}</td><td>{@code success}（撤销动作本身成功）</td></tr>
     * </table>
     * 复用会让"用户越权尝试"被"系统例行降级"淹没，并使 {@code idx_object} 追溯路径的
     * {@code objectId} 语义分叉（architecture.md §11.1.2）。
     */
    public static final String MCP_TOOL_GRANT_REVOKED = "mcp.tool_grant_revoked";
    /** 命中 10004 且检测到跨租户 ID（result=denied）。 */
    public static final String TENANT_CROSS_PROBE = "tenant.cross_probe";
    /** 平台管理员排障票据签发（result=success）。 */
    public static final String PLATFORM_ACCESS_GRANT_ISSUED = "platform.access_grant_issued";
    /** 缓存失效接口调用（含部分失败；非流式同事务，result=success/failed）。 */
    public static final String PLATFORM_CACHE_EVICT = "platform.cache_evict";

    /** 全部合法 action（🔴 写入前强制校验，防止拼写漂移导致 @测试 断言不到事件）。 */
    public static final Set<String> ALL = Set.of(
            TOOL_GRANT_DENIED,
            MCP_SSRF_REJECTED,
            MCP_CONNECTION_TEST,
            MCP_CREDENTIAL_CHANGED,
            MCP_TOOL_GRANT_REVOKED,
            TENANT_CROSS_PROBE,
            PLATFORM_ACCESS_GRANT_ISSUED,
            PLATFORM_CACHE_EVICT
    );

    // ===== mcp.tool_grant_revoked 的 reason 固定枚举（api-spec §7.4.3 G3） =====
    /** Schema 变更导致已授权工具自动降级。 */
    public static final String REASON_SCHEMA_CHANGED = "schemaChanged";
    /** 上游已移除该工具，原授权被撤销。 */
    public static final String REASON_TOOL_REMOVED = "toolRemoved";
    /** {@code removed} 场景的 afterDigest 取值（Schema 已不存在）。 */
    public static final String DIGEST_REMOVED = "removed";

    public static boolean isRegistered(String action) {
        return action != null && ALL.contains(action);
    }
}
