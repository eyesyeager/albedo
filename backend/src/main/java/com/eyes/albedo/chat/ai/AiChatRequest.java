package com.eyes.albedo.chat.ai;

import java.math.BigDecimal;
import java.util.List;

import com.eyes.albedo.tool.dto.ToolDefinition;

/**
 * 上游流式生成请求（内部结构，参数取自 Agent 版本快照）。
 *
 * @param model                 模型标识
 * @param messages              上下文消息（含系统提示、Skill 注入片段、工具调用链）
 * @param temperature           采样温度
 * @param maxOutputTokens       最大输出 token
 * @param requestTimeoutSeconds 🔴 <b>单轮</b>模型流式调用的上限（秒；ADR-017 ② 语义<b>收窄固化</b>）：
 *                              由 {@code AiChatClient.overallGuard} 逐轮生效。
 *                              🔴 它<b>不得</b>出现在任何"整条流 / 整次生成"的计算里
 *                              （含 {@code SseEmitter} timeout）—— 整流寿命由
 *                              {@code chat.generation_deadline_seconds + chat.deadline_grace_seconds}
 *                              决定；把本值当整流寿命正是 BUG-MCP-002 的根因。
 *                              🔴 实际取值 = {@code min(agent 版本值, 生成剩余预算 − 宽限)}
 *                              （由 {@code ChatStreamRunner.buildRequest} 收紧）
 * @param firstTokenTimeoutSeconds 首字超时（来自 {@code sys_config: chat.first_token_timeout_seconds}）
 * @param tools                 🔴 可下发给模型的工具清单（{@code ToolCatalogService} 四条件过滤结果）。
 *                              <b>空列表表示不下发 {@code tools} 字段</b> ——
 *                              api-spec 明确"无可用工具时不下发该字段，<b>不得下发空数组</b>"：
 *                              部分 OpenAI 兼容实现遇到 {@code "tools": []} 会直接 400
 */
public record AiChatRequest(String model,
                            List<AiMessage> messages,
                            BigDecimal temperature,
                            int maxOutputTokens,
                            int requestTimeoutSeconds,
                            int firstTokenTimeoutSeconds,
                            List<ToolDefinition> tools) {

    public AiChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 无工具场景的便捷构造（M1 行为完全不变）。
     */
    public AiChatRequest(String model, List<AiMessage> messages, BigDecimal temperature,
                         int maxOutputTokens, int requestTimeoutSeconds,
                         int firstTokenTimeoutSeconds) {
        this(model, messages, temperature, maxOutputTokens, requestTimeoutSeconds,
                firstTokenTimeoutSeconds, List.of());
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
