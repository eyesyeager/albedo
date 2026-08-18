package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSE 会话流订阅者单测（ADR-016 实施落点 #4 / ⑦ SSRF 纪律 / ⑥ 泄漏防护）。
 *
 * <p>🔴 <b>为什么用纯单测而不是只靠 IT</b>：本类的安全语义（{@code event: endpoint} 只认第一次、
 * id 不匹配一律丢弃、流提前关闭立即失败、字节超限强制关流）都是"<b>不该发生的事没发生</b>"型断言，
 * 用真实上游几乎无法稳定复现"流内二次投毒"这类场景 —— 直接驱动 {@link Flow.Subscriber} 回调
 * 才能逐条钉死。
 */
class SseSessionStreamTest {

    private static final long GENEROUS_LIMIT = 1024L * 1024L;
    private static final long WAIT_MILLIS = 2000L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录 cancel 的假订阅（🔴 泄漏防护断言用）。 */
    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong requested = new AtomicLong();

        @Override
        public void request(long n) {
            requested.addAndGet(n);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    @Test
    @DisplayName("🔴 event: endpoint 只接受第一次出现的值（防流内二次投毒，ADR-016 ⑦）")
    void onlyFirstEndpointEventAccepted() {
        RecordingSubscription subscription = new RecordingSubscription();
        try (SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper)) {
            stream.onSubscribe(subscription);

            feed(stream, "event: endpoint", "data: /messages?sessionId=first", "");
            // 第二次投毒：把会话端点改指向云元数据
            feed(stream, "event: endpoint", "data: http://169.254.169.254/latest/meta-data", "");

            assertEquals("/messages?sessionId=first", stream.awaitEndpoint(WAIT_MILLIS),
                    "🔴 只能认第一次出现的会话端点");
            assertTrue(subscription.requested.get() > 0, "必须向上游 request 数据");
        }
    }

    @Test
    @DisplayName("🔴 只接受 jsonrpc=2.0 且 id 匹配的报文；通知 / 非 JSON / id 不匹配一律丢弃")
    void onlyMatchingIdCompletesWaiter() {
        try (SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper)) {
            stream.onSubscribe(new RecordingSubscription());
            CompletableFuture<JsonNode> waiter = stream.expect("req-1");

            feed(stream, "data: not-json-at-all", "");
            feed(stream, "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}", "");
            feed(stream, "data: {\"jsonrpc\":\"2.0\",\"id\":\"other\",\"result\":{}}", "");
            feed(stream, "data: {\"id\":\"req-1\",\"result\":{\"leaked\":true}}", "");
            assertFalse(waiter.isDone(), "🔴 噪声帧绝不能完成等待者");

            feed(stream, "event: message",
                    "data: {\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"ok\":true}}", "");

            JsonNode message = stream.await(waiter, WAIT_MILLIS);
            assertEquals("req-1", McpRpcMessages.idOf(message));
            assertTrue(message.get("result").get("ok").asBoolean());
        }
    }

    @Test
    @DisplayName("🔴 流在给出结果前被关闭 → 立即 PROTOCOL_INCOMPATIBLE（不白等满预算）")
    void streamClosedBeforeResultFailsImmediately() {
        try (SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper)) {
            stream.onSubscribe(new RecordingSubscription());
            CompletableFuture<JsonNode> waiter = stream.expect("req-1");

            stream.onComplete();

            long began = System.nanoTime();
            McpTransportException ex = assertThrows(McpTransportException.class,
                    () -> stream.await(waiter, 5000L));
            long elapsedMillis = (System.nanoTime() - began) / 1_000_000L;

            assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
            assertTrue(elapsedMillis < 500L, "🔴 必须立即失败，实际耗时：" + elapsedMillis + "ms");
        }
    }

    @Test
    @DisplayName("流正常结束且从未给出 endpoint → 退化为原 endpoint（返回空串，不是故障）")
    void streamEndWithoutEndpointDegrades() {
        try (SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper)) {
            stream.onSubscribe(new RecordingSubscription());

            stream.onComplete();

            assertEquals("", stream.awaitEndpoint(WAIT_MILLIS));
        }
    }

    @Test
    @DisplayName("🔴 累计字节超上限 → 强制关流 + PROTOCOL_INCOMPATIBLE（无限流不能拖死线程）")
    void oversizeStreamIsCancelled() {
        RecordingSubscription subscription = new RecordingSubscription();
        try (SseSessionStream stream = new SseSessionStream(64L, objectMapper)) {
            stream.onSubscribe(subscription);
            CompletableFuture<JsonNode> waiter = stream.expect("req-1");

            feed(stream, "data: " + "x".repeat(128), "");

            McpTransportException ex = assertThrows(McpTransportException.class,
                    () -> stream.await(waiter, WAIT_MILLIS));
            assertEquals(McpFailure.PROTOCOL_INCOMPATIBLE, ex.failure());
            assertTrue(subscription.cancelled.get(), "🔴 超限必须立即 cancel 订阅");
        }
    }

    @Test
    @DisplayName("🔴 remaining ≤ 0（deadline 预算已用尽）→ TIMEOUT，且不发起任何等待")
    void exhaustedBudgetIsTimeout() {
        try (SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper)) {
            stream.onSubscribe(new RecordingSubscription());
            CompletableFuture<JsonNode> waiter = stream.expect("req-1");

            McpTransportException ex = assertThrows(McpTransportException.class,
                    () -> stream.await(waiter, 0L));
            assertEquals(McpFailure.TIMEOUT, ex.failure());
        }
    }

    @Test
    @DisplayName("🔴 close() 强制 cancel 订阅与响应 Future（finally 关流，AR-020 应对①）")
    void closeCancelsSubscriptionAndResponseFuture() {
        RecordingSubscription subscription = new RecordingSubscription();
        CompletableFuture<Void> responseFuture = new CompletableFuture<>();
        SseSessionStream stream = new SseSessionStream(GENEROUS_LIMIT, objectMapper);
        stream.onSubscribe(subscription);
        stream.bind(responseFuture);

        stream.close();

        assertTrue(subscription.cancelled.get(), "订阅必须被 cancel");
        assertTrue(responseFuture.isCancelled(), "响应 Future 必须被 cancel(true)");
        stream.close();
        assertTrue(subscription.cancelled.get(), "close() 必须幂等");
    }

    private void feed(SseSessionStream stream, String... lines) {
        for (String line : lines) {
            stream.onNext(line);
        }
    }
}
