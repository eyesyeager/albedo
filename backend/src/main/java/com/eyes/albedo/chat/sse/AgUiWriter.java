package com.eyes.albedo.chat.sse;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 协议事件写出器。
 *
 * <p>🔴 <b>与 {@link SseWriter} 的传输差异</b>：AG-UI 事件类型内嵌在 {@code data} JSON 的
 * {@code type} 字段中，<b>不使用 SSE 的 {@code event:} 行</b>（帧形如
 * {@code data: {"type":"RUN_STARTED",...}}）。因此本类直接写 {@code data:} 帧，不设置事件名。
 *
 * <p><b>Run 生命周期状态机</b>（本类维护，调用方无需关心）：
 * <pre>
 *   idle --runStarted--> running --runFinished/runError--> closed
 * </pre>
 * 文本 / 推理消息各自维护 Start→Content*→End 的开闭状态，避免重复发 Start/End；
 * {@code runFinished} / {@code runError} 会自动闭合尚未闭合的消息。
 *
 * <p>🔴 写失败（客户端断开）不抛异常：已生成内容仍需落库（EX-015），仅标记连接失效。
 */
@Slf4j
public class AgUiWriter {

    private final SseEmitter emitter;
    private final String threadId;
    private final String runId;

    private boolean broken;
    private boolean runStarted;
    private boolean runClosed;

    /** 当前 assistant 文本消息 id（由 textDelta 首帧记录，用于自动闭合）。 */
    private String textMessageId;
    private boolean textStarted;

    /** 当前推理消息 id。 */
    private String reasoningMessageId;
    private boolean reasoningStarted;

    /** 已发 ToolCallStart 的工具调用 id（避免重复发 start）。 */
    private final java.util.Set<String> toolCallStarted = new java.util.HashSet<>();

    public AgUiWriter(SseEmitter emitter, String threadId, String runId) {
        this.emitter = emitter;
        this.threadId = threadId;
        this.runId = runId;
    }

    public boolean broken() {
        return broken;
    }

    // ===================== 生命周期 =====================

    /**
     * 发送 {@code RUN_STARTED}（幂等：一个 writer 只发一次）。
     */
    public void runStarted(Object input) {
        if (runStarted) {
            return;
        }
        runStarted = true;
        send(new AgUiEvent.RunStarted(threadId, runId, null, input));
    }

    /**
     * 发送 {@code RUN_FINISHED}（outcome=success），并自动闭合所有未闭合的消息。
     */
    public void runFinished() {
        if (runClosed) {
            return;
        }
        closeOpenMessages();
        send(AgUiEvent.RunFinished.success());
        runClosed = true;
    }

    /**
     * 发送 {@code RUN_FINISHED}（自定义 outcome，如 interrupt），并自动闭合所有未闭合的消息。
     */
    public void runFinished(AgUiEvent.RunFinished finished) {
        if (runClosed) {
            return;
        }
        closeOpenMessages();
        send(finished);
        runClosed = true;
    }

    /**
     * 发送 {@code RUN_ERROR}，并自动闭合所有未闭合的消息。
     */
    public void runError(String message, String code) {
        if (runClosed) {
            return;
        }
        closeOpenMessages();
        send(new AgUiEvent.RunError(message, code));
        runClosed = true;
    }

    // ===================== 文本消息 =====================

    /**
     * 文本增量分片（自动维护 TextMessageStart / TextMessageEnd）。
     *
     * @param messageId 本次 assistant 消息 ID（AG-UI 的文本消息 id）
     */
    public void textDelta(String messageId, String delta) {
        if (!textStarted) {
            textStarted = true;
            textMessageId = messageId;
            send(new AgUiEvent.TextMessageStart(messageId, AgUiEvent.Role.ASSISTANT));
        }
        send(new AgUiEvent.TextMessageContent(messageId, delta));
    }

    // ===================== 推理消息 =====================

    /**
     * 推理（思维链）增量分片（自动维护 ReasoningStart/MessageStart/MessageEnd/ReasoningEnd）。
     */
    public void reasoningDelta(String messageId, String delta) {
        if (!reasoningStarted) {
            reasoningStarted = true;
            reasoningMessageId = messageId;
            send(new AgUiEvent.ReasoningStart(messageId));
            send(new AgUiEvent.ReasoningMessageStart(messageId));
        }
        send(new AgUiEvent.ReasoningMessageContent(messageId, delta));
    }

    // ===================== 工具调用 =====================

