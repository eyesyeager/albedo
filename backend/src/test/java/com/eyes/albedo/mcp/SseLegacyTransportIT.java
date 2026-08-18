package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

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
 * {@code sse} 传输的<b>双形态</b>验收（ADR-016 / api-spec §7.6.1 <b>G6′</b>、§8.3 I 组）。
 *
 * <p><b>覆盖矩阵</b>：
 * <table border="1">
 *   <caption>I 组核对项</caption>
 *   <tr><th>项</th><th>断言</th></tr>
 *   <tr><td>I1 不回归</td><td>同步形态 {@code /mock-mcp/sse} 行为完全不变
 *       （{@code initialize} 探测收到 {@code -32601} 被忽略）</td></tr>
 *   <tr><td>I2 异步可用</td><td>{@code /mock-mcp/sse-legacy} 的 {@code tools/list} +
 *       {@code tools/call} 端到端成功</td></tr>
 *   <tr><td>I4 结果匹配</td><td>{@code noise_then_result}：通知 / 日志 / id 不匹配帧一律丢弃</td></tr>
 *   <tr><td>I5 立即失败</td><td>{@code close_early}：🔴 不等满预算</td></tr>
 *   <tr><td>I6 止血开关</td><td>{@code sse_legacy_enabled=false} → {@code protocol_incompatible}</td></tr>
 *   <tr><td>I7 预算</td><td>{@code never_push}：总耗时 ≤ 预算，🔴 不得 2×</td></tr>
 *   <tr><td>I8 无泄漏</td><td>连续多次异步 exchange 后 JVM 线程数不单调增长</td></tr>
 * </table>
 *
 * <p>🔴 <b>配置纪律</b>：全部宽松值（SSRF 例外、压小的超时与流字节上限）均由
 * {@link SysConfigOverride} 在<b>本类范围内</b>覆盖并在 {@code @AfterEach} 还原
 * （api-spec §7.13 G12：严禁把宽松值持久化进共享库）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockMcpServer.class)
class SseLegacyTransportIT {

    /** 异步形态用例的调用预算（秒）：足够跑完 GET + 3 次 POST，又不至于让超时用例太慢。 */
    private static final int BUDGET_SECONDS = 5;
    /** {@code never_push} 用例的预算（秒）：越小越能凸显"不得 2× 预算"。 */
    private static final int TIMEOUT_BUDGET_SECONDS = 2;
    /** 压小后的流字节上限：足以容纳 initialize 结果帧，远小于 Mock 推送的噪声总量。 */
    private static final String SMALL_STREAM_MAX_BYTES = "4096";
    /** 泄漏专项的 exchange 次数（🔴 @测试 的 200 次专项在其验收清单内，此处为回归护栏）。 */
    private static final int LEAK_PROBE_ROUNDS = 30;

    @LocalServerPort
    private int port;

    @Autowired
    private McpJsonRpcClient client;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;

    private SysConfigOverride override;
    private String tenantId;

