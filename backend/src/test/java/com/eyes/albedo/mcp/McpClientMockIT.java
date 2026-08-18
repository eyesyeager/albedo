package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.dto.McpCallResult;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.MockMcpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MCP 客户端全链路测试（内置 Mock MCP，api-spec §7.6 / §7.13）。
 *
 * <p><b>REQ-MCP-003 · AC-MCP-003 / AC-MCP-006 / EX-030</b>
 *
 * <p>🔴 <b>两种传输都必须走通</b>（AC-MCP-003）：
 * {@code streamable_http}（裸 JSON 响应）与 {@code sse}（GET 建流 + 会话端点 + SSE 帧响应）。
 *
 * <p>🔴 <b>上游结果映射逐条断言</b>（api-spec §7.6.4）：
 * 成功 / 鉴权失败(30052) / 超时(30051) / 协议异常(30052) / 参数非法(30053) /
 * 业务失败(isError→30057) / 重定向(30052) / 超大结果。
 *
 * <p>数据纪律：MCP 配置写在<b>临时租户</b>下并在 {@code @AfterEach} 精确清理；
 * {@code sys_config} 的两项 SSRF 例外用 {@link SysConfigOverride} 覆盖后<b>立即还原</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockMcpServer.class)
class McpClientMockIT {

    @LocalServerPort
    private int port;

    @Autowired
    private McpJsonRpcClient client;
    @Autowired
    private StreamableHttpTransport streamableHttpTransport;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private SysConfigOverride override;
    private String tenantId;