    /**
     * 工具状态流转 → AG-UI 工具事件序列（🔴 由 {@code chat} 翻译 {@link ToolProgress}，
     * 与 {@link SseEvents.Tool#from} 同源，见 architecture.md §5.1.3 职责边界）。
     *
     * <p><b>映射规则</b>：
     * <ul>
     *   <li>首次见到 toolCallId（{@code pending}）→ {@code ToolCallStart} + {@code ToolCallArgs}
     *       （入参摘要）+ {@code ToolCallEnd}</li>
     *   <li>每次状态流转 → {@code Custom("tool_progress", ...)} 承载完整状态机信息
     *       （riskLevel / status / round / 确认倒计时等），供前端渲染工具卡片</li>
     *   <li>终态（succeeded/failed/denied/cancelled/timed_out）→ {@code ToolCallResult}</li>
     * </ul>
     *
     * @param progress         中立进度对象（摘要已脱敏）
     * @param assistantMessageId 本次 assistant 消息 ID（作为 parentMessageId / result messageId）
     */
    public void tool(com.eyes.albedo.tool.dto.ToolProgress progress, String assistantMessageId) {
        String toolCallId = progress.toolCallId();
        if (toolCallStarted.add(toolCallId)) {
            // 首次：发 ToolCallStart + 入参摘要 + End（AG-UI 三段式）
            send(new AgUiEvent.ToolCallStart(toolCallId, progress.toolKey(), assistantMessageId));
            String args = progress.argsSummary() == null ? "" : progress.argsSummary();
            send(new AgUiEvent.ToolCallArgs(toolCallId, args));
            send(new AgUiEvent.ToolCallEnd(toolCallId));
        }
        // 状态流转：用 Custom 事件承载完整状态机（AG-UI 标准未覆盖确认/风险等企业级语义）
        send(new AgUiEvent.Custom("tool_progress", toolProgressPayload(progress)));
        // 终态：补发 ToolCallResult
        if (isTerminal(progress.status())) {
            String result = progress.resultSummary() == null ? "" : progress.resultSummary();
            send(new AgUiEvent.ToolCallResult(assistantMessageId, toolCallId, result, AgUiEvent.Role.TOOL));
        }
    }

    private static boolean isTerminal(String status) {
        return com.eyes.albedo.tool.entity.ToolCall.STATUS_SUCCEEDED.equals(status)
                || com.eyes.albedo.tool.entity.ToolCall.STATUS_FAILED.equals(status)
                || com.eyes.albedo.tool.entity.ToolCall.STATUS_DENIED.equals(status)
                || com.eyes.albedo.tool.entity.ToolCall.STATUS_CANCELLED.equals(status)
                || com.eyes.albedo.tool.entity.ToolCall.STATUS_TIMED_OUT.equals(status);
    }

    private static java.util.Map<String, Object> toolProgressPayload(
            com.eyes.albedo.tool.dto.ToolProgress progress) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("toolCallId", progress.toolCallId());
        payload.put("toolType", progress.toolType());
        payload.put("toolKey", progress.toolKey());
        payload.put("status", progress.status());
        payload.put("round", progress.round());
        payload.put("summary", progress.beforeResult() ? progress.argsSummary() : progress.resultSummary());
        payload.put("argsSummary", progress.argsSummary());
        payload.put("resultSummary", progress.resultSummary());
        payload.put("truncated", progress.truncated());
        payload.put("errorCode", progress.errorCode());
        payload.put("retryAfterSeconds", progress.retryAfterSeconds());
        return payload;
    }

    /**
     * 工具调用开始（幂等：同一 toolCallId 只发一次 ToolCallStart）。
     *
     * @param parentMessageId 父消息 ID（本次 assistant 消息）
     */
    public void toolCallStart(String toolCallId, String toolCallName, String parentMessageId) {
        if (!toolCallStarted.add(toolCallId)) {
            return;
        }
        send(new AgUiEvent.ToolCallStart(toolCallId, toolCallName, parentMessageId));
    }

    /**
     * 工具入参（AG-UI 标准语义为 JSON 片段流；本项目模型已给完整 arguments，整段下发一次）。
     */
    public void toolCallArgs(String toolCallId, String args) {
        send(new AgUiEvent.ToolCallArgs(toolCallId, args));
    }

    /**
     * 工具调用结束（入参流闭合）。
     */
    public void toolCallEnd(String toolCallId) {
        send(new AgUiEvent.ToolCallEnd(toolCallId));
    }

    /**
     * 工具执行结果。
     */
    public void toolCallResult(String messageId, String toolCallId, Object content) {
        send(new AgUiEvent.ToolCallResult(messageId, toolCallId, content, AgUiEvent.Role.TOOL));
    }

    // ===================== 自定义事件 =====================

    /**
     * 自定义事件：承载 AG-UI 标准未覆盖的领域信息（工具状态流转 / 错误码 / 完成态等）。
     */
    public void custom(String name, Object value) {
        send(new AgUiEvent.Custom(name, value));
    }

    // ===================== 传输 =====================

    /**
     * 心跳注释帧（前端忽略）。
     */
    public void ping() {
        if (broken) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | RuntimeException e) {
            markBroken(e);
        }
    }

    private void send(Object event) {
        if (broken) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            markBroken(e);
        }
    }

    /**
     * 闭合所有未闭合的文本/推理消息（在 RunFinished / RunError 前调用，保证流结构完整）。
     */
    private void closeOpenMessages() {
        if (textStarted) {
            send(new AgUiEvent.TextMessageEnd(textMessageId));
            textStarted = false;
        }
        if (reasoningStarted) {
            send(new AgUiEvent.ReasoningMessageEnd(reasoningMessageId));
            send(new AgUiEvent.ReasoningEnd(reasoningMessageId));
            reasoningStarted = false;
        }
    }

    private void markBroken(Exception e) {
        if (broken) {
            return;
        }
        broken = true;
        log.debug("AG-UI SSE 连接已失效，停止写出但继续持久化：cause={}", e.getClass().getSimpleName());
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            log.trace("SSE emitter 二次收尾被忽略");
        }
    }

    /**
     * 结束响应（必须调用，否则 Servlet 异步上下文不会释放）。
     */
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("完成 SSE 响应时忽略异常：{}", e.getClass().getSimpleName());
        }
    }
}
