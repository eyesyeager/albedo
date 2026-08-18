package com.eyes.albedo.mcp;

import java.nio.charset.StandardCharsets;

/**
 * SSE 帧解析器（ADR-016 实施落点 #3，🔴 <b>纯函数式、无 IO、可单测</b>）。
 *
 * <p><b>职责</b>：把"逐行到达的文本"聚合为"一帧一帧的 {@code (event, data)}"，并对
 * <b>单次 exchange 的累计字节</b>设上限。
 *
 * <p>🔴 <b>为什么必须独立成类而不是塞进订阅者</b>：订阅者跑在 {@code HttpClient} 的<b>共享</b>
 * 内部 executor 上（与 AI 上游的 SSE 消费同池，ADR-016 后果第 3 条），它的代码必须短、
 * 不阻塞、且<b>可以在没有网络的情况下被完整测试</b>。把解析逻辑抽出来，
 * 边界用例（多行 {@code data:}、无末尾空行、字节超限、{@code [DONE]}）才能用纯单测覆盖。
 *
 * <p><b>SSE 规范要点（本类实现的部分）</b>：
 * <ul>
 *   <li>{@code event:} 指定事件名，{@code data:} 为负载；<b>同一帧内多行 {@code data:}
 *       按 {@code \n} 拼接</b></li>
 *   <li><b>空行 = 帧边界</b></li>
 *   <li>以 {@code :} 开头的行是注释（心跳），忽略</li>
 *   <li>{@code id:} / {@code retry:} 等字段本系统不消费，忽略</li>
 * </ul>
 *
 * <p>🔴 <b>字节上限</b>（{@code sys_config: mcp.sse_stream_max_bytes}）：一旦累计字节超限即
 * 置 {@link #overflow()} 并停止解析。它是"无限流"的唯一硬防线 ——
 * 上游若持续推送噪声帧，没有该上限就会把内存与一个挂起线程一起拖死（ADR-016 ⑥ / AR-020 ②）。
 */
public final class SseFrameParser {

    /** 会话端点事件名（旧版 HTTP+SSE 规范）。 */
    public static final String EVENT_ENDPOINT = "endpoint";

    private static final String FIELD_EVENT = "event:";
    private static final String FIELD_DATA = "data:";
    private static final String COMMENT_PREFIX = ":";
    /** 部分上游沿用 OpenAI 风格的流终止标记，🔴 不是 JSON，必须忽略。 */
    private static final String DONE_MARKER = "[DONE]";

    /** 一帧 SSE 事件。 */
    public record Frame(String event, String data) {

        /** 是否为会话端点事件。 */
        public boolean isEndpoint() {
            return EVENT_ENDPOINT.equals(event);
        }
    }

    private final long maxBytes;
    private final StringBuilder data = new StringBuilder();

    private long consumedBytes;
    private boolean overflow;
    private String event;

    public SseFrameParser(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * 追加一行。
     *
     * @return 完成的帧（遇到空行边界且 {@code data} 非空时），否则 {@code null}
     */
    public Frame accept(String rawLine) {
        if (overflow) {
            return null;
        }
        String line = rawLine == null ? "" : rawLine;
        // +1 计入被 BodyHandlers.fromLineSubscriber 剥掉的行分隔符（避免"每行都不超限但总量无界"）
        consumedBytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
        if (consumedBytes > maxBytes) {
            overflow = true;
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return flush();
        }
        if (trimmed.startsWith(COMMENT_PREFIX)) {
            // 注释 / 心跳行
            return null;
        }
        if (trimmed.startsWith(FIELD_EVENT)) {
            event = trimmed.substring(FIELD_EVENT.length()).trim();
            return null;
        }
        if (trimmed.startsWith(FIELD_DATA)) {
            if (data.length() > 0) {
                data.append('\n');
            }
            data.append(trimmed.substring(FIELD_DATA.length()).trim());
        }
        return null;
    }

    /**
     * 流结束时取出<b>残帧</b>（🔴 上游可能在最后一帧后直接关流而不发末尾空行）。
     */
    public Frame finish() {
        return overflow ? null : flush();
    }

    /** 🔴 累计字节是否已超 {@code mcp.sse_stream_max_bytes}。 */
    public boolean overflow() {
        return overflow;
    }

    /** 已消费字节数（供日志与断言使用）。 */
    public long consumedBytes() {
        return consumedBytes;
    }

    private Frame flush() {
        String payload = data.toString();
        String currentEvent = event;
        data.setLength(0);
        event = null;
        if (payload.isBlank() || DONE_MARKER.equals(payload)) {
            return null;
        }
        return new Frame(currentEvent, payload);
    }
}
