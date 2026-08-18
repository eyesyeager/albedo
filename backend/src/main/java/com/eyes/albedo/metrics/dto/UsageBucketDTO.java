package com.eyes.albedo.metrics.dto;

/**
 * 单个时间桶的用量（api-spec §7.11.1 {@code series[]} 元素）。
 *
 * @param bucket            桶起始时刻（ISO-8601 UTC）
 * @param messageCount      {@code role in (user, assistant)} 且 {@code isCurrent=1} 的消息数
 * @param conversationCount 桶内<b>新建</b>会话数
 * @param activeUserCount   桶内产生过消息的 {@code distinct uid} 数（🔴 只回计数）
 * @param tokenUsage        assistant 消息的 token 三项求和
 * @param toolCallCount     🔴 全部工具调用行（含 pending / denied）
 * @param toolFailedCount   {@code failed} + 执行超时（{@code 30051}/{@code 30056}）
 * @param toolDeniedCount   {@code denied} + 确认等待超时（{@code 30050}）
 * @param rateLimitedCount  🔴 <b>一期恒 0</b>（无持久化数据源，严禁伪造/估算，api-spec §7.11.1）
 */
public record UsageBucketDTO(String bucket,
                             long messageCount,
                             long conversationCount,
                             long activeUserCount,
                             UsageTokenUsageDTO tokenUsage,
                             long toolCallCount,
                             long toolFailedCount,
                             long toolDeniedCount,
                             long rateLimitedCount) {
}
