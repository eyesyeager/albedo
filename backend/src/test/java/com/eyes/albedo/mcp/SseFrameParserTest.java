package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SSE 帧解析纯单测（ADR-016 实施落点 #3 / api-spec §7.6.1 G6′ ④）。
 *
 * <p>🔴 <b>为什么必须有纯单测</b>：帧解析跑在 {@code HttpClient} 的<b>共享</b>内部 executor 上，
 * 一旦解析错帧（例如把多行 {@code data:} 拆成两帧），表现是"偶发超时 30051"而非明确报错 ——
 * 这类缺陷在集成测试里几乎不可复现，必须用纯函数单测把边界钉死。
 */
class SseFrameParserTest {

    private static final long GENEROUS_LIMIT = 1024L * 1024L;

    @Test
    @DisplayName("空行为帧边界：event + data 组成一帧")
    void blankLineTerminatesFrame() {
        SseFrameParser parser = new SseFrameParser(GENEROUS_LIMIT);

        assertNull(parser.accept("event: endpoint"));
        assertNull(parser.accept("data: /message/abc?sessionId=1"));
        SseFrameParser.Frame frame = parser.accept("");

        assertNotNull(frame);
        assertTrue(frame.isEndpoint());
        assertEquals("/message/abc?sessionId=1", frame.data());
    }

    @Test
    @DisplayName("🔴 多行 data: 按 \\n 拼接（拆错帧会让 JSON 解析失败 → 误判协议不兼容）")
    void multiLineDataIsJoinedByNewline() {
        SseFrameParser parser = new SseFrameParser(GENEROUS_LIMIT);

        parser.accept("data: {\"jsonrpc\":\"2.0\",");
        parser.accept("data: \"id\":\"x\"}");
        SseFrameParser.Frame frame = parser.accept("");

        assertNotNull(frame);
        assertEquals("{\"jsonrpc\":\"2.0\",\n\"id\":\"x\"}", frame.data());
        assertNull(frame.event(), "无 event: 行时事件名为 null（按 message 处理）");
    }

    @Test
    @DisplayName("注释行（心跳）与 id:/retry: 字段被忽略，且不会污染 data")
    void commentsAndUnknownFieldsIgnored() {
        SseFrameParser parser = new SseFrameParser(GENEROUS_LIMIT);

        parser.accept(": ping");
        parser.accept("id: 42");
        parser.accept("retry: 1000");
        parser.accept("data: {\"a\":1}");
        SseFrameParser.Frame frame = parser.accept("");

        assertNotNull(frame);
        assertEquals("{\"a\":1}", frame.data());
    }

    @Test
    @DisplayName("[DONE] 与空 data 帧一律不产出（它们不是 JSON-RPC 报文）")
    void doneMarkerAndEmptyFramesDropped() {
        SseFrameParser parser = new SseFrameParser(GENEROUS_LIMIT);

        parser.accept("data: [DONE]");
        assertNull(parser.accept(""));
        assertNull(parser.accept(""), "连续空行不产出空帧");
    }

    @Test
    @DisplayName("🔴 上游不发末尾空行就关流 → finish() 仍能取出残帧")
    void finishFlushesResidualFrame() {
        SseFrameParser parser = new SseFrameParser(GENEROUS_LIMIT);

        parser.accept("event: message");
        parser.accept("data: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}");

        SseFrameParser.Frame frame = parser.finish();
        assertNotNull(frame);
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{}}", frame.data());
        assertNull(parser.finish(), "残帧只能被取走一次");
    }

    @Test
    @DisplayName("🔴 累计字节超 mcp.sse_stream_max_bytes → overflow 并停止解析（无限流防线）")
    void overflowStopsParsing() {
        SseFrameParser parser = new SseFrameParser(32L);

        assertFalse(parser.overflow());
        parser.accept("data: " + "x".repeat(40));

        assertTrue(parser.overflow(), "超限必须置 overflow");
        assertTrue(parser.consumedBytes() > 32L);
        assertNull(parser.accept("data: {\"jsonrpc\":\"2.0\"}"), "overflow 后不再解析任何行");
        assertNull(parser.finish(), "overflow 后不得产出残帧");
    }

    @Test
    @DisplayName("🔴 字节计数含行分隔符：逐行都不超限但总量无界的流同样会被拦下")
    void lineSeparatorCountedTowardsLimit() {
        SseFrameParser parser = new SseFrameParser(10L);

        // 每行 4 字节 + 分隔符 1 字节 = 5 → 第 3 行必然越界
        parser.accept("data");
        parser.accept("data");
        assertFalse(parser.overflow());
        parser.accept("data");
        assertTrue(parser.overflow());
    }
}
