package com.eyes.albedo.mcp;

import com.eyes.albedo.mcp.entity.McpServer;

/**
 * MCP 连接测试的分类结果（api-spec §7.4.2，🔴 字面量固定，@测试 据此断言）。
 *
 * <p>🔴 <b>共 9 个字面量</b>（V1.1.2 起含 {@link #CONNECT_FAILED}，G2 裁决）。
 *
 * <p>🔴 <b>口径裁决</b>（api-spec §7.4.2 末尾）：连接测试是<b>诊断能力</b>，
 * 除 {@link #SSRF_REJECTED} 外一律以 {@code code=0} + {@code data.result} 承载
 * （接口本身执行成功）；{@link #SSRF_REJECTED} 必须返回 {@code 30050}（EX-029）。
 *
 * <p>🔴 {@code data} 中禁止出现凭据、完整响应体、上游错误正文 —— 本枚举<b>只做分类</b>，
 * 不携带任何上游细节，这正是它存在的意义。
 */
public enum McpCheckResult {

    /** 建连 + 协议握手 + {@code tools/list} 成功且工具数 ≥1。 */
    SUCCESS("success", McpServer.CHECK_STATUS_HEALTHY),
    /** 域名无法解析。 */
    DNS_FAILED("dns_failed", McpServer.CHECK_STATUS_UNHEALTHY),
    /** TLS 握手 / 证书校验失败。 */
    TLS_FAILED("tls_failed", McpServer.CHECK_STATUS_UNHEALTHY),
    /** 鉴权被拒（HTTP 401/403 或 JSON-RPC 鉴权错误）。 */
    AUTH_FAILED("auth_failed", McpServer.CHECK_STATUS_UNHEALTHY),
    /**
     * 🔴 <b>V1.1.2 新增（api-spec §7.4.2 G2 已裁决）</b>：TCP 连接未能建立 ——
     * 连接被拒（{@code ConnectException} / RST）、网络不可达（{@code NoRouteToHostException}）、
     * 连接被重置。
     *
     * <p><b>与 {@link #TIMEOUT} 的判别口径</b>：
     * {@code connect_failed} = 上游<b>明确拒绝或不可达</b>（立即失败，排障方向是"端口没开 /
     * 安全组拦了 / 服务没起"）；{@code timeout} = <b>无响应直至超时</b>（静默丢包 / 上游卡死，
     * 排障方向是网络与上游负载）。🔴 两者混为一谈会把 DBA 导向错误的排查方向。
     *
     * <p>💡 {@code mcp_servers.last_check_result} 为 {@code VARCHAR(32)}，本字面量 14 字符，
     * <b>无需改表</b>；运行时调用的错误码不变（仍 {@code 30052}）。
     */
    CONNECT_FAILED("connect_failed", McpServer.CHECK_STATUS_UNHEALTHY),
    /** 建连或握手<b>超时</b>（无响应达超时阈值）。 */
    TIMEOUT("timeout", McpServer.CHECK_STATUS_UNHEALTHY),
    /** 非 JSON-RPC 2.0 / 缺 {@code tools} 能力 / 版本不兼容。 */
    PROTOCOL_INCOMPATIBLE("protocol_incompatible", McpServer.CHECK_STATUS_UNHEALTHY),
    /** 握手成功但 {@code tools/list} 返回空。 */
    NO_TOOLS_AVAILABLE("no_tools_available", McpServer.CHECK_STATUS_UNHEALTHY),
    /** 🔴 HTTPS/SSRF 校验拒绝（<b>不发起任何网络连接</b>）；唯一返回 {@code 30050} 的结果。 */
    SSRF_REJECTED("ssrf_rejected", McpServer.CHECK_STATUS_UNHEALTHY);

    private final String literal;
    private final String checkStatus;

    McpCheckResult(String literal, String checkStatus) {
        this.literal = literal;
        this.checkStatus = checkStatus;
    }

    /** 对外字面量（🔴 固定，写入 {@code mcp_servers.last_check_result} 并原样返回）。 */
    public String literal() {
        return literal;
    }

    /** 对应的 {@code mcp_servers.last_check_status}（healthy / unhealthy）。 */
    public String checkStatus() {
        return checkStatus;
    }

    public boolean healthy() {
        return this == SUCCESS;
    }
}
