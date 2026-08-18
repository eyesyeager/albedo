package com.eyes.albedo.mcp;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.mcp.dto.McpConnectionTestResult;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP 连接测试（api-spec §7.4.2，REQ-MCP-001 / AC-MCP-001 / AC-MCP-004 / AC-AUD-003）。
 *
 * <p><b>行为顺序（契约固定）</b>：
 * HTTPS/SSRF 校验 → 建连 → 握手 → {@code tools/list} → 🔴 <b>强制写审计</b>
 * {@code mcp.connection_test}（<b>含成功</b>）。
 *
 * <p>🔴 <b>诊断语义（api-spec §7.4.2 口径裁决）</b>：连接测试是<b>诊断能力</b>，
 * {@code dns_failed} / {@code tls_failed} / {@code auth_failed} / {@code timeout} /
 * {@code protocol_incompatible} / {@code no_tools_available} 一律以 {@code code=0} +
 * {@code data.result} 承载（接口本身执行成功了）。
 * <b>唯一例外</b>：{@code ssrf_rejected} 必须返回 {@code 30050}（EX-029 强制"连接测试也拒绝"）。
 *
 * <p>🔴 <b>事务纪律（ADR-010 + EX-024，本类最微妙的地方）</b>：
 * <pre>
 * ① 本类 test(...) **不是事务方法**（它做 DNS 解析与 HTTP 调用）
 * ② 结果落库（mcp_servers.last_check_*）与审计收敛在
 *    McpConnectionTestWriter.persistOutcome(...) 的**同一短事务**
 * ③ 🔴 ssrf_rejected 的 30050 必须在 persistOutcome **提交之后**才抛出：
 *    若在事务内抛，RuntimeException 会把审计与 last_check 一起回滚 →
 *    表现为"接口报了 30050，但 audit_logs 查不到 mcp.ssrf_rejected" →
 *    违反 EX-029 与 AC-AUD-003。
 *    因此本类**只返回结果对象**，由 Controller 决定是否抛 30050。
 * </pre>
 *
 * <p>⚠️ <b>已知实现口径（已回报 @架构师）</b>：契约提到握手阶段的 {@code initialize}，
 * 但一期<b>不维护 MCP 会话</b>（无 {@code sessionId}、无 {@code initialized} 通知）——
 * 每次调用都是独立的 JSON-RPC 请求。因此"握手"以 {@code tools/list} 的成功与否代表：
 * 上游若要求先 {@code initialize}，会以 JSON-RPC error 回复 →
 * 分类为 {@code protocol_incompatible}，语义准确且不会误判为健康。
 */
@Slf4j
@Service
public class McpConnectionTester {

    private final McpClient mcpClient;
    private final SsrfGuard ssrfGuard;
    private final McpConnectionTestWriter testWriter;

    public McpConnectionTester(McpClient mcpClient,
                              SsrfGuard ssrfGuard,
                              McpConnectionTestWriter testWriter) {
        this.mcpClient = mcpClient;
        this.ssrfGuard = ssrfGuard;
        this.testWriter = testWriter;
    }

    /**
     * 执行连接测试（🔴 非事务方法）。
     *
     * <p>返回结果中 {@code result} 为 {@link McpCheckResult#SSRF_REJECTED} 时，
     * 调用方<b>必须</b>返回 {@code 30050}。
     */
    public McpConnectionTestResult test(McpServer server) {
        Instant startedAt = Instant.now();

        // ① SSRF 校验（🔴 拒绝则不发起任何网络连接）
        if (!ssrfGuard.evaluate(server.getEndpoint()).allowed()) {
            // 🔴 两条审计都要写：ssrf_rejected（EX-029）+ connection_test（§7.4.2 强制）
            ssrfGuard.writeRejectAudit(server, SsrfGuard.AuditMode.SAME_TRANSACTION);
            String eventId = testWriter.persistOutcome(server, McpCheckResult.SSRF_REJECTED, 0);
            return result(server, McpCheckResult.SSRF_REJECTED, 0, 0, eventId);
        }

        // ② 建连 + 握手 + tools/list（🔴 事务外）
        McpCheckResult outcome;
        int toolCount = 0;
        try {
            List<McpToolDescriptor> tools = mcpClient.listTools(server);
            toolCount = tools.size();
            outcome = toolCount > 0 ? McpCheckResult.SUCCESS : McpCheckResult.NO_TOOLS_AVAILABLE;
        } catch (McpTransportException e) {
            // 🔴 只取分类，不取上游正文
            outcome = e.failure().checkResult();
            log.warn("MCP 连接测试未通过：mcpId={} result={}", server.getId(), outcome.literal());
        }
        long latencyMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli();

        // ③ 落库 + 强制审计（同一短事务）
        String eventId = testWriter.persistOutcome(server, outcome, toolCount);
        return result(server, outcome, latencyMs, toolCount, eventId);
    }

    private McpConnectionTestResult result(McpServer server, McpCheckResult outcome,
                                          long latencyMs, int toolCount, String auditEventId) {
        return new McpConnectionTestResult(String.valueOf(server.getId()),
                server.getTransport(), outcome.literal(), outcome.healthy(), latencyMs, toolCount,
                TimeFormat.iso(Instant.now()), auditEventId);
    }
}
