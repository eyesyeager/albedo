package com.eyes.albedo.mcp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP 传输基类：把 HTTP 层的异常与状态码<b>统一分类</b>为 {@link McpFailure}。
 *
 * <p><b>为什么要有基类</b>：两种传输（{@code streamable_http} / {@code sse}）的差异只在
 * "怎么把 JSON-RPC 报文送出去、怎么把响应体取回来"，而<b>失败分类、重定向策略、超时口径、
 * 鉴权头附加</b>必须完全一致 —— 一旦分叉，就会出现"同一故障两种传输给出不同错误码"。
 *
 * <p>🔴 <b>安全约束</b>：
 * <ul>
 *   <li>{@code followRedirects=NEVER}（{@code HttpClientConfig} 已固定）：
 *       3xx → {@link McpFailure#PROTOCOL_INCOMPATIBLE}（→ {@code 30052}），
 *       规避"302 跳内网"绕过 SSRF 校验（api-spec §7.6.3 重定向行）</li>
 *   <li>以<b>原域名</b>发起连接：保留完整证书链与 SNI 校验，🔴 绝不降级端点识别（ADR-009 ②）</li>
 *   <li>异常与日志中<b>不出现</b> endpoint / 凭据 / 上游正文</li>
 * </ul>
 *
 * <p>🔴 <b>不新增线程池</b>（ADR-001 / api-spec §7.6.1）：复用 {@code HttpClientConfig} 的单个
 * {@link HttpClient} Bean，同步 {@code send(...)}，跑在调用方线程（生成时即 {@code aiStreamExecutor}）。
 * <br>🔴 <b>V1.4.0 边界补注（ADR-008 第 8 条 / ADR-016）</b>：{@code sse} 的异步形态使用
 * {@code httpClient.sendAsync(...)} + {@code BodyHandlers.fromLineSubscriber(...)}，
 * 它跑在 {@code HttpClient} <b>自带的内部 executor</b> 上 —— 而 JDK17 的
 * {@code HttpClient.send(...)} 本身就是 {@code sendAsync(...)} + 阻塞等待的语法糖，
 * 即<b>今天的同步调用已经在用那个线程池</b>，故显式使用它<b>不构成"新增线程池"</b>。
 * 🔴 仍然禁止：{@code new Thread} / {@code Executors.new*} / {@code @Async} / 自建 executor /
 * {@code CompletableFuture.supplyAsync}（落到 {@code ForkJoinPool.commonPool}）/
 * 无参 {@code get()} 与 {@code join()}（无超时等待 = 潜在永久挂起）。
 *
 * <p>✅ <b>已裁决的超时口径（api-spec V1.1.2 §7.6.1 G7）</b>：JDK17 的 {@code connectTimeout}
 * 只能设在 {@code HttpClient} 上、<b>无法按请求覆盖</b>，因此：
 * <ul>
 *   <li><b>建连超时（TCP + TLS）</b>= {@code application.yml} 的
 *       {@code app.ai.connect-timeout-seconds}（默认 10s，基础设施级、全局唯一 Bean）</li>
 *   <li><b>请求级超时</b>= {@code HttpRequest.timeout()}，取
 *       {@code mcp.connect_timeout_seconds}（握手/连接测试）、{@code mcp.discover_timeout_seconds}
 *       （{@code tools/list}）、{@code min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)}
 *       （{@code tools/call}）—— 这是<b>真超时</b>，由 HttpClient 中断请求</li>
 * </ul>
 * 🔴 因此 {@code mcp.connect_timeout_seconds} 的准确语义是"握手/连接测试阶段的<b>请求级</b>总超时"，
 * 而<b>不是</b> TCP 建连超时本身。
 * 🔴 运维不等式：{@code app.ai.connect-timeout-seconds ≤ mcp.connect_timeout_seconds}
 * （否则会出现"请求级超时先到、诊断结果误判为 timeout 而非 connect_failed"）。
 * 📋 "为 MCP 单建第二个 HttpClient"已被 @架构师 否决：两套连接池 = 两套重定向/代理策略，
 * {@code followRedirects=NEVER} 存在被漏配的一致性风险。
 */
@Slf4j
public abstract class AbstractMcpTransport implements McpTransport {

    protected static final String HEADER_CONTENT_TYPE = "Content-Type";
    protected static final String HEADER_ACCEPT = "Accept";
    protected static final String MEDIA_JSON = "application/json";
    protected static final String MEDIA_SSE = "text/event-stream";

    protected static final int HTTP_OK_MIN = 200;
    protected static final int HTTP_OK_MAX = 299;
    private static final int HTTP_REDIRECT_MIN = 300;
    private static final int HTTP_REDIRECT_MAX = 399;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_PROXY_AUTH_REQUIRED = 407;

