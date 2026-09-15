package com.eyes.albedo.chat.sse;

import com.eyes.albedo.tool.dto.ToolProgress;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link StreamSink} 的 AG-UI 实现：把既有写出语义（meta/delta/tool/error/done）
 * 翻译为 AG-UI 协议事件流。
 *
 * <p><b>翻译映射</b>：
 * <pre>
 * meta    → RUN_STARTED(threadId=conversationId, runId=assistantMessageId)
 * delta   → TEXT_MESSAGE_START* + TEXT_MESSAGE_CONTENT
 * reasoning → REASONING_START + REASONING_MESSAGE_START + REASONING_MESSAGE_CONTENT
 * tool    → TOOL_CALL_START + TOOL_CALL_ARGS + TOOL_CALL_END + Custom(tool_progress) [+ TOOL_CALL_RESULT]
 * error   → 缓存错误码/文案，由 done 时决定发 RUN_ERROR
 * done    → Custom(completion) + RUN_FINISHED（status=failed 时改发 RUN_ERROR）
 * </pre>
 *
 * <p>🔴 {@code error} + {@code done(failed)} 的组合收敛为 {@code RUN_ERROR}：
 * AG-UI 没有独立的 error 事件与 done 并存，失败即 {@code RUN_ERROR}。
 * 但为保留前端对 {@code finishReason}/{@code status}/{@code title}/{@code errorCode} 的
 * 既有展示能力，本类在收尾前<b>先发一个 {@code Custom("completion", ...)}</b> 透传这些字段，
 * 再发 {@code RUN_FINISHED} / {@code RUN_ERROR}。
 */
public class AgUiStreamSink implements StreamSink {

    private final AgUiWriter writer;

    private String assistantMessageId;
    private int errorCode;
    private String errorMessage;
    private boolean hasError;

    public AgUiStreamSink(AgUiWriter writer) {
        this.writer = writer;
    }

    @Override
    public void meta(String conversationId, String messageId, long agentVersion, String userMessageId) {
        this.assistantMessageId = messageId;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("conversationId", conversationId);
        input.put("userMessageId", userMessageId);
        input.put("agentVersion", agentVersion);
        writer.runStarted(input);
    }

    @Override
    public void delta(String text) {
        writer.textDelta(assistantMessageId, text);
    }

    @Override
    public void reasoning(String reasoning) {
        writer.reasoningDelta(assistantMessageId, reasoning);
    }

    @Override
    public void tool(ToolProgress progress) {
        writer.tool(progress, assistantMessageId);
    }

    @Override
    public void error(int code, String message) {
        this.errorCode = code;
        this.errorMessage = message;
        this.hasError = true;
    }

    @Override
    public void done(String finishReason, String messageId, String status, String title) {
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("finishReason", finishReason);
        completion.put("messageId", messageId);
        completion.put("status", status);
        completion.put("title", title);
        if (hasError) {
            completion.put("errorCode", errorCode);
            completion.put("errorMessage", errorMessage);
        }
        writer.custom("completion", completion);

        if ("failed".equals(status)) {
            String code = hasError ? String.valueOf(errorCode) : "50000";
            String message = hasError ? errorMessage : "生成失败";
            writer.runError(message, code);
        } else {
            writer.runFinished();
        }
    }

    @Override
    public void ping() {
        writer.ping();
    }

    @Override
    public boolean broken() {
        return writer.broken();
    }

    @Override
    public void complete() {
        writer.complete();
    }
}
