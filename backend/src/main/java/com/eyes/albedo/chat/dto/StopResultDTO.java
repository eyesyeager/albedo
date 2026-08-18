package com.eyes.albedo.chat.dto;

/**
 * 停止生成响应（api-spec.md §4.6.2）。
 *
 * @param messageId assistant 消息 ID（string）
 * @param status    停止后的状态；已是终态时返回其真实状态（幂等，重复调用不报错）
 */
public record StopResultDTO(String messageId, String status) {
}