    @BeforeEach
    void setUp() {
        tenantId = "ssel" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "sse 双形态测试租户", tenantId + ".test.invalid");
        override = new SysConfigOverride(jdbcTemplate, configService)
                // 🔴 api-spec §7.13：环回 Mock 需要这两项例外；用完立即还原
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS, "false")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS,
                        "[\"127.0.0.1/32\"]")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS,
                        String.valueOf(BUDGET_SECONDS))
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_DISCOVER_TIMEOUT_SECONDS,
                        String.valueOf(BUDGET_SECONDS));
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    // ===================== I2：异步推送形态可用 =====================

    @Test
    @DisplayName("🔴 I2｜异步形态（2024-11-05）：tools/list + tools/call 端到端成功（结果从 GET 流推送）")
    void asyncFormWorksEndToEnd() {
        McpServer server = legacyServer(MockMcpServer.SCENARIO_SUCCESS);

        List<McpToolDescriptor> tools = client.listTools(server);
        assertEquals(2, tools.size(), "异步形态必须能取回工具清单");
        assertEquals("lookup_user", tools.get(0).name());

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"10086\"}");
        assertFalse(result.isError());
        assertTrue(result.content().contains("10086"), result.content());
    }

    @Test
    @DisplayName("🔴 I1｜不回归：同步应答形态行为不变（initialize 探测的 -32601 必须被忽略）")
    void syncFormNotRegressed() {
        McpServer server = server("/mock-mcp/sse", MockMcpServer.SCENARIO_SUCCESS);

        List<McpToolDescriptor> tools = client.listTools(server);
        assertEquals(2, tools.size());

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"10086\"}");
        assertFalse(result.isError());
        assertTrue(result.content().contains("张三"), result.content());
    }

    @Test
    @DisplayName("🔴 I4｜noise_then_result：通知 / 日志 / id 不匹配帧一律丢弃，仍取到正确结果")
    void noiseFramesAreDiscarded() {
        McpServer server = legacyServer(MockMcpServer.SCENARIO_NOISE_THEN_RESULT);

        McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"10086\"}");

        assertFalse(result.isError());
        assertTrue(result.content().contains("10086"), result.content());
    }

    // ===================== I5 / I7：失败路径与预算 =====================

    @Test
    @DisplayName("🔴 I7｜never_push：判 TIMEOUT/30051，且总耗时不得达到 2× 预算（deadline 预算制）")
    void neverPushTimesOutWithinBudget() {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS,
                String.valueOf(TIMEOUT_BUDGET_SECONDS));
        McpServer server = legacyServer(MockMcpServer.SCENARIO_NEVER_PUSH);

        long began = System.nanoTime();
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.callTool(server, "lookup_user", "{\"uid\":\"1\"}"));
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

        assertEquals(McpFailure.TIMEOUT, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.TOOL_TIMEOUT, ex.errorCode());
        long budgetMillis = TIMEOUT_BUDGET_SECONDS * 1000L;
        assertTrue(elapsedMillis < budgetMillis * 2,
                "🔴 GET + 多次 POST + 等待必须共用一个预算，实际耗时 " + elapsedMillis
                        + "ms，预算 " + budgetMillis + "ms");
    }

    @Test
    @DisplayName("🔴 I5｜close_early：流在给出结果前被关闭 → 立即 protocol_incompatible（不等满预算）")
    void closeEarlyFailsImmediately() {
        McpServer server = legacyServer(MockMcpServer.SCENARIO_CLOSE_EARLY);

        long began = System.nanoTime();
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.callTool(server, "lookup_user", "{\"uid\":\"1\"}"));
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertTrue(elapsedMillis < BUDGET_SECONDS * 1000L / 2,
                "🔴 上游关流即失败，绝不白占线程等满预算，实际耗时 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("🔴 oversize_stream：累计字节超 mcp.sse_stream_max_bytes → protocol_incompatible")
    void oversizeStreamRejected() {
        // ⚠️ 该取值故意小于 tool.result_max_bytes（启动不变量只在启动时校验），@AfterEach 立即还原
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES,
                SMALL_STREAM_MAX_BYTES);
        McpServer server = legacyServer(MockMcpServer.SCENARIO_OVERSIZE_STREAM);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.callTool(server, "lookup_user", "{\"uid\":\"1\"}"));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    @Test
    @DisplayName("🔴 init_error：异步形态下 initialize 返回 error → protocol_incompatible（握手必须成功）")
    void initializeErrorRejected() {
        McpServer server = legacyServer(MockMcpServer.SCENARIO_INIT_ERROR);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    // ===================== I6：一键止血开关 =====================

    @Test
    @DisplayName("🔴 I6｜mcp.sse_legacy_enabled=false → 异步形态判 protocol_incompatible（完整回到 G6 行为）")
    void legacyFormCanBeDisabled() {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_LEGACY_ENABLED, "false");
        McpServer server = legacyServer(MockMcpServer.SCENARIO_SUCCESS);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
    }

    @Test
    @DisplayName("🔴 读取侧 fail-closed：开关值不可解析（写坏）时按 false 处理，绝不悄悄持有长连接")
    void unparsableSwitchFailsClosed() {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_LEGACY_ENABLED, "yes-please");
        McpServer server = legacyServer(MockMcpServer.SCENARIO_SUCCESS);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> client.listTools(server));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    // ===================== I8：线程泄漏专项（AR-020 应对⑤） =====================

    @Test
    @DisplayName("🔴 I8｜连续多次异步 exchange 后 JVM 线程数不单调增长（不新增线程池 / finally 关流）")
    void asyncExchangesDoNotLeakThreads() {
        McpServer server = legacyServer(MockMcpServer.SCENARIO_SUCCESS);
        // 预热：让 HttpClient 内部 executor 与 Tomcat 线程达到稳态后再取基线
        for (int round = 0; round < 5; round++) {
            client.callTool(server, "lookup_user", "{\"uid\":\"1\"}");
        }
        int baseline = Thread.activeCount();

        for (int round = 0; round < LEAK_PROBE_ROUNDS; round++) {
            McpCallResult result = client.callTool(server, "lookup_user", "{\"uid\":\"1\"}");
            assertFalse(result.isError());
        }

        int after = Thread.activeCount();
        assertTrue(after <= baseline + LEAK_PROBE_ROUNDS / 2,
                "🔴 线程数疑似随 exchange 次数单调增长：baseline=" + baseline + " after=" + after
                        + "（每次 exchange 都必须在 finally 关流，且不得新增线程池）");
    }

    // ===================== 辅助 =====================

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private McpServer legacyServer(String scenario) {
        return server("/mock-mcp/sse-legacy", scenario);
    }

    private McpServer server(String path, String scenario) {
        McpServer server = new McpServer();
        server.setId(1L);
        server.setTenantId(tenantId);
        server.setMcpKey("mock-legacy");
        server.setName("Mock MCP（异步形态）");
        server.setTransport(McpServer.TRANSPORT_SSE);
        server.setEndpoint(baseUrl() + path + "?scenario=" + scenario);
        server.setAuthType(McpServer.AUTH_TYPE_NONE);
        // 🔴 tools/call 预算 = min(sys_config, 本列)：置大值以确保预算由 sys_config 决定
        server.setTimeoutSeconds(60);
        server.setStatus(McpServer.STATUS_ENABLED);
        return server;
    }
}
