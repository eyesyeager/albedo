package com.eyes.albedo.tool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具调用可追溯视图（api-spec §7.9.1）。
 *
 * <p>🔴 <b>字段禁含清单（AC-CHAT-007 / PRD §4 第 15 条，逐条对齐契约）</b>：
 * <pre>
 * 凭据 / 密钥 / Token / IV / 密文任何片段
 * MCP endpoint、内部 IP、端口、上游原始错误正文与堆栈
 * 消息正文（user/assistant/tool 的 content）、systemPrompt、Skill 正文
 * 完整工具入参与完整结果（🔴 只允许 §5.4.3 脱敏摘要，四处复用同一份）
 * 其他租户 / 其他用户的任何标识与存在性
 * decidedByUid 等他人身份（🔴 默认不返回）
 * </pre>
 * 因此本 DTO <b>只映射 {@code tool_calls} 的摘要列</b>，不 join 任何消息或 MCP 配置表。
 *
 * @param toolCallId           工具调用 ID（string）
 * @param messageId            所属 assistant 消息 ID（string）
 * @param round                第几轮
 * @param toolType             {@code local} | {@code mcp}
 * @param toolKey              工具标识
 * @param toolName             工具展示名快照
 * @param status               状态机取值
 * @param errorCode            终态失败时的数字业务码，否则 {@code null}
 * @param argsSummary          入参脱敏摘要
 * @param resultSummary        结果脱敏摘要
 * @param truncated            结果是否被字节截断
 * @param startedAt            开始执行时间
 * @param finishedAt           终态时间
 * @param durationMs           执行耗时
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ToolCallDTO(String toolCallId,
                          String messageId,
                          int round,
                          String toolType,
                          String toolKey,
                          String toolName,
                          String status,
                          Integer errorCode,
                          String argsSummary,
                          String resultSummary,
                          boolean truncated,
                          String startedAt,
                          String finishedAt,
                          Integer durationMs) {
}
