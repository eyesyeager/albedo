package com.eyes.albedo.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 🔴 <b>MCP 旧版「HTTP+SSE」(2024-11-05) 上游替身</b>（仅 {@code src/test}，ADR-016 ⑨ 的验收基础设施）。
 *
 * <p><b>为什么不用 Spring 的 {@code MockMcpServer} 而另起一个 JDK {@link HttpServer}</b>：
 * 本轮（ADR-016 代码实现轮）<b>不落库</b>，而 {@code mcp.sse_legacy_enabled} /
 * {@code mcp.sse_stream_max_bytes} 两键尚未插入共享库 → 任何 {@code @SpringBootTest} 都会被
 * {@code StartupChecker} 的"缺键即启动失败"挡住。用 JDK 内置 HTTP 服务器 + 桩配置驱动
 * {@code SseTransport}，可以在<b>零 Spring 上下文、零数据库</b>的前提下把 ADR-016 的
 * 全部形态与边界跑通（{@code MockMcpServer} 的 {@code /mock-mcp/sse-legacy} 端点同步保留，
 * 供下一轮落库后的联调 IT 使用）。
 *
 * <p><b>形态（严格复刻腾讯云 WSA MCP 实测行为）</b>：
 * <pre>
 * GET  /sse?scenario=…   → 200 text/event-stream，立即推 event: endpoint，🔴 流保持打开
 * POST /messages?…       → 🔴 202 Accepted + 空体；结果异步写回那条 GET 流
 * </pre>
 *
 * <p>⚠️ 本类为测试替身，内部使用固定线程池承载"保持打开的流"——
 * 这是<b>测试侧</b>的实现细节，与生产代码"不新增线程池"的纪律无关
 * （{@code McpThreadDisciplineScanTest} 只扫 {@code src/main} 的 {@code mcp} 包）。
 */
public final class LegacySseUpstream implements AutoCloseable {

    /** 完整异步链路：initialize → 目标方法结果均从流推送。 */
    public static final String SCENARIO_SUCCESS = "success";
    /** POST 恒回 202，🔴 永不推送任何结果（断言 timeout 且总耗时 ≤ 预算）。 */
    public static final String SCENARIO_NEVER_PUSH = "never_push";
    /** 收到目标方法后 🔴 直接关流（断言立即 protocol_incompatible）。 */
    public static final String SCENARIO_CLOSE_EARLY = "close_early";
    /** 推送超过流字节上限的噪声（断言超限关流）。 */
    public static final String SCENARIO_OVERSIZE_STREAM = "oversize_stream";
    /** initialize 回 JSON-RPC error（断言 protocol_incompatible）。 */
    public static final String SCENARIO_INIT_ERROR = "init_error";
    /** 先推噪声与 id 不匹配帧，再推正确结果（断言仍成功）。 */
    public static final String SCENARIO_NOISE_THEN_RESULT = "noise_then_result";
    /** 🔴 流内二次投毒：先给合法端点，紧接着再给一个跨源端点（断言只认第一次）。 */
    public static final String SCENARIO_ENDPOINT_POISON = "endpoint_poison";
    /** 🔴 会话端点跨源（断言 protocol_incompatible，且绝不请求该地址）。 */
    public static final String SCENARIO_CROSS_ORIGIN = "cross_origin";
    /** 同步应答形态：POST 响应体内直接带 JSON-RPC 结果（🔴 零回归基线）。 */
    public static final String SCENARIO_SYNC = "sync";
    /** 上游不支持 GET 建流（405）→ 退化为直接 POST 原 endpoint。 */
    public static final String SCENARIO_NO_GET = "no_get";
    /**
     * 🔴 GET 建流回 {@code 401}（鉴权失败）——<b>安全类非 2xx 不得退化</b>。
     *
     * <p>断言 {@code AUTH_FAILED} <b>且上游未收到任何 POST</b>：若退化为直接 POST，
     * 鉴权失败会被掩盖成 {@code protocol_incompatible}，{@code mcp.auth_failed} 审计随之丢失。
     */
    public static final String SCENARIO_GET_401 = "get_401";
    /**
     * 🔴 GET 建流回 {@code 302}（带 {@code Location} 指向内网）——<b>安全类非 2xx 不得退化</b>。
     *
     * <p>断言 {@code PROTOCOL_INCOMPATIBLE} <b>且上游未收到任何 POST</b>：一旦退化为直接 POST，
     * 等于把 {@code followRedirects=NEVER} 的语义绕开（SSRF 旁路）。
     */
    public static final String SCENARIO_GET_302 = "get_302";

