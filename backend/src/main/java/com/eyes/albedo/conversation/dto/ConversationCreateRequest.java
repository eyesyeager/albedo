package com.eyes.albedo.conversation.dto;

/**
 * 创建会话请求（api-spec.md §4.5.2）。
 *
 * @param agentId 目标 Agent（可空 → 使用默认 Agent）；必须是已发布且启用的 Agent
 */
public record ConversationCreateRequest(String agentId) {
}
