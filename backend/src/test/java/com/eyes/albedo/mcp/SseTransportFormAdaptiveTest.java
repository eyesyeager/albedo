package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.LegacySseUpstream;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code sse} 传输<b>双形态</b>组件测试（ADR-016 / api-spec §7.6.1 <b>G6′</b> / §8.3 <b>I 组</b>）。
 *
 * <p>🔴 <b>零 Spring 上下文、零数据库</b>：直接以桩配置装配 {@link SseTransport}，
 * 对着 JDK {@link LegacySseUpstream}（真实 HTTP + 真实 SSE 流）跑完整链路 ——
 * 因此本轮"只做代码实现 + 单元测试、不落库"的约束下依然能把 ADR-016 的形态与边界全部验证。
 *
 * <p><b>覆盖矩阵</b>：
 * <table border="1">
 *   <caption>I 组核对项</caption>
 *   <tr><th>项</th><th>断言</th></tr>
 *   <tr><td>I1</td><td>同步应答形态零回归（{@code initialize} 探测的 {@code -32601} 被忽略）</td></tr>
 *   <tr><td>I2</td><td>异步推送形态端到端成功（{@code tools/list} + {@code tools/call}）</td></tr>
 *   <tr><td>I3</td><td>会话端点跨源 → {@code protocol_incompatible}；🔴 {@code event: endpoint}
 *       二次投毒被忽略</td></tr>
 *   <tr><td>I3′</td><td>🔴 <b>GET 建流的安全类非 2xx 不退化</b>：{@code 401} → {@code auth_failed}、
 *       {@code 302} → {@code protocol_incompatible}，两者<b>均不得</b>产生任何 POST</td></tr>
 *   <tr><td>I5</td><td>{@code close_early} → 🔴 <b>立即</b>失败，不等满预算</td></tr>
 *   <tr><td>I6</td><td>{@code sse_legacy_enabled=false} → 完整回到 G6 行为</td></tr>
 *   <tr><td>I7</td><td>{@code never_push} → {@code timeout} 且总耗时 &lt; 2× 预算</td></tr>
 *   <tr><td>I8</td><td>连续多次 exchange 后线程数不单调增长、每次都是新流（无跨调用复用）</td></tr>
 * </table>
 */
class SseTransportFormAdaptiveTest {

