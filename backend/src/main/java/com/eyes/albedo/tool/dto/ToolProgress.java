package com.eyes.albedo.tool.dto;

/**
 * 一次工具调用状态流转的<b>对外可见进度</b>（api-spec §5.2 {@code tool} 事件的全部字段）。
 *
 * <p>🔴 <b>为什么放在 {@code tool} 包而不是 {@code chat.sse}</b>（architecture.md §5.1.3）：
 * {@code tool} <b>不得依赖 {@code chat}</b>（否则 chat ↔ tool 成环），因此工具侧只产出<b>中立进度对象</b>，
 * 由 {@code chat/ChatStreamRunner} 翻译成 SSE {@code tool} 帧并写出 —— {@code tool} 包永不持有 {@code SseWriter}。
 *
 * <p>🔴 <b>禁含</b>（api-spec §5.2 末尾）：工具完整入参明文、完整结果正文、消息正文、
 * {@code systemPrompt}、Skill 正文、MCP {@code endpoint}、凭据/Token。
 * {@code argsSummary} / {@code resultSummary} 必须已过 {@code ToolSummaryScrubber} 脱敏与字符截断。
 *
 * @param toolCallId         工具调用 ID（string；🔴 <b>全生命周期稳定</b>，前端按它<b>原位更新</b>卡片）
 * @param toolType           {@code local} | {@code mcp}
 * @param toolKey            工具标识（MCP 为 {@code {mcpKey}:{toolName}}）
 * @param status             {@code pending} | {@code running} |
 *                           {@code succeeded} | {@code failed} | {@code timed_out} |
 *                           {@code cancelled} | {@code denied}
 * @param round              第几轮工具调用，从 1 开始
 * @param argsSummary        入参脱敏摘要
 * @param resultSummary      结果脱敏摘要（非终态为空串）
 * @param truncated          结果是否因超 {@code tool.result_max_bytes} 被<b>字节</b>截断（EX-017）
 * @param errorCode          终态失败时的数字业务码；否则 {@code null}
 * @param retryAfterSeconds  仅限流拒绝时给出；否则 {@code null}
 */
public record ToolProgress(String toolCallId,
                           String toolType,
                           String toolKey,
                           String status,
                           int round,
                           String argsSummary,
                           String resultSummary,
                           boolean truncated,
                           Integer errorCode,
                           Integer retryAfterSeconds) {

    public ToolProgress {
        argsSummary = argsSummary == null ? "" : argsSummary;
        resultSummary = resultSummary == null ? "" : resultSummary;
    }

    /**
     * 是否为"尚未产出结果"的阶段（决定 api-spec §5.2 兼容字段 {@code summary} 取 args 还是 result）。
     */
    public boolean beforeResult() {
        return com.eyes.albedo.tool.entity.ToolCall.STATUS_PENDING.equals(status)
                || com.eyes.albedo.tool.entity.ToolCall.STATUS_RUNNING.equals(status);
    }
}
