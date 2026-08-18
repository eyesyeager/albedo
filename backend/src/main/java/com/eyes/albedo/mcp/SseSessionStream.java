package com.eyes.albedo.mcp;

import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 单次 exchange 的 SSE 会话流（ADR-016 实施落点 #4）。
 *
 * <p>它是 {@code sse} 传输<b>异步推送形态</b>（MCP 2024-11-05）的接收端：
 * 作为 {@link Flow.Subscriber} 挂在 {@code BodyHandlers.fromLineSubscriber(...)} 上，
 * 把流上的帧分派为两类结果 ——
 * <ol>
 *   <li>{@code event: endpoint} → 会话端点（🔴 <b>只认第一次出现的值</b>，见下）</li>
 *   <li>{@code jsonrpc=="2.0"} 且 <b>id 已被本次 exchange 登记</b>的报文 → 对应的等待者</li>
 * </ol>
 * 其余帧（{@code notifications/*}、{@code logging}、{@code ping}、{@code resources} 变更、
 * id 不匹配）🔴 <b>一律丢弃且不做任何解释</b>（ADR-016 ④）。
 *
 * <p>🔴 <b>线程纪律（ADR-008 第 8 条 V1.4.0 补注的落点，违反即"AI 首字变慢"级耦合故障）</b>：
 * <ul>
 *   <li>本类的回调跑在 {@code HttpClient} 的<b>共享内部 executor</b> 上，
 *       与 AI 上游的 SSE 消费<b>同池</b> —— 因此 {@link #onNext(String)} 内
 *       🔴 <b>禁止任何阻塞</b>：无 DB、无远程调用、无锁等待，只做"解析 + 完成 Future"</li>
 *   <li>🔴 本类<b>不新增任何线程池</b>：既没有 {@code new Thread}，也没有
 *       {@code Executors.new*} / {@code supplyAsync}；等待一律由<b>调用方线程</b>
 *       以 {@code get(remaining, MILLISECONDS)} 被动完成</li>
 *   <li>{@code CompletableFuture.complete(...)} 是幂等的，且本类<b>不注册任何依赖阶段</b>
 *       （无 {@code thenApply}/{@code thenAccept}），因此不会把工作反向压回该共享池</li>
 * </ul>
 *
 * <p>🔴 <b>SSRF 纪律（ADR-016 ⑦）</b>：{@code event: endpoint} <b>只接受第一次出现的值</b>，
 * 后续再出现一律忽略并记 {@code [SECURITY]} —— 防"先给合法端点、再改指向"的<b>流内二次投毒</b>。
 * 流内出现的任何其它 URL（{@code resources} 链接等）本类<b>根本不解释</b>，因此不可能被请求。
 *
 * <p>🔴 <b>生命周期</b>：实例严格属于<b>单次</b> exchange，{@link #close()} 在
 * {@code finally} 中强制 {@code subscription.cancel()} + {@code responseFuture.cancel(true)}。
 * 🔴 禁止把本对象、其 sessionId 或流写入任何字段 / 静态变量 / Redis / DB / 缓存（ADR-016 ⑤⑥）。
 */
@Slf4j
public final class SseSessionStream implements Flow.Subscriber<String>, AutoCloseable {

    private final SseFrameParser parser;
    private final ObjectMapper objectMapper;

    /** 会话端点（{@code ""} 表示"流已结束且上游从未给出"→ 退化为原 endpoint）。 */
    private final CompletableFuture<String> endpointFuture = new CompletableFuture<>();
    /** 本次 exchange 已登记的 requestId → 等待者（🔴 只接受登记过的 id）。 */
    private final Map<String, CompletableFuture<JsonNode>> waiters = new ConcurrentHashMap<>();
    private final AtomicBoolean endpointAccepted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Flow.Subscription subscription;
    private volatile CompletableFuture<?> responseFuture;

    public SseSessionStream(long maxStreamBytes, ObjectMapper objectMapper) {
        this.parser = new SseFrameParser(maxStreamBytes);
        this.objectMapper = objectMapper;
    }

    /**
     * 绑定 {@code sendAsync} 返回的 Future，供 {@link #close()} 强制取消（🔴 泄漏防护）。
     */
    public void bind(CompletableFuture<?> future) {
        this.responseFuture = future;
    }

    /**
     * 登记一个即将 POST 的 requestId，返回其结果等待者。
     *
     * <p>🔴 <b>必须在 POST 之前调用</b>：上游完全可能在 POST 的 HTTP 响应（202）回到我们之前
     * 就把结果推上流 —— 若"先 POST 再登记"，那一帧会因"id 未登记"被当噪声丢掉，
     * 表现为必然超时（{@code 30051}）。
     */
    public CompletableFuture<JsonNode> expect(String requestId) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        waiters.put(requestId, future);
        return future;
    }

    /**
     * 等待会话端点。
     *
     * @return 上游给出的端点；{@code ""} 表示流已正常结束但从未给出端点（调用方退化为原 endpoint）
     * @throws McpTransportException {@link McpFailure#TIMEOUT} 超预算 /
     *                               {@link McpFailure#PROTOCOL_INCOMPATIBLE} 流异常
     */
    public String awaitEndpoint(long remainingMillis) {
        return await(endpointFuture, remainingMillis);
    }

    /**
     * 在<b>调用方线程</b>上被动等待一个结果（🔴 带超时的 {@code get}，禁止无参 {@code get()} /
     * {@code join()}）。
     */
    public <T> T await(CompletableFuture<T> future, long remainingMillis) {
        if (remainingMillis <= 0L) {
            // 🔴 deadline 预算已用尽（ADR-016 ⑤）
            throw new McpTransportException(McpFailure.TIMEOUT);
        }
        try {
            return future.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpTransportException classified) {
                throw classified;
            }
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE, e);
        } catch (InterruptedException e) {
            // 🔴 恢复中断位后按超时收敛（绝不吞掉中断）
            Thread.currentThread().interrupt();
            throw new McpTransportException(McpFailure.TIMEOUT, e);
        }
    }

    // ===================== Flow.Subscriber（🔴 回调内禁止阻塞） =====================

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        // 无界请求：内存与时长由 mcp.sse_stream_max_bytes + HttpRequest.timeout(remaining) 双封顶
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(String line) {
        SseFrameParser.Frame frame = parser.accept(line);
        if (parser.overflow()) {
            // 🔴 "无限流"防线：立即关流并整体失败（ADR-016 ⑥）
            log.warn("[SECURITY] MCP sse 事件流累计字节超过 sys_config[{}.{}]，已强制关流：bytes={}",
                    ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES,
                    parser.consumedBytes());
            failAll(new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE));
            cancelSubscription();
            return;
        }
        dispatch(frame);
    }

    @Override
    public void onError(Throwable throwable) {
        // 流异常中断：形态/流行为不符规范（🔴 不白等到超时）
        log.debug("MCP sse 事件流异常结束：{}", throwable.getClass().getSimpleName());
        failAll(new McpTransportException(classifyStreamError(throwable), throwable));
    }

    @Override
    public void onComplete() {
        // 上游可能不发末尾空行就关流 → 先收残帧，再收敛
        dispatch(parser.finish());
        // 🔴 端点从未出现 = "上游不走会话端点"，退化为原 endpoint（不是故障）
        endpointFuture.complete("");
        // 🔴 结果未给出就关流 = 协议不兼容，**立即**失败，不白占一个线程等满预算
        failWaiters(new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE));
    }

    /**
     * 🔴 强制关流：{@code subscription.cancel()} + {@code responseFuture.cancel(true)}。
     *
     * <p>两者都要做的原因：只 cancel 订阅时，JDK 仍可能保留连接直至读完；
     * 只 cancel Future 时，已投递的行仍会继续进入订阅者。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelSubscription();
        CompletableFuture<?> future = this.responseFuture;
        if (future != null) {
            future.cancel(true);
        }
        // 释放可能仍在等待的引用（本方法只在 exchange 结束时调用）
        failWaiters(new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE));
        waiters.clear();
    }

    private void dispatch(SseFrameParser.Frame frame) {
        if (frame == null) {
            return;
        }
        if (frame.isEndpoint()) {
            acceptEndpoint(frame.data());
            return;
        }
        JsonNode node = McpRpcMessages.parseQuietly(objectMapper, frame.data());
        if (!McpRpcMessages.isJsonRpc(node)) {
            // 非 JSON-RPC 帧（心跳 / 上游日志 / 自定义事件）→ 🔴 丢弃
            return;
        }
        String id = McpRpcMessages.idOf(node);
        if (id == null) {
            // 通知类报文（notifications/*）无 id → 🔴 丢弃，不做任何解释
            return;
        }
        CompletableFuture<JsonNode> waiter = waiters.get(id);
        if (waiter == null) {
            // 🔴 id 不匹配 → 丢弃（可能是上游串话或投毒帧）
            return;
        }
        waiter.complete(node);
    }

    /**
     * 🔴 {@code event: endpoint} <b>只接受第一次出现的值</b>（防流内二次投毒，ADR-016 ⑦）。
     */
    private void acceptEndpoint(String endpoint) {
        if (endpointAccepted.compareAndSet(false, true)) {
            endpointFuture.complete(endpoint);
            return;
        }
        log.warn("[SECURITY] MCP sse 流内重复出现 event: endpoint，已忽略后续取值"
                + "（只认第一次出现的会话端点）");
    }

    private void failAll(McpTransportException failure) {
        endpointFuture.completeExceptionally(failure);
        failWaiters(failure);
    }

    /**
     * 流异常的分类：🔴 {@code HttpRequest.timeout(remaining)} 触发的中断必须归 {@code TIMEOUT}
     * 而不是 {@code PROTOCOL_INCOMPATIBLE}（否则"上游一直不回"会被误诊为"协议不兼容"，
     * 违反 api-spec §7.4.2 G2 的判别口径：{@code timeout} = 无响应直至超时）。
     */
    private static McpFailure classifyStreamError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
                return McpFailure.TIMEOUT;
            }
            cause = cause.getCause();
        }
        return McpFailure.PROTOCOL_INCOMPATIBLE;
    }

    private void failWaiters(McpTransportException failure) {
        waiters.values().forEach(waiter -> waiter.completeExceptionally(failure));
    }

    private void cancelSubscription() {
        Flow.Subscription current = this.subscription;
        if (current != null) {
            current.cancel();
        }
    }
}
