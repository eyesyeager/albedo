package com.eyes.albedo.chat.dto;

/**
 * 发送消息请求（api-spec.md §4.6.1）。
 *
 * <p>长度限制取自 {@code sys_config: chat.message_min_chars / message_max_chars}，
 * 因此这里不加 Bean Validation 的长度注解（否则上限就被写死在代码里，违反反硬编码）。
 * 空白与超长统一由 Service 判定为 {@code 30041}（EX-020 / AC-CHAT-004）。
 *
 * @param content 消息正文
 * @param agentId 仅当 {@code conversationId=new} 时有意义（缺省用默认 Agent）；
 *                已有会话<b>忽略</b>该字段——会话绑定的版本不可变（RISK-005）
 */
public record SendMessageRequest(String content, String agentId) {
}