    @BeforeEach
    void setUp() {
        tenantId = "mcli" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "MCP 客户端测试租户", tenantId + ".test.invalid");
        // 🔴 api-spec §7.13：环回 Mock 需要这两项例外；用完立即还原
        override = new SysConfigOverride(jdbcTemplate, configService)
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS, "false")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS,
                        "[\"127.0.0.1/32\"]")
                // 超时场景：把调用/发现超时压到 1s，Mock 睡 2s
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS, "1")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_DISCOVER_TIMEOUT_SECONDS, "1");
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    // ===================== 传输：两种都必须走通（AC-MCP-003） =====================

    @Test
    @DisplayName("AC-MCP-003｜streamable_http：tools/list + tools/call 成功（裸 JSON 响应）")
    void streamableHttpSuccess() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP, MockMcpServer.SCENARIO_SUCCESS);

        List<McpToolDescriptor> tools = client.listTools(server);
        assertEquals(2, tools.size());
        assertEquals("lookup_user", tools.get(0).name());
        assertTrue(tools.get(0).hasSchema());

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"10086\"}");
        assertFalse(result.isError());
        assertTrue(result.content().contains("张三"), result.content());
    }

    @Test
    @DisplayName("AC-MCP-003｜sse：GET 建流取会话端点 → POST → SSE 帧响应（帧剥离生效）")
    void sseTransportSuccess() {
        McpServer server = server(McpServer.TRANSPORT_SSE, MockMcpServer.SCENARIO_SUCCESS);

        List<McpToolDescriptor> tools = client.listTools(server);
        assertEquals(2, tools.size());

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"10086\"}");
        assertFalse(result.isError());
        assertTrue(result.content().contains("10086"));
    }

    // ===================== 故障分类（api-spec §7.6.4 / §7.4.2） =====================

    @Test
    @DisplayName("AC-MCP-006｜鉴权失败（HTTP 401）→ AUTH_FAILED / 30052 / auth_failed")
    void authFailed() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_AUTH_FAILED);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.AUTH_FAILED, ex.failure());
        assertEquals(ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
        assertEquals(McpCheckResult.AUTH_FAILED, ex.failure().checkResult());
        assertTrue(ex.failure().securityRelevant(), "鉴权失败必须被标记为需写审计");
    }

    @Test
    @DisplayName("AC-MCP-006｜超时 → TIMEOUT / 30051 / timeout（超时取 sys_config，无字面量）")
    void timeout() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_TIMEOUT);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.TIMEOUT, ex.failure());
        assertEquals(ErrorCode.TOOL_TIMEOUT, ex.errorCode());
        assertEquals(McpCheckResult.TIMEOUT, ex.failure().checkResult());
    }

    @Test
    @DisplayName("AC-MCP-006｜协议异常（非 JSON-RPC 2.0）→ PROTOCOL_INCOMPATIBLE / 30052")
    void protocolError() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_PROTOCOL_ERROR);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
    }

    @Test
    @DisplayName("JSON-RPC -32602 → INVALID_PARAMS / 30053（与本地 Tool Schema 失败同码）")
    void invalidParams() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_INVALID_PARAMS);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.callTool(server, "lookup_user", "{\"uid\":1}"));

        assertEquals(McpFailure.INVALID_PARAMS, ex.failure());
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.errorCode());
    }

    @Test
    @DisplayName("result.isError=true → 由返回值承载（调用方判 30057，非传输异常）")
    void executionError() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_EXECUTION_ERROR);

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"1\"}");

        assertTrue(result.isError());
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, McpFailure.EXECUTION_FAILED.errorCode());
    }

    @Test
    @DisplayName("未知方法（-32601）→ PROTOCOL_INCOMPATIBLE / 30052")
    void methodNotFound() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP, MockMcpServer.SCENARIO_SUCCESS);
        server.setEndpoint(baseUrl() + "/mock-mcp/streamable-http");

        // 直接用传输层发一个未登记方法，验证 JSON-RPC error 分类
        // 🔴 V1.4.0（ADR-016 #1）：exchange 入参改为 McpRpcRequest —— 传输层需要 id 才能在
        //    sse 异步形态的事件流上匹配结果；构造统一走 McpRpcMessages（禁止两处各拼一遍报文）。
        McpRpcRequest request = McpRpcMessages.request(objectMapper, "resources/list", Map.of());
        String response = streamableHttpTransport.exchange(server, Map.of(), request,
                Duration.ofSeconds(5));
        assertTrue(response.contains("-32601"), response);
    }

    @Test
    @DisplayName("🔴 HTTP 3xx → 30052（followRedirects=NEVER，规避「302 跳内网」绕过 SSRF）")
    void redirectRejected() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP, MockMcpServer.SCENARIO_SUCCESS);
        server.setEndpoint(baseUrl() + "/mock-mcp/redirect");

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
    }

    @Test
    @DisplayName("🔴 sse 会话端点跨主机（指向云元数据）→ 拒绝（否则绕过已完成的 SSRF 校验）")
    void crossOriginSessionEndpointRejected() {
        McpServer server = server(McpServer.TRANSPORT_SSE, MockMcpServer.SCENARIO_SUCCESS);
        server.setEndpoint(baseUrl() + "/mock-mcp/sse-cross-origin");

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));
        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    @Test
    @DisplayName("AC-MCP-006｜超大结果（>1MB）由上游返回后完整取回（截断在 tool 包做，ADR-011）")
    void oversizeResult() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP,
                MockMcpServer.SCENARIO_OVERSIZE_RESULT);
        // 超大响应体读取需要更宽松的超时
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS, "20");

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"1\"}");

        assertFalse(result.isError());
        assertTrue(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > 1024 * 1024, "Mock 必须回出超过 1MB 的结果以驱动截断用例");
    }

    @Test
    @DisplayName("🔴 transport=stdio → 30060（不落库、不调用）")
    void stdioRejected() {
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP, MockMcpServer.SCENARIO_SUCCESS);
        server.setTransport(McpServer.TRANSPORT_STDIO);

        BusinessException ex = assertThrows(BusinessException.class, () -> client.listTools(server));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("超时口径：tools/call 取 min(sys_config, mcp_servers.timeout_seconds)")
    void callTimeoutTakesMinimum() {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS, "30");
        McpServer server = server(McpServer.TRANSPORT_STREAMABLE_HTTP, MockMcpServer.SCENARIO_SUCCESS);
        server.setTimeoutSeconds(5);

        assertEquals(Duration.ofSeconds(5), client.callTimeout(server));

        server.setTimeoutSeconds(60);
        assertEquals(Duration.ofSeconds(30), client.callTimeout(server));
    }

    // ===================== 辅助 =====================

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private McpServer server(String transport, String scenario) {
        String path = McpServer.TRANSPORT_SSE.equals(transport)
                ? "/mock-mcp/sse" : "/mock-mcp/streamable-http";
        McpServer server = new McpServer();
        server.setId(1L);
        server.setTenantId(tenantId);
        server.setMcpKey("mock");
        server.setName("Mock MCP");
        server.setTransport(transport);
        server.setEndpoint(baseUrl() + path + "?scenario=" + scenario);
        server.setAuthType(McpServer.AUTH_TYPE_NONE);
        server.setTimeoutSeconds(30);
        server.setStatus(McpServer.STATUS_ENABLED);
        return server;
    }
}
