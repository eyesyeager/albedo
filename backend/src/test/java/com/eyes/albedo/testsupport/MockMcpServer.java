package com.eyes.albedo.testsupport;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 内置 Mock MCP 服务（api-spec §7.13，AC-MCP-006 / EX-030 / DEC-009）。
 *
 * <p>🔴 <b>位于 {@code src/test}，绝不进入生产打包</b>（Maven 不打包 test 类），
 * 且不通过组件扫描注册 —— 只能由测试用 {@code @Import(MockMcpServer.class)} 显式装配，
 * 因此不可能成为生产攻击面。
 *
 * <p>🔴 <b>不可替代</b>（EX-030 / DEC-009）：Mock 不可用即判 M3 验收失败，
 * <b>不得</b>以真实外部 MCP 服务替代（真实服务无法稳定复现鉴权失败 / 超时 / 协议异常 /
 * 超大结果这四类故障，也不能把真实凭据带进 CI）。
 *
 * <p><b>路径</b>（🔴 不在 {@code /api/v1/**} 下，不受业务契约约束、不返回 {@code Result} 包装）：
 * <ul>
 *   <li>{@code POST /mock-mcp/streamable-http} —— 首选传输：单端点 POST，回裸 JSON</li>
 *   <li>{@code GET  /mock-mcp/sse} —— 回退传输：SSE 建流，回 {@code event: endpoint} 会话地址</li>
 *   <li>{@code POST /mock-mcp/sse/messages} —— 会话端点：回 SSE 帧包裹的 JSON-RPC 响应</li>
 *   <li>🔴 {@code GET  /mock-mcp/sse-legacy} —— <b>异步推送形态</b>（MCP 2024-11-05）：
 *       保持流打开，先推 {@code event: endpoint}，结果稍后异步推送到该流</li>
 *   <li>🔴 {@code POST /mock-mcp/sse-legacy/messages} —— 恒回 {@code 202} + 空体，
 *       把结果写回对应 {@code sessionId} 的流</li>
 * </ul>
 *
 * <p>🔴 <b>同步形态端点 {@code /mock-mcp/sse} 的行为保持不变</b>（api-spec §7.13 / G6′ ⑨）：
 * 现有 {@code TRANSPORT_SSE} 用例与 AC-MCP-003 必须原样通过。
 * ⚠️ 客户端（V1.4.0 起）会先发一次 {@code initialize}，本 Mock 对未知方法回 {@code -32601} ——
 * 🔴 该 {@code error} 是<b>探测信号</b>，客户端必须忽略，不得据此判缺陷。
 *
 * <p><b>场景开关</b>：请求头 {@code X-Mock-Scenario}（缺省 {@code success}）。
 * ⚠️ <b>同时支持查询参数 {@code ?scenario=xxx}</b>（🔴 测试专用扩展，已回报 @架构师）：
 * 原因是 {@code McpClient} 按契约<b>只允许</b>发送 JSON-RPC 载荷与鉴权头，
 * 没有"给某个 MCP 附加任意自定义头"的能力（那会成为一条绕过治理的旁路）。
 * 把场景放进 {@code mcp_servers.endpoint} 的 query 里，
 * 才能在<b>不给生产代码开后门</b>的前提下驱动全部故障场景。
 * 请求头形态保留，供人工 {@code curl} 排障使用。
 * <table border="1">
 *   <caption>7 类覆盖场景</caption>
 *   <tr><th>场景</th><th>行为</th><th>被断言的分类</th></tr>
 *   <tr><td>{@code success}</td><td>2 个工具；调用回文本结果</td><td>{@code success} / 30052 不出现</td></tr>
 *   <tr><td>{@code auth_failed}</td><td>HTTP 401</td><td>{@code auth_failed} / 30052</td></tr>
 *   <tr><td>{@code timeout}</td><td>睡 {@link #TIMEOUT_SLEEP_MILLIS} 毫秒</td><td>{@code timeout} / 30051</td></tr>
 *   <tr><td>{@code protocol_error}</td><td>回非 JSON-RPC 报文</td><td>{@code protocol_incompatible} / 30052</td></tr>
 *   <tr><td>{@code invalid_params}</td><td>JSON-RPC {@code -32602}</td><td>30053</td></tr>
 *   <tr><td>{@code execution_error}</td><td>{@code result.isError=true}</td><td>30057</td></tr>
 *   <tr><td>{@code oversize_result}</td><td>返回 &gt;1MB 文本</td><td>字节截断 + {@code truncated=true}</td></tr>
 *   <tr><td>{@code new_tool}</td><td>多返回 1 个工具</td><td>{@code new} + 默认禁用</td></tr>
 *   <tr><td>{@code schema_changed}</td><td>已有工具换 Schema</td><td>{@code schema_changed} + 自动降级</td></tr>
 *   <tr><td>{@code no_tools}</td><td>空工具列表</td><td>{@code no_tools_available}</td></tr>
 * </table>
 * 「非法地址」不由 Mock 承担：它必须在<b>发起连接之前</b>被拒绝，
 * 因此用"把 endpoint 改成环回/云元数据地址"的方式验证（{@code McpSsrfGuardIT}）。
 *
 * <p>🔴 SSRF 例外（api-spec §7.13）：Mock 位于环回地址，测试需临时把
 * {@code mcp.allowed_internal_cidrs} 置为 {@code ["127.0.0.1/32"]} 且 {@code mcp.require_https=false}；
 * 🔴 生产两项必须为 {@code []} / {@code true}（{@code StartupChecker} 在 prod profile 强制拦死）。
 */
@Slf4j
@TestConfiguration
@RestController
@RequestMapping("/mock-mcp")
public class MockMcpServer {

    public static final String HEADER_SCENARIO = "X-Mock-Scenario";

    public static final String SCENARIO_SUCCESS = "success";
    public static final String SCENARIO_AUTH_FAILED = "auth_failed";
    public static final String SCENARIO_TIMEOUT = "timeout";
    public static final String SCENARIO_PROTOCOL_ERROR = "protocol_error";
    public static final String SCENARIO_INVALID_PARAMS = "invalid_params";
    public static final String SCENARIO_EXECUTION_ERROR = "execution_error";
    public static final String SCENARIO_OVERSIZE_RESULT = "oversize_result";
    public static final String SCENARIO_NEW_TOOL = "new_tool";
    public static final String SCENARIO_SCHEMA_CHANGED = "schema_changed";
    public static final String SCENARIO_NO_TOOLS = "no_tools";

    /**
     * 🔴 V1.2.2 新增（ADR-018 ⑤ⓔ）：上游以 {@code isError=true} + <b>参数错误文本</b>回应
     * （复刻腾讯云 WSA 对 "Mode 参数非法" 的真实反应）。
     *
     * <p>用途：断言该文本被<b>如实回灌</b>进下一轮上下文（{@code role=tool}）——
     * 这正是 BUG-MCP-001 的修复判据：模型必须拿到"哪个参数错了"，
     * 否则只能换个写法反复重试（每次还要用户再确认一次）。
     */
    public static final String SCENARIO_PARAM_ERROR = "param_error";

    /** {@code param_error} 场景回给模型的上游参数诊断（🔴 测试据此做包含断言）。 */
    public static final String PARAM_ERROR_TEXT =
            "invalid parameter Mode: expected one of 0,1,2 but got 3";

    // ===== 🔴 V1.2.0 新增：异步推送形态（MCP 2024-11-05）专用场景（api-spec §7.13） =====
    /** POST 恒回 202，🔴 永不向流推送任何结果 → 断言 {@code timeout}/30051 且总耗时 ≤ 预算。 */
    public static final String SCENARIO_NEVER_PUSH = "never_push";
    /** 收到目标方法 POST 后 🔴 直接关闭流 → 断言 {@code protocol_incompatible} 且<b>立即</b>失败。 */
    public static final String SCENARIO_CLOSE_EARLY = "close_early";
    /** 向流推送超过 {@code mcp.sse_stream_max_bytes} 的噪声帧 → 断言超限关流。 */
    public static final String SCENARIO_OVERSIZE_STREAM = "oversize_stream";
    /** {@code initialize} 回 JSON-RPC {@code error} → 断言 {@code protocol_incompatible}。 */
    public static final String SCENARIO_INIT_ERROR = "init_error";
    /** 先推大量噪声帧（通知 / 日志 / ping / id 不匹配）再推正确结果 → 断言仍 {@code success}。 */
    public static final String SCENARIO_NOISE_THEN_RESULT = "noise_then_result";

    /** 异步形态流的空闲超时（远大于测试给出的调用预算，确保"超时"由客户端一侧先到）。 */
    public static final long LEGACY_STREAM_TIMEOUT_MILLIS = 30_000L;
    /** {@code oversize_stream} 每帧噪声字节数。 */
    public static final int NOISE_FRAME_BYTES = 2048;
    /** {@code oversize_stream} 推送的噪声帧数（2048 × 64 = 128KB，测试把上限压到远小于此）。 */
    public static final int NOISE_FRAME_COUNT = 64;

    /** {@code timeout} 场景的睡眠时长：必须显著大于测试给出的请求超时。 */
    public static final long TIMEOUT_SLEEP_MILLIS = 2000L;
    /** {@code oversize_result} 场景的结果字节数（> {@code tool.result_max_bytes} 默认 1MB）。 */
    public static final int OVERSIZE_BYTES = 1024 * 1024 + 4096;

    /** 调用计数（供"未授权工具不得发起网络调用"这类断言使用）。 */
    private final AtomicInteger callCount = new AtomicInteger();

    /**
     * 异步形态的 {@code sessionId → 事件流}（🔴 仅 {@code src/test}，见 api-spec §7.13）。
     */
    private final Map<String, SseEmitter> legacySessions = new ConcurrentHashMap<>();

    public int callCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    /**
     * {@code streamable_http} 传输端点：单端点 POST，回裸 JSON。
     */
    @PostMapping(value = "/streamable-http", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> streamableHttp(
            @RequestBody String body,
            @RequestHeader(value = HEADER_SCENARIO, required = false) String scenarioHeader,
            @RequestParam(value = "scenario", required = false) String scenarioParam) {
        return handle(body, firstPresent(scenarioHeader, scenarioParam), false);
    }

    /**
     * {@code sse} 传输的建流端点：回 {@code event: endpoint} 给出会话地址。
     *
     * <p>🔴 会话地址上<b>回显 scenario</b>：{@code URI.resolve(绝对路径)} 会丢掉原 query，
     * 若不回显，SSE 链路的故障场景就无法驱动。
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> sseHandshake(
            @RequestHeader(value = HEADER_SCENARIO, required = false) String scenarioHeader,
            @RequestParam(value = "scenario", required = false) String scenarioParam) {
        String scenario = firstPresent(scenarioHeader, scenarioParam);
        if (SCENARIO_AUTH_FAILED.equals(scenario)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("");
        }
        // 🔴 相对路径：客户端会用 base.resolve(...) 拼接，并强制校验同源
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: endpoint\ndata: /mock-mcp/sse/messages?scenario=" + scenario + "\n\n");
    }

    /**
     * {@code sse} 传输的会话端点：回 SSE 帧包裹的 JSON-RPC 响应。
     */
    @PostMapping(value = "/sse/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> sseMessages(
            @RequestBody String body,
            @RequestHeader(value = HEADER_SCENARIO, required = false) String scenarioHeader,
            @RequestParam(value = "scenario", required = false) String scenarioParam) {
        return handle(body, firstPresent(scenarioHeader, scenarioParam), true);
    }

    /**
     * 🔴 跨主机会话端点（安全用例）：返回一个<b>不同源</b>的 endpoint，
     * 客户端必须拒绝（否则等于绕过已完成的 SSRF 校验）。
     */
    @GetMapping(value = "/sse-cross-origin", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> sseCrossOrigin() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: endpoint\ndata: http://169.254.169.254/latest/meta-data\n\n");
    }

    // ============ 🔴 V1.2.0：MCP 2024-11-05 异步推送形态（api-spec §7.13 / ADR-016 ⑨） ============

    /**
     * 🔴 异步形态建流端点：<b>保持流打开</b>，先推 {@code event: endpoint}，
     * 随后按场景把 JSON-RPC 结果<b>异步推送</b>到该流。
     *
     * <p>这正是腾讯云 WSA MCP 的实测形态（ADR-016 背景表）：
     * {@code sessionId} 绑定在<b>这条流</b>上，流一关 session 即失效 ——
     * 因此客户端必须在整个 POST 期间持续持有并读取本流。
     *
     * <p>🔴 {@code sessionId → emitter} 的映射只存在于 {@code src/test} 的内存 Map 中，
     * 生产代码<b>永远不会</b>有这样的会话表（G9′：系统不持有跨请求 MCP 会话状态）。
     */
    @GetMapping(value = "/sse-legacy", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sseLegacyHandshake(
            @RequestHeader(value = HEADER_SCENARIO, required = false) String scenarioHeader,
            @RequestParam(value = "scenario", required = false) String scenarioParam) {
        String scenario = firstPresent(scenarioHeader, scenarioParam);
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(LEGACY_STREAM_TIMEOUT_MILLIS);
        legacySessions.put(sessionId, emitter);
        emitter.onCompletion(() -> legacySessions.remove(sessionId));
        emitter.onTimeout(() -> legacySessions.remove(sessionId));
        emitter.onError(error -> legacySessions.remove(sessionId));
        try {
            // 🔴 相对路径 + 回显 sessionId/scenario：客户端会 base.resolve(...) 并强制校验同源
            emitter.send(SseEmitter.event().name("endpoint")
                    .data("/mock-mcp/sse-legacy/messages?sessionId=" + sessionId
                            + "&scenario=" + scenario));
        } catch (IOException e) {
            // 🔴 ADR-021 / api-spec §8.3 L6 ⓑ：代码库中**永久禁止** SseEmitter.completeWithError(...)
            //    —— 它会触发错误分派，把 JSON 写进一条已提交为 text/event-stream 的响应
            //    （SseWriter.markBroken 同样明文选用 complete()）。
            //    本处是测试桩，写不出 endpoint 帧时只需正常关流：客户端会因缺帧而按握手失败处理。
            legacySessions.remove(sessionId);
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 🔴 异步形态会话端点：<b>恒返回 202 Accepted + 空体</b>，结果写回对应 {@code sessionId} 的流。
     *
     * <p>{@code notifications/initialized} 不回任何东西（它是通知，无 id）。
     */
    @PostMapping("/sse-legacy/messages")
    public ResponseEntity<Void> sseLegacyMessages(
            @RequestBody String body,
            @RequestParam("sessionId") String sessionId,
            @RequestHeader(value = HEADER_SCENARIO, required = false) String scenarioHeader,
            @RequestParam(value = "scenario", required = false) String scenarioParam) {
        String scenario = firstPresent(scenarioHeader, scenarioParam);
        String method = extractMethod(body);
        String id = extractId(body);
        SseEmitter emitter = legacySessions.get(sessionId);
        if (emitter != null && !"notifications/initialized".equals(method)) {
            pushLegacy(emitter, scenario, method, id);
        }
        // 🔴 202 + 空体：这就是"异步形态"的判据
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private void pushLegacy(SseEmitter emitter, String scenario, String method, String id) {
        if (SCENARIO_NEVER_PUSH.equals(scenario)) {
            // 🔴 永不推送：客户端必须在预算内收敛为 timeout（不得 2× 预算）
            return;
        }
        boolean initialize = "initialize".equals(method);
        if (initialize) {
            pushFrame(emitter, SCENARIO_INIT_ERROR.equals(scenario)
                    ? rpcError(id, -32603, "handshake refused")
                    : initializeResult(id));
            return;
        }
        if (SCENARIO_CLOSE_EARLY.equals(scenario)) {
            // 🔴 目标方法到达后直接关流、不推结果 → 客户端必须**立即**判 protocol_incompatible
            emitter.complete();
            return;
        }
        if (SCENARIO_OVERSIZE_STREAM.equals(scenario)) {
            pushNoise(emitter, NOISE_FRAME_COUNT);
            return;
        }
        if (SCENARIO_NOISE_THEN_RESULT.equals(scenario)) {
            pushNoise(emitter, 3);
            // id 不匹配的报文：客户端必须丢弃而不是误当结果
            pushFrame(emitter, "{\"jsonrpc\":\"2.0\",\"id\":\"not-my-id\",\"result\":{\"tools\":[]}}");
        }
        if ("tools/list".equals(method)) {
            pushFrame(emitter, toolsList(SCENARIO_SUCCESS, id));
            return;
        }
        if ("tools/call".equals(method)) {
            callCount.incrementAndGet();
            pushFrame(emitter, toolsCall(SCENARIO_SUCCESS, id));
            return;
        }
        pushFrame(emitter, rpcError(id, -32601, "Method not found"));
    }

    /** 噪声帧：{@code notifications/*} / 日志 / ping —— 客户端必须一律丢弃。 */
    private void pushNoise(SseEmitter emitter, int frames) {
        String filler = "n".repeat(NOISE_FRAME_BYTES);
        for (int index = 0; index < frames; index++) {
            pushFrame(emitter, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\","
                    + "\"params\":{\"level\":\"info\",\"data\":\"" + filler + "\"}}");
        }
    }

    private void pushFrame(SseEmitter emitter, String json) {
        try {
            emitter.send(SseEmitter.event().name("message").data(json));
        } catch (IOException | IllegalStateException e) {
            // 客户端已断开（正常路径：exchange 结束即 cancel），忽略
            log.debug("Mock MCP 异步形态推送失败（客户端可能已断开）：{}", e.getClass().getSimpleName());
        }
    }

    private String initializeResult(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"protocolVersion\":"
                + "\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true}},"
                + "\"serverInfo\":{\"name\":\"Mock MCP Legacy\",\"version\":\"1.0.0\"}}}";
    }

    private String rpcError(String id, int code, String message) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message + "\"}}";
    }

    /**
     * 🔴 重定向场景（安全用例）：客户端 {@code followRedirects=NEVER}，必须判 30052。
     */
    @PostMapping("/redirect")
    public ResponseEntity<String> redirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "http://127.0.0.1/internal")
                .body("");
    }

    private ResponseEntity<String> handle(String body, String scenario, boolean sseFramed) {
        String effective = scenario == null || scenario.isBlank() ? SCENARIO_SUCCESS : scenario;
        String method = extractMethod(body);
        String id = extractId(body);

        if (SCENARIO_AUTH_FAILED.equals(effective)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"unauthorized\"}");
        }
        if (SCENARIO_TIMEOUT.equals(effective)) {
            sleepQuietly();
            return wrap("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{}}", sseFramed);
        }
        if (SCENARIO_PROTOCOL_ERROR.equals(effective)) {
            // 非 JSON-RPC 2.0 报文（缺 jsonrpc 字段）
            return wrap("{\"ok\":true,\"payload\":\"not-json-rpc\"}", sseFramed);
        }

        if ("tools/list".equals(method)) {
            return wrap(toolsList(effective, id), sseFramed);
        }
        if ("tools/call".equals(method)) {
            callCount.incrementAndGet();
            return wrap(toolsCall(effective, id), sseFramed);
        }
        // 未知方法 → JSON-RPC -32601（客户端应判 protocol_incompatible / 30052）
        return wrap("{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}", sseFramed);
    }

    private String toolsList(String scenario, String id) {
        if (SCENARIO_NO_TOOLS.equals(scenario)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                    + "\",\"result\":{\"tools\":[],\"nextCursor\":null}}";
        }
        StringBuilder tools = new StringBuilder();
        tools.append(tool("lookup_user", "查询用户",
                SCENARIO_SCHEMA_CHANGED.equals(scenario)
                        // 🔴 偷换 Schema：新增一个必填参数（提权路径的典型形态）
                        ? "{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"},"
                        + "\"scope\":{\"type\":\"string\"}},\"required\":[\"uid\",\"scope\"]}"
                        : "{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"}},"
                        + "\"required\":[\"uid\"]}"));
        tools.append(',').append(tool("create_ticket", "创建工单",
                "{\"type\":\"object\",\"properties\":{\"title\":{\"type\":\"string\"}},"
                        + "\"required\":[\"title\"]}"));
        if (SCENARIO_NEW_TOOL.equals(scenario)) {
            tools.append(',').append(tool("export_all", "导出数据",
                    "{\"type\":\"object\",\"properties\":{}}"));
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":["
                + tools + "],\"nextCursor\":null}}";
    }

    private String toolsCall(String scenario, String id) {
        if (SCENARIO_INVALID_PARAMS.equals(scenario)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                    + "\",\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}";
        }
        if (SCENARIO_EXECUTION_ERROR.equals(scenario)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"upstream business failure\"}],"
                    + "\"isError\":true}}";
        }
        if (SCENARIO_PARAM_ERROR.equals(scenario)) {
            // 🔴 ADR-018 ⓔ：isError=true + **参数错误文本**（复刻 WSA 的真实反应）
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"" + PARAM_ERROR_TEXT + "\"}],"
                    + "\"isError\":true}}";
        }
        if (SCENARIO_OVERSIZE_RESULT.equals(scenario)) {
            String payload = "壹".repeat(OVERSIZE_BYTES / 3 + 16);
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"" + payload + "\"}],\"isError\":false}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":"
                + "[{\"type\":\"text\",\"text\":\"{\\\"name\\\":\\\"张三\\\",\\\"uid\\\":\\\"10086\\\"}\"}],"
                + "\"isError\":false}}";
    }

    private String tool(String name, String description, String schema) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description
                + "\",\"inputSchema\":" + schema + "}";
    }

    /**
     * SSE 帧包裹（验证客户端的"帧剥离"能力）。
     */
    private ResponseEntity<String> wrap(String json, boolean sseFramed) {
        if (!sseFramed) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: message\ndata: " + json + "\n\n");
    }

    private String extractMethod(String body) {
        return extract(body, "\"method\"");
    }

    private String extractId(String body) {
        String id = extract(body, "\"id\"");
        return id == null ? "mock" : id;
    }

    private String extract(String body, String field) {
        if (body == null) {
            return null;
        }
        int at = body.indexOf(field);
        if (at < 0) {
            return null;
        }
        int colon = body.indexOf(':', at);
        int start = body.indexOf('"', colon + 1);
        int end = body.indexOf('"', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        return body.substring(start + 1, end);
    }

    private String firstPresent(String header, String param) {
        if (header != null && !header.isBlank()) {
            return header;
        }
        return param == null || param.isBlank() ? SCENARIO_SUCCESS : param;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(TIMEOUT_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
