package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * SSRF 校验单测（ADR-009 / api-spec §7.6.3 / AC-MCP-004 / EX-029）。
 *
 * <p>🔴 关键断言：
 * <ul>
 *   <li>协议 / 端口 / 环回 / 私网 / <b>云元数据 169.254.169.254</b> 一律拒绝</li>
 *   <li>拒绝时<b>不发起任何网络连接</b>，且<b>强制写审计</b> {@code mcp.ssrf_rejected}</li>
 *   <li>白名单命中可豁免（{@code test} profile 放行环回 Mock 的唯一通道）</li>
 *   <li>🔴 错误消息与审计 reason <b>不含</b>解析出的 IP / 端口</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SsrfGuardTest {

    private static final List<String> BLOCKED = List.of("127.0.0.0/8", "::1/128", "0.0.0.0/8",
            "169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10",
            "fc00::/7", "fe80::/10");

    @Mock
    private ConfigService configService;
    @Mock
    private BusinessConfig businessConfig;
    @Mock
    private AuditService auditService;

    private SsrfGuard guard;

    @BeforeEach
    void setUp() {
        when(businessConfig.requireBoolean(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_REQUIRE_HTTPS))).thenReturn(true);
        blockedCidrs(BLOCKED);
        allowedCidrs(List.of());
        guard = new SsrfGuard(configService, businessConfig, auditService);
    }

    @Test
    @DisplayName("🔴 环回地址 → 拒绝（不发起任何连接）")
    void loopbackRejected() {
        assertFalse(guard.evaluate("https://127.0.0.1/v1").allowed());
        assertFalse(guard.evaluate("https://[::1]/v1").allowed());
    }

    @Test
    @DisplayName("🔴 云元数据 169.254.169.254 → 拒绝（SSRF 的头号目标）")
    void cloudMetadataRejected() {
        assertFalse(guard.evaluate("https://169.254.169.254/latest/meta-data").allowed());
    }

    @Test
    @DisplayName("🔴 私网地址（10/172.16/192.168/100.64）→ 拒绝")
    void privateRangesRejected() {
        assertFalse(guard.evaluate("https://10.0.0.5/v1").allowed());
        assertFalse(guard.evaluate("https://172.16.3.4/v1").allowed());
        assertFalse(guard.evaluate("https://192.168.1.1/v1").allowed());
        assertFalse(guard.evaluate("https://100.64.0.1/v1").allowed());
    }

    @Test
    @DisplayName("🔴 非 HTTPS → 拒绝（mcp.require_https=true）")
    void plainHttpRejected() {
        assertFalse(guard.evaluate("http://8.8.8.8/v1").allowed());
    }

    @Test
    @DisplayName("🔴 非 http(s) 协议（file/gopher/ftp）→ 拒绝（经典 SSRF 载体）")
    void exoticSchemesRejected() {
        when(businessConfig.requireBoolean(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_REQUIRE_HTTPS))).thenReturn(false);
        assertFalse(guard.evaluate("file:///etc/passwd").allowed());
        assertFalse(guard.evaluate("gopher://8.8.8.8:70/x").allowed());
        assertFalse(guard.evaluate("ftp://8.8.8.8/x").allowed());
    }

    @Test
    @DisplayName("🔴 非 443 端口 → 拒绝（未命中内网白名单时）")
    void nonStandardPortRejected() {
        assertFalse(guard.evaluate("https://8.8.8.8:8443/v1").allowed());
        assertTrue(guard.evaluate("https://8.8.8.8:443/v1").allowed());
        assertTrue(guard.evaluate("https://8.8.8.8/v1").allowed());
    }

    @Test
    @DisplayName("空 / 非法 URL → 拒绝（fail-closed，不得因解析异常而放行）")
    void malformedRejected() {
        assertFalse(guard.evaluate(null).allowed());
        assertFalse(guard.evaluate("").allowed());
        assertFalse(guard.evaluate("not a url").allowed());
        assertFalse(guard.evaluate("https://").allowed());
    }

    @Test
    @DisplayName("DNS 解析失败 → 拒绝（无法证明安全就不放行）")
    void unresolvableRejected() {
        assertFalse(guard.evaluate("https://this-host-does-not-exist.invalid/v1").allowed());
    }

    @Test
    @DisplayName("内网白名单命中 → 放行，且可用显式端口（test profile 放行环回 Mock 的唯一通道）")
    void whitelistAllowsLoopbackWithPort() {
        when(businessConfig.requireBoolean(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_REQUIRE_HTTPS))).thenReturn(false);
        allowedCidrs(List.of("127.0.0.1/32"));
        guard = new SsrfGuard(configService, businessConfig, auditService);

        SsrfGuard.Decision decision = guard.evaluate("http://127.0.0.1:18080/mock-mcp/streamable-http");
        assertTrue(decision.allowed());
        assertTrue(decision.whitelisted());
    }

    @Test
    @DisplayName("🔴 requireAllowed 拒绝 → 30050 + 强制审计 mcp.ssrf_rejected，且消息不含 IP/端口")
    void rejectWritesAuditAndThrows30050() {
        McpServer server = server("https://10.0.0.5:8080/v1");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireAllowed(server, SsrfGuard.AuditMode.SAME_TRANSACTION));

        assertEquals(ErrorCode.TOOL_DENIED, ex.getCode());
        assertEquals(SsrfGuard.REJECT_MESSAGE, ex.getMessage());
        // 🔴 不回显解析出的地址与端口
        assertFalse(ex.getMessage().contains("10.0.0.5"));
        assertFalse(ex.getMessage().contains("8080"));

        org.mockito.ArgumentCaptor<AuditEvent> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditService).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("mcp.ssrf_rejected", event.action());
        assertEquals("denied", event.result());
        assertEquals("gift", event.tenantId());
        assertFalse(String.valueOf(event.reason()).contains("10.0.0.5"),
                "🔴 审计 reason 也不得含内部地址");
    }

    @Test
    @DisplayName("🔴 流式内拒绝走独立短事务；审计失败不上抛（绝不中断 SSE 流，ADR-010）")
    void runtimeRejectUsesNewTransactionAndSwallowsAuditFailure() {
        McpServer server = server("https://127.0.0.1/v1");
        when(auditService.recordInNewTransaction(any(AuditEvent.class)))
                .thenThrow(new IllegalStateException("audit down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard.requireAllowed(server, SsrfGuard.AuditMode.NEW_TRANSACTION));

        assertEquals(ErrorCode.TOOL_DENIED, ex.getCode(),
                "🔴 审计失败不得把 30050 变成 50003 或异常穿透");
        verify(auditService, never()).record(any(AuditEvent.class));
    }

    @Test
    @DisplayName("放行时不写任何审计（避免审计被正常流量淹没）")
    void allowWritesNoAudit() {
        guard.requireAllowed(server("https://8.8.8.8/v1"), SsrfGuard.AuditMode.SAME_TRANSACTION);
        verify(auditService, never()).record(any(AuditEvent.class));
        verify(auditService, never()).recordInNewTransaction(any(AuditEvent.class));
    }

    @Test
    @DisplayName("AuditMode.NONE 不写审计（供批量配置校验聚合 violations）")
    void auditModeNone() {
        guard.writeRejectAudit(server("https://127.0.0.1/v1"), SsrfGuard.AuditMode.NONE);
        verify(auditService, never()).record(any(AuditEvent.class));
    }

    @Test
    @DisplayName("🔴 同一 endpoint 的判定必须稳定（避免「偶尔放行」的不确定安全行为）")
    void deterministicDecision() {
        assertEquals(guard.evaluate("https://8.8.8.8/v1").allowed(),
                guard.evaluate("https://8.8.8.8/v1").allowed());
        assertNotEquals(guard.evaluate("https://8.8.8.8/v1").allowed(),
                guard.evaluate("https://10.0.0.5/v1").allowed());
    }

    private void blockedCidrs(List<String> cidrs) {
        when(configService.getJson(eq(ConfigKeys.GROUP_MCP), eq(ConfigKeys.MCP_BLOCKED_IP_CIDRS),
                any(TypeReference.class), any())).thenReturn(cidrs);
    }

    private void allowedCidrs(List<String> cidrs) {
        when(configService.getJson(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS), any(TypeReference.class), any()))
                .thenReturn(cidrs);
    }

    private McpServer server(String endpoint) {
        McpServer server = new McpServer();
        server.setId(12L);
        server.setTenantId("gift");
        server.setMcpKey("crm");
        server.setName("CRM");
        server.setEndpoint(endpoint);
        server.setTransport(McpServer.TRANSPORT_STREAMABLE_HTTP);
        server.setAuthType(McpServer.AUTH_TYPE_NONE);
        server.setStatus(McpServer.STATUS_ENABLED);
        return server;
    }
}
