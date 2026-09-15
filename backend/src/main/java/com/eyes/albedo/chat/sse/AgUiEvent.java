package com.eyes.albedo.chat.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * AG-UI 协议事件模型（ag-ui-protocol / docs.ag-ui.com/concepts/events）。
 *
 * <p>🔴 <b>与既有自定义 SSE 契约的关键差异</b>：
 * <ul>
 *   <li>事件类型是 <b>PascalCase</b> 字符串（{@code RUN_STARTED} / {@code TEXT_MESSAGE_CONTENT} …），
 *      内嵌在 {@code data} JSON 的 {@code type} 字段里，<b>不再使用 SSE 的 {@code event:} 行</b>；
 *      因此 {@code event:} 行恒为 {@code message}（或缺省），载荷由 {@code type} 判别。</li>
 *   <li>生命周期：{@code RUN_STARTED} → … → {@code RUN_FINISHED} / {@code RUN_ERROR}。</li>
 *   <li>文本流：{@code TEXT_MESSAGE_START} → {@code TEXT_MESSAGE_CONTENT}* → {@code TEXT_MESSAGE_END}。</li>
 *   <li>推理流：{@code REASONING_START} → {@code REASONING_MESSAGE_START} → {@code REASONING_MESSAGE_CONTENT}*
 *       → {@code REASONING_MESSAGE_END} → {@code REASONING_END}。</li>
 *   <li>工具流：{@code TOOL_CALL_START} → {@code TOOL_CALL_ARGS}* → {@code TOOL_CALL_END} → {@code TOOL_CALL_RESULT}。</li>
 * </ul>
 *
 * <p>🔴 本类是 <b>纯数据模型</b>（record + 事件类型常量），不依赖任何传输细节；
 * 由 {@link AgUiWriter} 负责写出为 AG-UI 标准 SSE 帧。
 */
public final class AgUiEvent {

    private AgUiEvent() {
    }

    /** AG-UI 事件类型常量（协议规范里的 PascalCase 字面量）。 */
    public static final class Type {
        public static final String RUN_STARTED = "RUN_STARTED";
        public static final String RUN_FINISHED = "RUN_FINISHED";
        public static final String RUN_ERROR = "RUN_ERROR";
        public static final String STEP_STARTED = "STEP_STARTED";
        public static final String STEP_FINISHED = "STEP_FINISHED";
        public static final String TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
        public static final String TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
        public static final String TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
        public static final String TOOL_CALL_START = "TOOL_CALL_START";
        public static final String TOOL_CALL_ARGS = "TOOL_CALL_ARGS";
        public static final String TOOL_CALL_END = "TOOL_CALL_END";
        public static final String TOOL_CALL_RESULT = "TOOL_CALL_RESULT";
        public static final String REASONING_START = "REASONING_START";
        public static final String REASONING_MESSAGE_START = "REASONING_MESSAGE_START";
        public static final String REASONING_MESSAGE_CONTENT = "REASONING_MESSAGE_CONTENT";
        public static final String REASONING_MESSAGE_END = "REASONING_MESSAGE_END";
        public static final String REASONING_END = "REASONING_END";
        public static final String STATE_SNAPSHOT = "STATE_SNAPSHOT";
        public static final String STATE_DELTA = "STATE_DELTA";
        public static final String MESSAGES_SNAPSHOT = "MESSAGES_SNAPSHOT";
        public static final String CUSTOM = "CUSTOM";

        private Type() {
        }
    }

    /** 角色字面量（AG-UI 消息角色）。 */
    public static final class Role {
        public static final String ASSISTANT = "assistant";
        public static final String USER = "user";
        public static final String TOOL = "tool";
        public static final String REASONING = "reasoning";

        private Role() {
        }
    }

