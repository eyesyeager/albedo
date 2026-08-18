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
    /** 用户提交 {@code decision=allow}（confirm 请求短事务，result=success）。 */
    public static final String TOOL_CONFIRM_ALLOWED = "tool.confirm_allowed";
    /** 用户提交 {@code decision=deny}（confirm 请求短事务，result=denied）。 */
    public static final String TOOL_CONFIRM_DENIED = "tool.confirm_denied";
    /** 确认等待超过 {@code tool.confirm_wait_seconds}（由生成线程写，result=denied）。 */
    public static final String TOOL_CONFIRM_TIMEOUT = "tool.confirm_timeout";
    /**
     * 🔴 <b>确认决定冲突</b>（api-spec V1.1.3 §7.8.2 ⑤ / §7.14 新增，本轮登记）。
     *
     * <p>触发点：同一 {@code toolCallId} 提交与既有决定<b>相反</b>的 {@code decision}（返回 {@code 30055}）。
     * 字段契约：{@code actorType=endUser}、{@code objectType=toolCall}、{@code result=denied}、
     * {@code errorCode=30055}；{@code beforeDigest}=既有决定、{@code afterDigest}=被拒绝的提交值
     * （🔴 二者均为枚举字面量 {@code allow}/{@code deny}，非敏感值，可原样记）。
     *
     * <p>🔴 <b>为什么必须留痕</b>："用户先 allow 又 deny（或反之）"是<b>安全相关行为</b>，
     * 且恰好发生在<b>高风险工具</b>这条最需要留痕的链路上：可能是多标签页/前端缺陷，
     * 也可能是有人试图<b>翻转一个已生效的高风险决定</b>
     * （例如已 allow 并执行成功后再提交 deny，制造"我没批准过"的抗辩）。不留痕等于放弃举证能力。
     *
     * <p>🔴 <b>防刷（不新增表、不改 DDL）</b>：同一 {@code toolCallId} <b>至多一条</b> ——
     * 行锁内先按 {@code (tenant_id, action, object_type='toolCall', object_id)} 点查去重
     * （{@code idx_object} 支撑），已存在则<b>跳过写入但仍返回 30055</b>。
     * 冲突事实"发生过"即已完成留痕，重复刷同一冲突不增加信息量，却是最容易被前端重试放大的路径。
     *
     * <p>🔴 该路径<b>不改动</b> {@code tool_calls} 任何列（状态已是终态，改动即篡改历史并破坏 §7.11.1 聚合）。
     */
    public static final String TOOL_CONFIRM_CONFLICT = "tool.confirm_conflict";
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
            TOOL_CONFIRM_ALLOWED,
            TOOL_CONFIRM_DENIED,
            TOOL_CONFIRM_TIMEOUT,
            TOOL_CONFIRM_CONFLICT,
            MCP_SSRF_REJECTED,
            MCP_CONNECTION_TEST,
            MCP_CREDENTIAL_CHANGED,
            MCP_TOOL_GRANT_REVOKED,
            TENANT_CROSS_PROBE,
            PLATFORM_ACCESS_GRANT_ISSUED,
            PLATFORM_CACHE_EVICT
    );

    // ===== tool.confirm_conflict 的 reason 兜底值（api-spec §7.8.2 ⑤） =====
    /** 调用方未给 {@code reason} 时的固定取值。 */
    public static final String REASON_CONFLICTING_DECISION = "conflictingDecision";

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
