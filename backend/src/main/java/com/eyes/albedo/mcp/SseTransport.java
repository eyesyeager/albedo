package com.eyes.albedo.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code sse} 传输 —— MCP <b>HTTP+SSE</b>，🔴 <b>单次 exchange 内的形态自适应</b>
 * （ADR-016 / api-spec §7.6.1 <b>G6′</b>）。
 *
 * <h2>支持的两种形态（🔴 由同一个 {@code transport='sse'} 覆盖，不新增枚举值）</h2>
 * <pre>
 * ① 同步应答形态：POST 的**响应体内**直接返回 JSON-RPC 结果（裸 JSON 或单条 SSE 帧）
 * ② 2024-11-05 异步推送形态：POST 只回 202 + 空体，结果由**本次 exchange 持有的 GET 流**推送
 * </pre>
 * 🔴 <b>判据 = 首个 POST（{@code initialize}）的响应体形态</b>，因此探测成本 =
 * <b>零额外网络往返</b>，且同步形态在第一步即命中 → 向后兼容天然成立。
 * 🔴 为什么不新增 {@code sse_legacy} 枚举：DBA 无法从 URL 判断上游属哪种形态
 * （本次靠 curl 实探才知道），让运维选枚举 = 把探测责任推给运维（ADR-016 备选方案 B 已否决）。
 *
 * <h2>单次 exchange 的固定序列（🔴 全程在<b>一个</b> deadline 预算内）</h2>
 * <ol>
 *   <li>GET endpoint 建流（{@code sendAsync} + {@code fromLineSubscriber}），🔴 <b>流保持打开</b>
 *       —— 上游不支持 GET（非 2xx）→ 退化为直接 POST 原 endpoint（保留既有兼容行为）</li>
 *   <li>等 {@code event: endpoint} → 🔴 {@code sameOrigin} 校验 → sessionUri
 *       （流已结束且从未给出该事件 → sessionUri = 原 endpoint）</li>
 *   <li>POST {@code initialize} —— 🔴 它<b>同时</b>是"形态探测"与"协议握手"：
 *     <ul>
 *       <li>响应体含可解析 JSON-RPC 报文 → <b>同步形态</b>：🔴 <b>忽略该 initialize 的成败</b>
 *           （含 {@code -32601}/{@code -32602}/任意 error，它只用于探测）→ POST 目标方法 →
 *           从响应体取结果</li>
 *       <li>只回 2xx + 空体 / 无 JSON-RPC 报文（含 {@code 202}）→ <b>异步形态</b>：
 *           从流等 {@code initialize} 的 result（必须成功）→ POST
 *           {@code notifications/initialized}（🔴 不等结果）→ POST 目标方法 →
 *           从流等<b>匹配 id</b> 的报文</li>
 *     </ul>
 *   </li>
 *   <li>{@code finally}：🔴 强制关流（{@code subscription.cancel()} +
 *       {@code responseFuture.cancel(true)}，见 {@link SseSessionStream#close()}）</li>
 * </ol>
 *
 * <p>🔴 <b>为什么必须先 {@code initialize}</b>（ADR-016 ③）：反过来"先发目标方法、失败再补握手"
 * 会在上游强制握手时要求<b>重发 {@code tools/call}</b> —— 重试一个非幂等的外部工具调用可能造成
 * <b>重复副作用</b>，与 {@code 30056}「非幂等结果未知一律不自动重试」直接冲突。
 * 把 {@code initialize} 前置，从结构上消灭了"重试 tools/call"这条路径。
 * 代价：同步形态的 sse 上游每次 exchange 多一次 POST（sse 是兼容分支、非首选传输，可接受）。
 *
 * <h2>🔴 SSRF 纪律（ADR-016 ⑦，点位零削弱）</h2>
 * <ul>
 *   <li>三处 {@code SsrfGuard} 点位<b>完全不变</b>且仍在发起任何连接之前（本类不新增/不移动点位）</li>
 *   <li>{@code followRedirects=NEVER} 不变：GET 建流同样受其约束，3xx → {@code PROTOCOL_INCOMPATIBLE}</li>
 *   <li>会话端点 {@code sameOrigin} 校验<b>保留</b>：跨源 = 重定向的变体 →
 *       {@code PROTOCOL_INCOMPATIBLE}（🔴 <b>不改判 30050</b>：此处未发起任何连接，
 *       {@code mcp.ssrf_rejected} 审计保留给 {@code SsrfGuard} 的 endpoint 级判定）</li>
 *   <li>🔴 {@code event: endpoint} <b>只接受第一次出现的值</b>（防流内二次投毒，见
 *       {@link SseSessionStream}）</li>
 *   <li>🔴 本传输<b>只会</b>向两个 URL 发起请求：已通过 SSRF 校验的 endpoint，
 *       与与之<b>同源</b>的 session endpoint；流内出现的任何其它 URL 一律<b>不请求</b></li>
 * </ul>
 *
 * <h2>🔴 线程与资源纪律（ADR-008 第 8 条 V1.4.0 补注 / AR-020）</h2>
 * <ul>
 *   <li>✅ 只用 {@code httpClient.sendAsync} + {@code BodyHandlers.fromLineSubscriber}
 *       （复用唯一 {@code HttpClient} Bean 的<b>内部 executor</b>）+ 调用方线程上的
 *       {@code get(remaining, MILLISECONDS)} 被动等待</li>
 *   <li>❌ 无 {@code new Thread} / {@code Executors.new*} / {@code @Async} / 自建 executor /
 *       {@code supplyAsync} / 无参 {@code get()} / {@code join()}</li>
 *   <li>🔴 GET 流存活期 ⊆ 单次 exchange 且 ≤ 单次调用预算；
 *       🔴 sessionId / 流 / 订阅者<b>不进任何字段、静态变量、Redis、DB、缓存</b>
 *       —— 本类<b>无任何实例状态</b>（仅注入不可变协作者）</li>
 * </ul>
 */
@Slf4j
@Component
public class SseTransport extends AbstractMcpTransport {

    /** GET 建流与 POST 一致的 Accept（同步形态可能回裸 JSON）。 */
    private static final String ACCEPT_SSE_JSON = MEDIA_SSE + ", " + MEDIA_JSON;

    private final BusinessConfig businessConfig;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public SseTransport(HttpClient httpClient,
                        BusinessConfig businessConfig,
                        ConfigService configService,
                        ObjectMapper objectMapper) {
        super(httpClient);
        this.businessConfig = businessConfig;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String transport() {
        return McpServer.TRANSPORT_SSE;
    }

    @Override
    public String exchange(McpServer server, Map<String, String> authHeaders,
                           McpRpcRequest request, Duration timeout) {
        // 🔴 deadline 预算制：整次 exchange（GET + 最多 3 次 POST + 等待）共用这一个预算
        Deadline deadline = Deadline.after(timeout);
        URI base = URI.create(server.getEndpoint().trim());

        // 🔴 无字面量：流字节上限取 sys_config，缺失即 50003（BusinessConfig 不提供默认值兜底）
        long maxStreamBytes = businessConfig.requireLong(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_SSE_STREAM_MAX_BYTES);

        // 🔴 try-with-resources = finally 强制关流（订阅 + 响应 Future 双 cancel）
        try (SseSessionStream stream = new SseSessionStream(maxStreamBytes, objectMapper)) {
            if (!openEventStream(server, base, authHeaders, stream, deadline)) {
                // 上游不支持 GET 建流：退化为直接 POST（既有兼容行为，🔴 不视为故障）
                return extractJsonPayload(postForBody(base, request.json(), authHeaders,
                        ACCEPT_SSE_JSON, deadline.remaining()));
            }
            URI sessionUri = resolveSessionEndpoint(server, base, stream, deadline);
            return handshakeThenSend(server, sessionUri, authHeaders, request, stream, deadline);
        }
    }

    /**
     * GET 建流并<b>保持打开</b>。
     *
     * @return {@code true} 建流成功；{@code false} 上游不支持 GET（退化为直接 POST）
     * @throws McpTransportException 401/403 → {@code AUTH_FAILED}；超预算 → {@code TIMEOUT}；
     *                               3xx → {@code PROTOCOL_INCOMPATIBLE}
     */
    private boolean openEventStream(McpServer server, URI base, Map<String, String> authHeaders,
                                   SseSessionStream stream, Deadline deadline) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base)
                // 🔴 双保险：即便漏了 cancel，JDK 也会按剩余预算中断请求
                .timeout(deadline.remaining())
                .header(HEADER_ACCEPT, MEDIA_SSE)
                .GET();
        authHeaders.forEach(builder::header);

        // 状态码在响应头到达时即可判定（BodyHandler.apply 的入参），🔴 无需等流读完
        CompletableFuture<Integer> statusFuture = new CompletableFuture<>();
        HttpResponse.BodyHandler<Void> handler = info -> {
            statusFuture.complete(info.statusCode());
            if (info.statusCode() < HTTP_OK_MIN || info.statusCode() > HTTP_OK_MAX) {
                // 非 2xx：丢弃响应体，不把噪声灌进订阅者
                return HttpResponse.BodySubscribers.discarding();
            }
            return HttpResponse.BodySubscribers.fromLineSubscriber(stream);
        };

        CompletableFuture<HttpResponse<Void>> responseFuture =
                httpClient.sendAsync(builder.build(), handler);
        // 连接层失败时 BodyHandler 永不被调用 → 必须把异常转交给 statusFuture，
        // 否则会白等满预算并把 connect_failed 误判为 timeout。
        // 🔴 非 async 的 whenComplete：回调在完成线程上<b>内联</b>执行，不新增任何线程池。
        responseFuture.whenComplete((response, error) -> {
            if (error != null) {
                statusFuture.completeExceptionally(error);
            }
        });
        stream.bind(responseFuture);

        int status;
        try {
            long remainingMillis = deadline.remainingMillis();
            if (remainingMillis <= 0L) {
                throw new McpTransportException(McpFailure.TIMEOUT);
            }
            // 🔴 调用方线程上的被动等待（带超时；禁止无参 get() / join()）
            status = statusFuture.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        } catch (ExecutionException e) {
            // 连接层失败：DNS / TLS / 连接被拒 —— 分类与同步 send(...) 完全一致
            throw new McpTransportException(classifyThrowable(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        }
        if (status >= HTTP_OK_MIN && status <= HTTP_OK_MAX) {
            return true;
        }
        // 🔴 3xx / 401 / 403 / 407 不属于"上游不支持 GET"，必须按统一判据失败：
        //    3xx = 想把我们导去别处（可能是内网，followRedirects=NEVER 的语义不能被退化路径绕过）；
        //    401/403 = 鉴权失败（要写审计），退化重试只会把它掩盖成"协议不兼容"。
        //    见 ADR-016 失败分类对照表前两行。
        if (isRedirect(status) || isAuthFailure(status)) {
            requireAcceptableStatus(status);
        }
        // 其余非 2xx（404/405/5xx…）= 上游该地址只接受 POST → 退化为直接 POST（既有兼容行为）
        log.debug("MCP sse 传输：上游不支持 GET 建流，退化为直接 POST：mcpId={} status={}",
                server.getId(), status);
        return false;
    }

    /**
     * 解析 {@code event: endpoint} 给出的会话地址。
     *
     * <p>🔴 会话地址必须与原 endpoint <b>同源</b>：上游若返回一个跨主机地址，
     * 等于绕过已完成的 SSRF 校验（本质是"重定向"的变体），一律拒绝。
     * 上游未给出会话端点时退化为向原 endpoint POST（多数实现兼容）。
     */
    private URI resolveSessionEndpoint(McpServer server, URI base, SseSessionStream stream,
                                      Deadline deadline) {
        String endpoint = stream.awaitEndpoint(deadline.remainingMillis());
        if (endpoint == null || endpoint.isBlank()) {
            return base;
        }
        URI resolved = base.resolve(endpoint);
        if (!sameOrigin(base, resolved)) {
            // 🔴 跨主机会话端点 = 绕过 SSRF 校验的通道（判 protocol_incompatible，不改判 30050）
            log.warn("[SECURITY] MCP sse 会话端点与原地址不同源，已拒绝：mcpId={}", server.getId());
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        return resolved;
    }

    /**
     * {@code initialize}（探测 + 握手）→ 按形态发送目标方法。
     */
    private String handshakeThenSend(McpServer server, URI sessionUri,
                                    Map<String, String> authHeaders, McpRpcRequest request,
                                    SseSessionStream stream, Deadline deadline) {
        McpRpcRequest initialize = McpRpcMessages.initialize(objectMapper);
        // 🔴 必须在 POST 之前登记：上游可能在 202 回来之前就把 result 推上流
        CompletableFuture<JsonNode> initResult = stream.expect(initialize.id());
        PostOutcome outcome = postForOutcome(sessionUri, initialize.json(), authHeaders,
                ACCEPT_SSE_JSON, deadline.remaining());

        if (findJsonPayload(outcome.body()) != null) {
            // ===== 同步形态：🔴 忽略 initialize 的成败（它只用于探测） =====
            log.debug("MCP sse 形态判定：同步应答形态（mcpId={}）", server.getId());
            String body = postForBody(sessionUri, request.json(), authHeaders,
                    ACCEPT_SSE_JSON, deadline.remaining());
            return extractJsonPayload(body);
        }

        // ===== 异步推送形态（MCP 2024-11-05） =====
        if (!legacyFormEnabled()) {
            // 🔴 一键止血：完整回到 G6 行为（POST 空体 → protocol_incompatible / 30052）
            log.warn("MCP sse 上游为 2024-11-05 异步推送形态，但 sys_config[{}.{}] 未启用，已拒绝："
                            + "mcpId={} status={}", ConfigKeys.GROUP_MCP,
                    ConfigKeys.MCP_SSE_LEGACY_ENABLED, server.getId(), outcome.status());
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        log.debug("MCP sse 形态判定：2024-11-05 异步推送形态（mcpId={} status={}）",
                server.getId(), outcome.status());

        JsonNode initialized = stream.await(initResult, deadline.remainingMillis());
        if (McpRpcMessages.hasError(initialized)) {
            // 🔴 异步形态下握手必须成功（同步形态才允许忽略 initialize 的成败）
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }

        // 通知：无 id、🔴 不等结果（HTTP 层失败仍按统一判据分类）
        postForOutcome(sessionUri, McpRpcMessages.initializedNotification(objectMapper),
                authHeaders, ACCEPT_SSE_JSON, deadline.remaining());

        CompletableFuture<JsonNode> resultFuture = stream.expect(request.id());
        postForOutcome(sessionUri, request.json(), authHeaders,
                ACCEPT_SSE_JSON, deadline.remaining());
        JsonNode message = stream.await(resultFuture, deadline.remainingMillis());
        // 返回完整 JSON-RPC 报文文本：error / result 的语义判定统一由 McpJsonRpcClient 承担
        return message.toString();
    }

    /**
     * 🔴 {@code mcp.sse_legacy_enabled} 读取侧 <b>fail-closed</b>（缺行 / 不可解析 → {@code false}）。
     *
     * <p>为什么是 fail-closed（api-spec §7.1.2 / §11 一般原则）：它是"要不要<b>发起并持有</b>
     * 一条 SSE 流"的<b>能力开关</b>，取保守值即完整回到 ADR-016 之前的行为（{@code 30052}），
     * 是运维<b>不改代码的止血手段</b>；fail-open 会让"配置丢失"变成"悄悄开始持有长连接"。
     * 🔴 该键仍纳入 {@code StartupChecker.REQUIRED_CONFIG}（缺键启动即失败），
     * 本方法的 fail-closed 只是运行期的第二道保守兜底。
     */
    private boolean legacyFormEnabled() {
        return configService.getBoolean(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_SSE_LEGACY_ENABLED, false);
    }

    private boolean sameOrigin(URI base, URI candidate) {
        return base.getScheme() != null && candidate.getScheme() != null
                && candidate.getHost() != null
                && base.getScheme().equalsIgnoreCase(candidate.getScheme())
                && base.getHost().equalsIgnoreCase(candidate.getHost())
                && effectivePort(base) == effectivePort(candidate);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
