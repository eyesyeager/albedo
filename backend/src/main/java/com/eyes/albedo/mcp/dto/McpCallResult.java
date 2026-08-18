package com.eyes.albedo.mcp.dto;

/**
 * MCP {@code tools/call} 的成功返回（api-spec §7.6.2）。
 *
 * <p>🔴 <b>上游输出按不可信内容处理</b>（PRD §8.6 / api-spec §7.6.4）：
 * 结果只作为 {@code role=tool} 消息回灌模型，<b>不得</b>改写系统提示、
 * <b>不得</b>提升工具权限、<b>不得</b>触发未授权工具；回灌前必须先脱敏再截断
 * （顺序见 ADR-011 第 4 条）。
 *
 * <p>🔴 本记录<b>不做</b>截断：截断阈值属 {@code tool} 包的职责
 * （{@code ToolResultTruncator}，architecture.md §5.1.3），
 * {@code mcp} 包只负责"把上游文本原样取出来"。
 *
 * @param content 拼接后的文本内容（多个 {@code content[]} 元素以 {@code \n} 连接）
 * @param isError 上游是否标记为业务失败（{@code true} → {@code 30057}）
 */
public record McpCallResult(String content, boolean isError) {

    public McpCallResult {
        content = content == null ? "" : content;
    }

    public static McpCallResult ok(String content) {
        return new McpCallResult(content, false);
    }

    public static McpCallResult error(String content) {
        return new McpCallResult(content, true);
    }
}
