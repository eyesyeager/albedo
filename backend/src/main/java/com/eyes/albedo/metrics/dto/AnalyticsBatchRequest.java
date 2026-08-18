package com.eyes.albedo.metrics.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * 批量埋点上报请求（api-spec §7.10.1）。
 *
 * <p>🔴 请求体中<b>不接受</b> {@code tenantId}：租户一律由 Host 解析后<b>服务端强制补齐</b>，
 * 客户端传入一律忽略并记安全日志（EX-003）。因此本记录<b>刻意不声明</b>该字段 ——
 * Jackson 默认忽略未知属性，客户端多传也不会生效。
 *
 * @param events 事件数组（1 ~ {@code sys_config: observability.analytics_batch_max}）
 */
public record AnalyticsBatchRequest(@NotNull List<AnalyticsEventRequest> events) {
}
