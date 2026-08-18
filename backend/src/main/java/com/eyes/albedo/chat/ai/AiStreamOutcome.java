package com.eyes.albedo.chat.ai;

import java.util.List;

import com.eyes.albedo.chat.dto.TokenUsage;

/**
 * 上游流式生成结果（一轮）。
 *
 * @param finishReason 上游给出的结束原因（{@code stop} / {@code length} / {@code tool_calls} …），未知时为空串
 * @param usage        token 用量（上游未返回则为 null，🔴 不编造）
 * @param cancelled    是否因用户停止而提前结束（此时已生成内容需保存为 stopped）
 * @param toolCalls    🔴 本轮模型请求调用的工具（非空即进入下一轮工具编排；空则本次生成结束）
 */
public record AiStreamOutcome(String finishReason, TokenUsage usage, boolean cancelled,
                              List<AiToolCall> toolCalls) {

    public AiStreamOutcome {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 无工具调用的便捷构造（M1 行为完全不变，🔴 保留以免既有调用方与测试桩全部返工）。
     */
    public AiStreamOutcome(String finishReason, TokenUsage usage, boolean cancelled) {
        this(finishReason, usage, cancelled, List.of());
    }

    public boolean requestsTools() {
        return !toolCalls.isEmpty();
    }
}
