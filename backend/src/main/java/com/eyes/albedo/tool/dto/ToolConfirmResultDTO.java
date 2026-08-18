package com.eyes.albedo.tool.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 确认决定的响应体（api-spec §7.8.2 成功响应）。
 *
 * <p>🔴 所有 ID 对外为 string（ADR-004）；{@code auditEventId} 为 <b>32 位小写 hex</b>，
 * 🔴 原样返回、<b>禁止截断</b>（api-spec §7.14 不变量第 5 条）。
 *
 * @param toolCallId   工具调用 ID
 * @param messageId    assistant 消息 ID
 * @param decision     本次提交的决定（{@code allow} / {@code deny}）
 * @param status       流转后的工具调用状态
 * @param decidedAt    决定时间（ISO-8601 UTC）
 * @param replayed     是否为重复提交的回放
 * @param auditEventId 审计事件 ID
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ToolConfirmResultDTO(String toolCallId,
                                   String messageId,
                                   String decision,
                                   String status,
                                   String decidedAt,
                                   boolean replayed,
                                   String auditEventId) {
}
