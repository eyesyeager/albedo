package com.eyes.albedo.chat.ai;

import java.util.List;

/**
 * 上游模型消息（OpenAI 兼容格式）。
 *
 * <p>🔴 <b>单一前导 {@code system} 不变量</b>（ADR-019 ① / api-spec §7.5.2）：
 * 发往上游的整个 {@code messages} 列表中，{@code role=system} <b>全局至多 1 条，
 * 且必须位于 {@code index 0}</b>（真实上游硬约束，违反即 {@code status=400}）。
 * 因此<b>多段系统提示不得拆成多条消息</b> —— 租户段 / 历史摘要块 / 平台纪律段
 * 一律由 {@code ContextAssembler} <b>在同一条消息内</b>分块拼接（块间用
 * {@code SystemPromptBudget.SECTION_SEPARATOR}），{@code AiChatClient} 在构体前 fail-fast 断言。
 *
 * <p>M3 起额外承载<b>工具调用链</b>（api-spec §5.4.2 多轮时序 / §7.6.4 结果回灌）：
 * <pre>
 * assistant(tool_calls=[…])   ← 模型请求调用工具的那一轮（🔴 必须先回灌它）
 * tool(tool_call_id=…)        ← 每个工具的结果，逐个回灌
 * </pre>
 * 🔴 顺序不可颠倒、{@code tool_call_id} 必须与 {@code assistant.tool_calls[].id} 一一对应，
 * 否则 OpenAI 兼容上游会直接拒绝整个请求（表现为"接了工具之后对话全挂"）。
 *
 * <p>🔴 {@code role=tool} 的内容是<b>不可信外部数据</b>（PRD §8.6）：
 * 回灌前已由 {@code ToolSummaryScrubber} 脱敏、{@code ToolResultTruncator} 按字节截断；
 * 它不得改写系统提示、不得提升工具权限。
 *
 * @param role       {@code system} / {@code user} / {@code assistant} / {@code tool}
 * @param content    正文（{@code assistant} 请求工具时可为空串）
 * @param toolCallId {@code role=tool} 时必填，对应 {@link AiToolCall#id()}
 * @param toolCalls  {@code role=assistant} 且本轮请求了工具时非空
 */
public record AiMessage(String role, String content, String toolCallId,
                        List<AiToolCall> toolCalls) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public AiMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AiMessage system(String content) {
        return new AiMessage(ROLE_SYSTEM, content, null, List.of());
    }

    public static AiMessage user(String content) {
        return new AiMessage(ROLE_USER, content, null, List.of());
    }

    public static AiMessage assistant(String content) {
        return new AiMessage(ROLE_ASSISTANT, content, null, List.of());
    }

    /**
     * 模型请求工具的那一轮 assistant 消息（🔴 必须在 {@link #tool} 之前回灌）。
     */
    public static AiMessage assistantToolCalls(String content, List<AiToolCall> toolCalls) {
        return new AiMessage(ROLE_ASSISTANT, content, null, toolCalls);
    }

    /**
     * 工具结果回灌（🔴 {@code toolCallId} 必须对应模型给出的 id）。
     */
    public static AiMessage tool(String toolCallId, String content) {
        return new AiMessage(ROLE_TOOL, content, toolCallId, List.of());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
