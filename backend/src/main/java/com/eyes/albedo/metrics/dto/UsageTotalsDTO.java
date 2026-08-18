package com.eyes.albedo.metrics.dto;

/**
 * 区间合计（api-spec §7.11.1 的 {@code data.total}）。
 *
 * <p>🔴 字段与 {@link UsageBucketDTO} 完全一致<b>但没有 {@code bucket}</b>：
 * 前端可以用同一套渲染逻辑处理"某一桶"与"区间合计"。
 *
 * <p>🔴 {@code activeUserCount} 的合计口径说明（容易误解）：
 * 它是<b>区间整体</b>的 {@code distinct uid}，🔴 <b>不是</b>各桶 {@code activeUserCount} 之和 ——
 * 同一用户在多天活跃只应计一次。因此本字段由<b>独立的区间级查询</b>得出，
 * 绝不通过累加桶值计算（累加会得到远大于真实值的数字，且随粒度变化，属统计错误）。
 */
public record UsageTotalsDTO(long messageCount,
                             long conversationCount,
                             long activeUserCount,
                             UsageTokenUsageDTO tokenUsage,
                             long toolCallCount,
                             long toolFailedCount,
                             long toolDeniedCount,
                             long rateLimitedCount) {
}