    protected final HttpClient httpClient;

    protected AbstractMcpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 单次 exchange 的<b>超时预算</b>（🔴 ADR-016 ⑤ / api-spec §7.6.1 G6′ ⑤：deadline 预算制）。
     *
     * <p>🔴 <b>它订正的既有缺口</b>：原实现把 {@code timeout} 当作"每个子请求各取一份"，
     * 而 {@code sse} 传输一次 exchange 内含 <b>GET 建流 + 最多 3 次 POST + 流上等待</b> ——
     * 最坏可达 <b>2×~4× 预算</b>，使"单次调用总超时"这条契约在实现层形同虚设
     * （表现为一次 {@code tools/call} 明明配了 30s 却能占用 2 分钟线程）。
     *
     * <p>用法：exchange 入口 {@link #after(Duration)} 算一次，各子步骤取 {@link #remaining()}；
     * 🔴 预算用尽即 {@link McpFailure#TIMEOUT}（{@code 30051}），累加<b>不得</b>超预算。
     *
     * <p>🔴 <b>不新增线程池</b>：本类只做时间算术，绝不起看门狗线程做中断
     * （超时由 {@code HttpRequest.timeout(remaining)} 与 {@code Future.get(remaining)} 落实，
     * 边界见 ADR-008 第 8 条 V1.4.0 补注）。
     */
    public static final class Deadline {

        private final long deadlineNanos;

        private Deadline(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        /** 以当前时刻 + 预算创建 deadline。 */
        public static Deadline after(Duration budget) {
            return new Deadline(System.nanoTime() + budget.toNanos());
        }

        /**
         * 剩余预算。
         *
         * @throws McpTransportException {@link McpFailure#TIMEOUT} 预算已用尽（{@code remaining ≤ 0}）
         */
        public Duration remaining() {
            long millis = remainingMillis();
            if (millis <= 0L) {
                throw new McpTransportException(McpFailure.TIMEOUT);
            }
            return Duration.ofMillis(millis);
        }

        /** 剩余预算毫秒数（🔴 可能 {@code ≤ 0}，由调用方判定）。 */
        public long remainingMillis() {
            return (deadlineNanos - System.nanoTime()) / 1_000_000L;
        }
    }

    /**
     * 一次 POST 的<b>原始结果</b>（状态码 + 响应体）。
     *
     * <p>🔴 为什么需要它而不是只要 body：{@code sse} 的形态判定依据正是
     * "首个 POST 的响应体里<b>有没有</b>可解析的 JSON-RPC 报文"（含 {@code 202} + 空体），
     * 而 {@link #postForBody} 会把空体直接判成 {@code PROTOCOL_INCOMPATIBLE} —— 那样就永远
     * 进不了异步分支（正是 ADR-016 之前的行为）。
     */
    protected record PostOutcome(int status, String body) {

        /** 响应体是否有内容（🔴 空体是异步形态的判据之一）。 */
        public boolean hasBody() {
            return body != null && !body.isBlank();
        }
    }

    /**
     * 发送一次 POST 并返回响应体文本。
     *
     * @throws McpTransportException 已分类的上游失败
     */
    protected String postForBody(URI uri, String body, Map<String, String> headers,
                                 String accept, Duration timeout) {
        return postForOutcome(uri, body, headers, accept, timeout).body();
    }

    /**
     * 发送一次 POST 并返回<b>状态码 + 响应体</b>（🔴 失败分类逻辑与 {@link #postForBody} 完全一致）。
     */
    protected PostOutcome postForOutcome(URI uri, String body, Map<String, String> headers,
                                        String accept, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header(HEADER_CONTENT_TYPE, MEDIA_JSON)
                .header(HEADER_ACCEPT, accept)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        HttpResponse<String> response = send(builder.build());
        return new PostOutcome(response.statusCode(),
                response.body() == null ? "" : response.body());
    }

    /**
     * 执行请求并把 HTTP 层结果统一分类。
     *
     * <p>🔴 分类表（api-spec §7.6.4）：
     * <pre>
     *   UnknownHostException  → DNS_FAILED
     *   SSLException          → TLS_FAILED（🔴 证书问题宁可失败，绝不跳过校验）
     *   HttpTimeoutException  → TIMEOUT（→ 30051）
     *   ConnectException/IO   → CONNECT_FAILED（→ 30052）
     *   3xx                   → PROTOCOL_INCOMPATIBLE（不跟随重定向）
     *   401/403/407           → AUTH_FAILED（→ 30052，写审计）
     *   其他非 2xx            → PROTOCOL_INCOMPATIBLE
     * </pre>
     */
    private HttpResponse<String> send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        } catch (SSLException e) {
            throw new McpTransportException(McpFailure.TLS_FAILED, e);
        } catch (UnknownHostException e) {
            throw new McpTransportException(McpFailure.DNS_FAILED, e);
        } catch (ConnectException e) {
            throw new McpTransportException(McpFailure.CONNECT_FAILED, e);
        } catch (InterruptedIOException e) {
            // 请求超时在部分 JDK 版本表现为 InterruptedIOException
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        } catch (IOException e) {
            throw new McpTransportException(classifyIoCause(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        }
        requireAcceptableStatus(response.statusCode());
        return response;
    }

    /**
     * 状态码分类（🔴 GET 建流与 POST 共用同一判据，避免两条路径给出不同错误码）。
     */
    protected void requireAcceptableStatus(int status) {
        if (isRedirect(status)) {
            // 🔴 不跟随重定向：3xx 说明上游想把我们导去别处，可能是内网
            log.warn("[SECURITY] MCP 上游返回重定向，已拒绝跟随：status={}", status);
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        if (isAuthFailure(status)) {
            throw new McpTransportException(McpFailure.AUTH_FAILED);
        }
        if (status < HTTP_OK_MIN || status > HTTP_OK_MAX) {
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
    }

    /** 3xx（🔴 一律不跟随）。 */
    protected boolean isRedirect(int status) {
        return status >= HTTP_REDIRECT_MIN && status <= HTTP_REDIRECT_MAX;
    }

    /** 401 / 403 / 407（🔴 需写安全审计）。 */
    protected boolean isAuthFailure(int status) {
        return status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN
                || status == HTTP_PROXY_AUTH_REQUIRED;
    }

    /**
     * 异步路径（{@code sendAsync}）抛出的 {@code Throwable} 的分类。
     *
     * <p>🔴 必须与同步 {@link #send} 的分类<b>完全一致</b>：{@code sse} 的 GET 建流走异步，
     * 若两处分类分叉，同一个"域名解析失败"会在 {@code streamable_http} 判 {@code dns_failed}、
     * 在 {@code sse} 判 {@code connect_failed} —— 诊断结论自相矛盾。
     */
    protected McpFailure classifyThrowable(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException || cause instanceof ExecutionException) {
            if (cause.getCause() == null) {
                break;
            }
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
            return McpFailure.TIMEOUT;
        }
        if (cause instanceof SSLException) {
            return McpFailure.TLS_FAILED;
        }
        if (cause instanceof UnknownHostException) {
            return McpFailure.DNS_FAILED;
        }
        if (cause instanceof ConnectException) {
            return McpFailure.CONNECT_FAILED;
        }
        if (cause instanceof IOException io) {
            return classifyIoCause(io);
        }
        return McpFailure.CONNECT_FAILED;
    }

    /**
     * {@code IOException} 的二次分类：JDK 会把 TLS / DNS 错误包在 {@code IOException} 里。
     */
    private McpFailure classifyIoCause(IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SSLException) {
                return McpFailure.TLS_FAILED;
            }
            if (cause instanceof UnknownHostException) {
                return McpFailure.DNS_FAILED;
            }
            if (cause instanceof HttpTimeoutException) {
                return McpFailure.TIMEOUT;
            }
            cause = cause.getCause();
        }
        return McpFailure.CONNECT_FAILED;
    }

    /**
     * 从响应体中取出 JSON-RPC 报文文本。
     *
     * <p>两种传输都可能收到 <b>SSE 帧</b>（{@code data: {...}}）或<b>裸 JSON</b>：
     * MCP 的 {@code streamable_http} 允许服务端用 {@code text/event-stream} 回复单个 POST。
     * 因此统一在此做"帧剥离"，避免各传输各写一遍解析。
     *
     * @return 第一个 JSON 报文文本；无法识别时抛 {@link McpFailure#PROTOCOL_INCOMPATIBLE}
     */
    protected String extractJsonPayload(String body) {
        String payload = findJsonPayload(body);
        if (payload == null) {
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        return payload;
    }

    /**
     * 与 {@link #extractJsonPayload} 同规则，但<b>识别不到就返回 {@code null}</b>。
     *
     * <p>🔴 {@code sse} 的<b>形态判定</b>必须用本方法：判据是"首个 POST 的响应体里有没有
     * 可解析报文"，而"没有"是一个<b>正常分支</b>（进入异步形态），不是错误。
     */
    protected String findJsonPayload(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        // SSE 帧：逐行取第一个非空 data:
        for (String line : trimmed.split("\\R")) {
            String candidate = line.trim();
            if (!candidate.startsWith("data:")) {
                continue;
            }
            String payload = candidate.substring("data:".length()).trim();
            if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                return payload;
            }
        }
        return null;
    }
}