    /** 常规用例预算。 */
    private static final Duration BUDGET = Duration.ofSeconds(5);
    /** 超时用例预算（越小越能凸显"累加不得超预算"）。 */
    private static final Duration TIGHT_BUDGET = Duration.ofSeconds(2);
    /** 默认流字节上限（与 sys_config 默认值同量级，桩值，🔴 生产取值来自 sys_config）。 */
    private static final String STREAM_MAX_BYTES = "1048576";
    /** 压小后的流字节上限：足以容纳 initialize 结果帧，远小于噪声总量（128KB）。 */
    private static final String SMALL_STREAM_MAX_BYTES = "4096";
    private static final int LEAK_PROBE_ROUNDS = 30;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LegacySseUpstream upstream;
    private ConfigService configService;
    private SseTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        upstream = new LegacySseUpstream();
        configService = mock(ConfigService.class);
        // 🔴 桩出 sys_config 的两个新键：流字节上限（NUMBER）+ 异步形态开关（BOOLEAN，fail-closed）
        when(configService.find(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES))
                .thenReturn(Optional.of(STREAM_MAX_BYTES));
        when(configService.getBoolean(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_SSE_LEGACY_ENABLED), anyBoolean())).thenReturn(true);
        // 与 HttpClientConfig 保持一致：唯一 Bean、🔴 followRedirects=NEVER
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        transport = new SseTransport(httpClient, new BusinessConfig(configService),
                configService, objectMapper);
    }

    @AfterEach
    void tearDown() {
        upstream.close();
    }

    // ===================== I2：异步推送形态（MCP 2024-11-05） =====================

    @Test
    @DisplayName("🔴 I2｜异步形态：POST 回 202 空体 → 结果从 GET 流按 id 匹配取回（tools/list）")
    void asyncFormResolvesResultFromStream() {
        String response = exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/list", BUDGET);

        assertTrue(response.contains("wsa-SearchPro"), response);
        assertEquals(1, upstream.streamCount(), "一次 exchange 恰好建立一条 GET 流");
    }

    @Test
    @DisplayName("🔴 I2｜异步形态：tools/call 结果从流推送，报文完整回传给 JSON-RPC 层")
    void asyncFormSupportsToolsCall() {
        String response = exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/call", BUDGET);

        assertTrue(response.contains("async-result-ok"), response);
        assertTrue(response.contains("\"jsonrpc\":\"2.0\""), "必须回完整 JSON-RPC 报文");
    }

    @Test
    @DisplayName("🔴 I4｜噪声帧（notifications / ping / id 不匹配）一律丢弃，仍取到正确结果")
    void noiseFramesAreDiscarded() {
        String response = exchange(LegacySseUpstream.SCENARIO_NOISE_THEN_RESULT, "tools/call",
                BUDGET);

        assertTrue(response.contains("async-result-ok"), response);
    }

    // ===================== I1：同步应答形态零回归 =====================

    @Test
    @DisplayName("🔴 I1｜同步形态不回归：POST 响应体内带结果；initialize 探测的 -32601 必须被忽略")
    void syncFormStillReadsResultFromPostBody() {
        String response = exchange(LegacySseUpstream.SCENARIO_SYNC, "tools/list", BUDGET);

        assertTrue(response.contains("wsa-SearchPro"), response);
        assertTrue(response.contains("-32601") == false,
                "🔴 探测用 initialize 的 error 不得被当成结果返回");
        assertEquals(2, upstream.postCount(),
                "同步形态恰好 2 次 POST（initialize 探测 + 目标方法），🔴 无 initialized 通知");
    }

    @Test
    @DisplayName("上游不支持 GET 建流（405）→ 退化为直接 POST 原 endpoint（既有兼容行为不变）")
    void degradesToDirectPostWhenGetUnsupported() {
        String response = exchange(LegacySseUpstream.SCENARIO_NO_GET, "tools/list", BUDGET);

        assertTrue(response.contains("wsa-SearchPro"), response);
        assertEquals(0, upstream.streamCount(), "GET 不被支持时不应建立任何流");
        assertTrue(upstream.postCount() > 0,
                "🔴 反向锚点：405 属于「上游不支持 GET」，必须真的退化去 POST —— "
                        + "否则下面两个安全用例的 postCount()==0 就是「永远为真」的假断言");
    }

    // ===================== 🔴 安全类非 2xx 不退化（ADR-016 失败分类对照表前两行） =====================

    @Test
    @DisplayName("🔴 GET 建流回 401 → AUTH_FAILED（30052 + 审计），且上游未收到任何 POST（不得退化掩盖鉴权失败）")
    void authFailureOnGetIsNotDegradedToDirectPost() {
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_GET_401, "tools/list", BUDGET));

        assertEquals(McpFailure.AUTH_FAILED, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
        assertTrue(ex.failure().securityRelevant(),
                "🔴 401 必须落在 securityRelevant 分类上，否则 mcp.auth_failed 审计不会被写");
        assertEquals(0, upstream.postCount(),
                "🔴 401 绝不允许退化为直接 POST：退化会把 auth_failed 掩盖成 protocol_incompatible，"
                        + "凭据失效的运维信号随之丢失");
        assertEquals(0, upstream.streamCount(), "非 2xx 不得计为已建流");
    }

    @Test
    @DisplayName("🔴 GET 建流回 302（带 Location）→ PROTOCOL_INCOMPATIBLE，且上游未收到任何 POST（SSRF 旁路必须关闭）")
    void redirectOnGetIsNotDegradedToDirectPost() {
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_GET_302, "tools/list", BUDGET));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
        assertEquals(0, upstream.postCount(),
                "🔴 302 退化为直接 POST 等于绕过 followRedirects=NEVER —— "
                        + "删掉 SseTransport 里的 isRedirect 判断，本断言必须变红");
        assertEquals(0, upstream.streamCount(), "非 2xx 不得计为已建流");
    }

    // ===================== I3：SSRF 纪律 =====================

    @Test
    @DisplayName("🔴 I3｜会话端点跨源 → protocol_incompatible（不改判 30050，且绝不请求该地址）")
    void crossOriginSessionEndpointRejected() {
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_CROSS_ORIGIN, "tools/list", BUDGET));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(0, upstream.postCount(), "🔴 跨源端点必须在发起任何 POST 之前被拒");
    }

    @Test
    @DisplayName("🔴 I3｜event: endpoint 只认第一次出现的值：流内二次投毒被忽略，链路仍成功")
    void secondEndpointEventIsIgnored() {
        String response = exchange(LegacySseUpstream.SCENARIO_ENDPOINT_POISON, "tools/call",
                BUDGET);

        assertTrue(response.contains("async-result-ok"),
                "🔴 必须继续用第一个（合法）会话端点，且不得请求被投毒的地址");
    }

    // ===================== I5 / I7：失败路径与 deadline 预算 =====================

    @Test
    @DisplayName("🔴 I7｜never_push → TIMEOUT/30051，且总耗时不得达到 2× 预算（deadline 预算制）")
    void neverPushTimesOutWithinOneBudget() {
        long began = System.nanoTime();
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_NEVER_PUSH, "tools/call", TIGHT_BUDGET));
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

        assertEquals(McpFailure.TIMEOUT, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.TOOL_TIMEOUT, ex.errorCode());
        assertTrue(elapsedMillis < TIGHT_BUDGET.toMillis() * 2,
                "🔴 GET 建流 + 各次 POST + 各次等待必须共用一个预算，实际 " + elapsedMillis
                        + "ms / 预算 " + TIGHT_BUDGET.toMillis() + "ms");
    }

    @Test
    @DisplayName("🔴 I5｜close_early：流在给出结果前被关闭 → 立即 protocol_incompatible（不等满预算）")
    void streamClosedBeforeResultFailsImmediately() {
        long began = System.nanoTime();
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_CLOSE_EARLY, "tools/call", BUDGET));
        long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertTrue(elapsedMillis < BUDGET.toMillis() / 2,
                "🔴 上游关流即失败，绝不白占线程等满预算，实际 " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("🔴 流累计字节超 mcp.sse_stream_max_bytes → 强制关流 + protocol_incompatible")
    void oversizeStreamRejected() {
        when(configService.find(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES))
                .thenReturn(Optional.of(SMALL_STREAM_MAX_BYTES));

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_OVERSIZE_STREAM, "tools/call", BUDGET));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    @Test
    @DisplayName("🔴 异步形态下 initialize 返回 error → protocol_incompatible（握手必须成功）")
    void initializeErrorRejectedInAsyncForm() {
        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_INIT_ERROR, "tools/list", BUDGET));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
    }

    // ===================== I6：一键止血开关（fail-closed） =====================

    @Test
    @DisplayName("🔴 I6｜mcp.sse_legacy_enabled=false → 异步形态判 protocol_incompatible（回到 G6 行为）")
    void legacyFormCanBeDisabled() {
        when(configService.getBoolean(eq(ConfigKeys.GROUP_MCP),
                eq(ConfigKeys.MCP_SSE_LEGACY_ENABLED), anyBoolean())).thenReturn(false);

        McpTransportException ex = assertThrows(McpTransportException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/list", BUDGET));

        assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
        assertEquals(com.eyes.albedo.common.ErrorCode.MCP_UNAVAILABLE, ex.errorCode());
    }

    @Test
    @DisplayName("🔴 mcp.sse_stream_max_bytes 缺失 → 50003（BusinessConfig 不提供代码默认值兜底）")
    void missingStreamLimitIsInternalError() {
        when(configService.find(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES))
                .thenReturn(Optional.empty());

        com.eyes.albedo.common.BusinessException ex = assertThrows(
                com.eyes.albedo.common.BusinessException.class,
                () -> exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/list", BUDGET));

        assertEquals(com.eyes.albedo.common.ErrorCode.INTERNAL_ERROR, ex.getCode());
    }

    // ===================== I8：泄漏与"不复用 session" =====================

    @Test
    @DisplayName("🔴 I8｜连续多次异步 exchange：线程数不单调增长，且每次都是新流（禁止跨调用复用 session）")
    void repeatedExchangesLeakNothing() {
        for (int round = 0; round < 5; round++) {
            exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/call", BUDGET);
        }
        int baseline = Thread.activeCount();

        for (int round = 0; round < LEAK_PROBE_ROUNDS; round++) {
            String response = exchange(LegacySseUpstream.SCENARIO_SUCCESS, "tools/call", BUDGET);
            assertTrue(response.contains("async-result-ok"));
        }

        int after = Thread.activeCount();
        assertTrue(after <= baseline + LEAK_PROBE_ROUNDS / 3,
                "🔴 线程数疑似随 exchange 次数单调增长：baseline=" + baseline + " after=" + after);
        assertEquals(5 + LEAK_PROBE_ROUNDS, upstream.streamCount(),
                "🔴 每次 exchange 必须新建一条流（不得跨调用复用 session）");
    }

    // ===================== 辅助 =====================

    private String exchange(String scenario, String method, Duration budget) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "wsa-SearchPro");
        McpRpcRequest request = McpRpcMessages.request(objectMapper, method, params);
        return transport.exchange(server(scenario), Map.of(), request, budget);
    }

    private McpServer server(String scenario) {
        McpServer server = new McpServer();
        server.setId(1L);
        server.setTenantId("gift");
        server.setMcpKey("wsa");
        server.setName("Legacy SSE 上游");
        server.setTransport(McpServer.TRANSPORT_SSE);
        server.setEndpoint(upstream.endpoint(scenario));
        server.setAuthType(McpServer.AUTH_TYPE_NONE);
        server.setStatus(McpServer.STATUS_ENABLED);
        return server;
    }
}
