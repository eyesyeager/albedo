package com.eyes.albedo.mcp;

import com.eyes.albedo.common.ErrorCode;

/**
 * MCP 上游失败的<b>唯一分类枚举</b>（api-spec §7.6.4 上游结果映射 + §7.4.2 分类结果）。
 *
 * <p><b>为什么需要它</b>：同一个上游失败要同时满足两个互不相同的口径 ——
 * <ul>
 *   <li>运行时调用：映射为 {@code tool_calls.error_code}（{@code 30051}/{@code 30052}/
 *       {@code 30053}/{@code 30057}）</li>
 *   <li>连接测试：映射为 {@link McpCheckResult} 的 9 个诊断字面量（V1.1.2 起含
 *       {@code connect_failed}，G2 裁决）</li>
 * </ul>
 * 若在两处各写一遍 if-else，必然出现"同一故障两处结论不一致"。本枚举把映射表收敛为一处，
 * 🔴 {@code mcp} 包只负责"分类"，{@code tool} 包只负责"落库与下发"（architecture.md §5.1.3）。
 *
 * <p>🔴 所有 {@code errorCode} 均为 api-spec §2.2 已登记码，禁止自造。
 */
public enum McpFailure {

    /** 域名无法解析。 */
    DNS_FAILED(ErrorCode.MCP_UNAVAILABLE, McpCheckResult.DNS_FAILED),
    /** TLS 握手 / 证书校验失败（🔴 一律不降级端点识别，宁可失败）。 */
    TLS_FAILED(ErrorCode.MCP_UNAVAILABLE, McpCheckResult.TLS_FAILED),
    /**
     * 连接失败 / 不可达（连接被拒、RST、no route to host）。
     *
     * <p>🔴 已裁决（api-spec §7.4.2 G2）：诊断分类为 {@link McpCheckResult#CONNECT_FAILED}
     * （不再归入 {@code timeout}）；运行时错误码不变，仍为 {@code 30052}。
     */
    CONNECT_FAILED(ErrorCode.MCP_UNAVAILABLE, McpCheckResult.CONNECT_FAILED),
    /** HTTP 401 / 403 或 JSON-RPC 鉴权错误（🔴 写审计）。 */
    AUTH_FAILED(ErrorCode.MCP_UNAVAILABLE, McpCheckResult.AUTH_FAILED),
    /** 超时（{@code mcp.call_timeout_seconds} / {@code discover_timeout_seconds}）。 */
    TIMEOUT(ErrorCode.TOOL_TIMEOUT, McpCheckResult.TIMEOUT),
    /**
     * 非 JSON-RPC 2.0 响应 / {@code -32601} 方法不存在 / 缺 {@code tools} 能力 /
     * HTTP 5xx / 🔴 HTTP 3xx（不跟随重定向，出现即视为协议问题）。
     */
    PROTOCOL_INCOMPATIBLE(ErrorCode.MCP_UNAVAILABLE, McpCheckResult.PROTOCOL_INCOMPATIBLE),
    /** JSON-RPC {@code -32602}（invalid params）→ 与本地 Tool 的 Schema 校验失败同码。 */
    INVALID_PARAMS(ErrorCode.TOOL_ARGS_INVALID, McpCheckResult.PROTOCOL_INCOMPATIBLE),
    /** {@code result.isError=true}：工具执行返回<b>业务失败</b>（非超时 / 非鉴权 / 非参数错误）。 */
    EXECUTION_FAILED(ErrorCode.TOOL_EXECUTION_FAILED, McpCheckResult.SUCCESS);

    private final int errorCode;
    private final McpCheckResult checkResult;

    McpFailure(int errorCode, McpCheckResult checkResult) {
        this.errorCode = errorCode;
        this.checkResult = checkResult;
    }

    /** 已登记的业务错误码（api-spec §2.2 / §7.6.4）。 */
    public int errorCode() {
        return errorCode;
    }

    /** 连接测试语境下的诊断分类（api-spec §7.4.2）。 */
    public McpCheckResult checkResult() {
        return checkResult;
    }

    /** 是否属于"应写安全审计"的失败（鉴权被拒，api-spec §7.6.4）。 */
    public boolean securityRelevant() {
        return this == AUTH_FAILED;
    }
}