    /** 流最长存活时间：远大于用例预算，确保"超时"由客户端一侧先到。 */
    private static final long STREAM_TTL_MILLIS = 10_000L;
    /** 队列轮询间隔。 */
    private static final long POLL_MILLIS = 50L;
    /**
     * 心跳（SSE 注释行）间隔：🔴 <b>用于尽快感知客户端已关流</b> ——
     * 客户端 exchange 结束会 cancel 订阅，若替身不写数据就发现不了断开，
     * 承载该流的线程会一直占到 TTL，几十次连续用例即耗尽替身线程池（表现为莫名 timeout）。
     */
    private static final long HEARTBEAT_MILLIS = 200L;
    /** 关流信号。 */
    private static final String CLOSE_SIGNAL = "__close__";
    /** {@code oversize_stream} 的噪声帧尺寸与数量（2KB × 64 = 128KB）。 */
    private static final int NOISE_FRAME_BYTES = 2048;
    private static final int NOISE_FRAME_COUNT = 64;
    /**
     * 🔴 {@code get_302} 的 {@code Location}：典型云元数据地址。
     *
     * <p>取这个值是为了让"退化为直接 POST 就等于 SSRF 旁路"这件事在用例里一目了然 ——
     * 客户端<b>绝不允许</b>请求它（{@code followRedirects=NEVER} 已保证不跟随，
     * 本用例进一步保证<b>也不回落去 POST 原 endpoint</b>）。
     */
    private static final String REDIRECT_TARGET = "http://169.254.169.254/latest/meta-data";

    private final HttpServer server;
    private final ExecutorService executor;
    private final Map<String, BlockingQueue<String>> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger streamCount = new AtomicInteger();
    private final AtomicInteger postCount = new AtomicInteger();

    public LegacySseUpstream() throws IOException {
        this.executor = Executors.newFixedThreadPool(8);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/sse", this::handleStreamEndpoint);
        this.server.createContext("/messages", this::handleSessionEndpoint);
        this.server.start();
    }