    /**
     * 生命周期：Run 开始。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunStarted(String type,
                             @JsonProperty("threadId") String threadId,
                             @JsonProperty("runId") String runId,
                             @JsonProperty("parentRunId") String parentRunId,
                             Object input) {
        public RunStarted(String threadId, String runId, String parentRunId, Object input) {
            this(Type.RUN_STARTED, threadId, runId, parentRunId, input);
        }
    }

    /**
     * 生命周期：Run 正常结束（成功或中断）。
     *
     * <p>{@code outcome} 为 {@code {type:"success"}} 或 {@code {type:"interrupt", interrupts:[...]}}。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunFinished(String type, Map<String, Object> outcome) {
        public RunFinished(Map<String, Object> outcome) {
            this(Type.RUN_FINISHED, outcome);
        }

        public static RunFinished success() {
            return new RunFinished(Map.of("type", "success"));
        }

        public static RunFinished interrupt(List<String> interrupts) {
            return new RunFinished(Map.of("type", "interrupt", "interrupts", interrupts));
        }
    }

    /**
     * 生命周期：Run 出错。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RunError(String type, String message, String code) {
        public RunError(String message, String code) {
            this(Type.RUN_ERROR, message, code);
        }
    }

    /**
     * 文本消息：开始。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TextMessageStart(String type,
                                   @JsonProperty("messageId") String messageId,
                                   String role) {
        public TextMessageStart(String messageId, String role) {
            this(Type.TEXT_MESSAGE_START, messageId, role);
        }
    }

    /**
     * 文本消息：增量分片。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TextMessageContent(String type,
                                     @JsonProperty("messageId") String messageId,
                                     String delta) {
        public TextMessageContent(String messageId, String delta) {
            this(Type.TEXT_MESSAGE_CONTENT, messageId, delta);
        }
    }

    /**
     * 文本消息：结束。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TextMessageEnd(String type, @JsonProperty("messageId") String messageId) {
        public TextMessageEnd(String messageId) {
            this(Type.TEXT_MESSAGE_END, messageId);
        }
    }

    /**
     * 工具调用：开始。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallStart(String type,
                                @JsonProperty("toolCallId") String toolCallId,
                                @JsonProperty("toolCallName") String toolCallName,
                                @JsonProperty("parentMessageId") String parentMessageId) {
        public ToolCallStart(String toolCallId, String toolCallName, String parentMessageId) {
            this(Type.TOOL_CALL_START, toolCallId, toolCallName, parentMessageId);
        }
    }

    /**
     * 工具调用：入参增量。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallArgs(String type,
                               @JsonProperty("toolCallId") String toolCallId,
                               String delta) {
        public ToolCallArgs(String toolCallId, String delta) {
            this(Type.TOOL_CALL_ARGS, toolCallId, delta);
        }
    }

    /**
     * 工具调用：入参流结束。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallEnd(String type, @JsonProperty("toolCallId") String toolCallId) {
        public ToolCallEnd(String toolCallId) {
            this(Type.TOOL_CALL_END, toolCallId);
        }
    }

    /**
     * 工具调用：执行结果。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ToolCallResult(String type,
                                 @JsonProperty("messageId") String messageId,
                                 @JsonProperty("toolCallId") String toolCallId,
                                 Object content,
                                 String role) {
        public ToolCallResult(String messageId, String toolCallId, Object content, String role) {
            this(Type.TOOL_CALL_RESULT, messageId, toolCallId, content, role);
        }
    }

    /**
     * 推理：开始（包裹整段推理）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReasoningStart(String type, @JsonProperty("messageId") String messageId) {
        public ReasoningStart(String messageId) {
            this(Type.REASONING_START, messageId);
        }
    }

    /**
     * 推理：消息开始。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReasoningMessageStart(String type,
                                        @JsonProperty("messageId") String messageId,
                                        String role) {
        public ReasoningMessageStart(String messageId) {
            this(Type.REASONING_MESSAGE_START, messageId, Role.REASONING);
        }
    }

    /**
     * 推理：增量分片。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReasoningMessageContent(String type,
                                          @JsonProperty("messageId") String messageId,
                                          String delta) {
        public ReasoningMessageContent(String messageId, String delta) {
            this(Type.REASONING_MESSAGE_CONTENT, messageId, delta);
        }
    }

    /**
     * 推理：消息结束。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReasoningMessageEnd(String type, @JsonProperty("messageId") String messageId) {
        public ReasoningMessageEnd(String messageId) {
            this(Type.REASONING_MESSAGE_END, messageId);
        }
    }

    /**
     * 推理：结束（整段推理闭合）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReasoningEnd(String type, @JsonProperty("messageId") String messageId) {
        public ReasoningEnd(String messageId) {
            this(Type.REASONING_END, messageId);
        }
    }

    /**
     * 自定义事件：承载 AG-UI 标准未覆盖的领域语义。
     *
     * <p>用于把现有契约里无法用标准事件表达的信息（如 {@code finishReason} / {@code status} /
     * {@code title} / 错误数字业务码等）透传给前端，保持向后兼容的展示能力。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Custom(String type, String name, Object value) {
        public Custom(String name, Object value) {
            this(Type.CUSTOM, name, value);
        }
    }
}
