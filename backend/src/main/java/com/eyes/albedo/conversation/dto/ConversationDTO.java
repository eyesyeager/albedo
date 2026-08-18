package com.eyes.albedo.conversation.dto;

/**
 * 会话对象（api-spec.md §4.5.1）。
 *
 * <p>{@code version} 是乐观锁令牌：重命名必须回传它（§4.5.4），否则无法检测并发覆盖。
 *
 * @param conversationId 会话 ID（string，ADR-004）
 * @param title          标题
 * @param titleSource    auto / model / manual
 * @param agentId        绑定的 Agent ID（string）
 * @param agentVersion   绑定的 Agent 版本（number，创建时固定）
 * @param status         active / readOnly / deleted
 * @param messageCount   消息条数
 * @param lastMessageAt  最后一条消息时间（ISO-8601 UTC，可空）
 * @param updatedAt      更新时间（ISO-8601 UTC）
 * @param version        乐观锁版本（number）
 */
public record ConversationDTO(String conversationId,
                              String title,
                              String titleSource,
                              String agentId,
                              long agentVersion,
                              String status,
                              int messageCount,
                              String lastMessageAt,
                              String updatedAt,
                              int version) {
}