    /** {@code mcp_servers.endpoint} 取值。 */
    public String endpoint(String scenario) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse?scenario=" + scenario;
    }

    /** 已建立的 GET 流条数（🔴 用于断言"每次 exchange 一条新流、无跨调用复用"）。 */
    public int streamCount() {
        return streamCount.get();
    }

    /**
     * 收到的 POST <b>总</b>次数（🔴 含 {@code /messages} 会话端点与退化路径的 {@code /sse}）。
     *
     * <p>用途有两类：① 同步形态断言"恰 2 次 POST（initialize 探测 + 目标方法）"；
     * ② 🔴 安全类断言"上游<b>未收到任何</b> POST"（{@code cross_origin} / {@code get_401} /
     * {@code get_302}）—— 因此<b>两个 context 都必须计数</b>，只统计 {@code /messages}
     * 会让退化路径的 POST 逃过断言。
     */
    public int postCount() {
        return postCount.get();
    }

    @Override
    public void close() {
        sessions.values().forEach(queue -> queue.offer(CLOSE_SIGNAL));
        server.stop(0);
        executor.shutdownNow();
    }

    // ===================== GET /sse：建流（或退化用的同步 POST） =====================

    private void handleStreamEndpoint(HttpExchange exchange) throws IOException {
        String scenario = scenario(exchange);
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            // 退化路径（no_get）：客户端直接 POST 原 endpoint，按同步形态应答。
            // 🔴 这里必须同样计数：退化 POST 打的是 /sse 而非 /messages，
            //    不计数则 get_401 / get_302 的"上游未收到任何 POST"断言会形同虚设（永远为真）。
            postCount.incrementAndGet();
            respondSync(exchange, scenario);
            return;
        }
        if (SCENARIO_NO_GET.equals(scenario)) {
            // 🔴 上游只支持 POST（腾讯云 WSA 的反例：POST /sse 才是 405，此处取反向场景）
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (SCENARIO_GET_401.equals(scenario)) {
            // 🔴 鉴权失败：客户端必须直接判 auth_failed，不得退化为直接 POST
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (SCENARIO_GET_302.equals(scenario)) {
            // 🔴 重定向：客户端既不得跟随，也不得退化为直接 POST（否则 SSRF 旁路打开）
            exchange.getResponseHeaders().add("Location", REDIRECT_TARGET);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        streamCount.incrementAndGet();
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        String sessionId = UUID.randomUUID().toString();
        try (OutputStream out = exchange.getResponseBody()) {
            if (SCENARIO_CROSS_ORIGIN.equals(scenario)) {
                // 🔴 跨源会话端点：客户端必须拒绝且绝不请求它
                write(out, frame("endpoint", "http://169.254.169.254/latest/meta-data"));
                return;
            }
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            sessions.put(sessionId, queue);
            write(out, frame("endpoint", sessionPath(sessionId, scenario)));
            if (SCENARIO_ENDPOINT_POISON.equals(scenario)) {
                // 🔴 二次投毒：客户端必须忽略后续 endpoint 事件
                write(out, frame("endpoint", "http://169.254.169.254/latest/meta-data"));
            }
            pump(out, queue);
        } catch (IOException e) {
            // 客户端已 cancel（正常路径：exchange 结束即强制关流）——测试替身无需处理
            return;
        } finally {
            sessions.remove(sessionId);
            exchange.close();
        }
    }

    /** 把队列里的帧写到流上，直到收到关流信号、客户端断开或超过存活时间。 */
    private void pump(OutputStream out, BlockingQueue<String> queue) throws IOException {
        long deadline = System.nanoTime() + STREAM_TTL_MILLIS * 1_000_000L;
        long nextHeartbeat = System.nanoTime() + HEARTBEAT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            String payload;
            try {
                payload = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (payload == null) {
                if (System.nanoTime() >= nextHeartbeat) {
                    // SSE 注释行：客户端一侧被忽略，但写失败即说明对端已关流
                    write(out, ": keep-alive\n\n");
                    nextHeartbeat = System.nanoTime() + HEARTBEAT_MILLIS * 1_000_000L;
                }
                continue;
            }
            if (CLOSE_SIGNAL.equals(payload)) {
                return;
            }
            write(out, payload);
        }
    }

    // ===================== POST /messages：恒 202 + 空体 =====================

    private void handleSessionEndpoint(HttpExchange exchange) throws IOException {
        postCount.incrementAndGet();
        String scenario = scenario(exchange);
        if (SCENARIO_SYNC.equals(scenario)) {
            // 🔴 同步应答形态：POST 响应体内直接带 JSON-RPC 结果（零回归基线）
            respondSync(exchange, scenario);
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String sessionId = query(exchange, "sessionId");
        String method = extract(body, "\"method\"");
        String id = extract(body, "\"id\"");
        BlockingQueue<String> queue = sessionId == null ? null : sessions.get(sessionId);
        if (queue != null && !"notifications/initialized".equals(method)) {
            enqueueResponse(queue, scenario, method, id);
        }
        // 🔴 202 Accepted + 空体 —— 这就是"异步形态"的判据
        exchange.sendResponseHeaders(202, -1);
        exchange.close();
    }

    private void enqueueResponse(BlockingQueue<String> queue, String scenario, String method,
                                String id) {
        if (SCENARIO_NEVER_PUSH.equals(scenario)) {
            return;
        }
        if ("initialize".equals(method)) {
            queue.offer(frame("message", SCENARIO_INIT_ERROR.equals(scenario)
                    ? rpcError(id) : initializeResult(id)));
            return;
        }
        if (SCENARIO_CLOSE_EARLY.equals(scenario)) {
            queue.offer(CLOSE_SIGNAL);
            return;
        }
        if (SCENARIO_OVERSIZE_STREAM.equals(scenario)) {
            String filler = "n".repeat(NOISE_FRAME_BYTES);
            for (int index = 0; index < NOISE_FRAME_COUNT; index++) {
                queue.offer(frame("message", notification(filler)));
            }
            return;
        }
        if (SCENARIO_NOISE_THEN_RESULT.equals(scenario)) {
            queue.offer(frame("message", notification("noise")));
            queue.offer(frame("ping", "{}"));
            // 🔴 id 不匹配的报文：客户端必须丢弃，不得误当结果
            queue.offer(frame("message",
                    "{\"jsonrpc\":\"2.0\",\"id\":\"not-my-id\",\"result\":{\"tools\":[]}}"));
        }
        queue.offer(frame("message", methodResult(method, id)));
    }

    // ===================== 同步形态应答（零回归基线） =====================

    private void respondSync(HttpExchange exchange, String scenario) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = extract(body, "\"method\"");
        String id = extract(body, "\"id\"");
        // 🔴 initialize 在同步形态下回 -32601：客户端必须忽略该 error（它只是探测信号）
        String payload = "initialize".equals(method) ? rpcError(id) : methodResult(method, id);
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    /** 供同步形态用的会话端点（POST 直接带结果）。 */
    public String syncEndpoint() {
        return endpoint(SCENARIO_SYNC);
    }

    // ===================== 报文构造 =====================

    private String sessionPath(String sessionId, String scenario) {
        // 🔴 相对路径：客户端会 base.resolve(...) 并强制校验同源
        return "/messages?sessionId=" + sessionId + "&scenario=" + scenario;
    }

    private String initializeResult(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"protocolVersion\":"
                + "\"2024-11-05\",\"capabilities\":{\"tools\":{\"listChanged\":true}},"
                + "\"serverInfo\":{\"name\":\"Legacy SSE Upstream\",\"version\":\"1.0.0\"}}}";
    }

    private String methodResult(String method, String id) {
        if ("tools/list".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"tools\":[{\"name\":"
                    + "\"wsa-SearchPro\",\"description\":\"联网搜索\",\"inputSchema\":{\"type\":"
                    + "\"object\",\"properties\":{\"Query\":{\"type\":\"string\"}},\"required\":"
                    + "[\"Query\"]}}],\"nextCursor\":null}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":{\"content\":[{\"type\":"
                + "\"text\",\"text\":\"async-result-ok\"}],\"isError\":false}}";
    }

    private String rpcError(String id) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id
                + "\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
    }

    private String notification(String data) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{\"level\":"
                + "\"info\",\"data\":\"" + data + "\"}}";
    }

    private String frame(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private void write(OutputStream out, String payload) throws IOException {
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private String scenario(HttpExchange exchange) {
        String scenario = query(exchange, "scenario");
        return scenario == null || scenario.isBlank() ? SCENARIO_SUCCESS : scenario;
    }

    private String query(HttpExchange exchange, String name) {
        String rawQuery = exchange.getRequestURI().getQuery();
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0 && pair.substring(0, at).equals(name)) {
                return pair.substring(at + 1);
            }
        }
        return null;
    }

    private String extract(String body, String field) {
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
}
