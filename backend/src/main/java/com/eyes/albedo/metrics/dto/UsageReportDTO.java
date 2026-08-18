package com.eyes.albedo.metrics.dto;

import java.util.List;

/**
 * 用量查询响应（api-spec §7.11.1 的 {@code data}）。
 *
 * <p>🔴 <b>不是分页结构</b>（契约明确）：这是<b>固定时间序列</b>，
 * 因此不套 {@code {list,total,page,pageSize}}，也<b>禁止</b>伪装成分页 ——
 * 把时间序列塞进分页壳会让前端以为可以翻页，而 bucket 数量由 {@code from/to/granularity} 决定。
 * 这里的 {@code total} 是<b>区间合计对象</b>（不是行数）。
 *
 * <p>🔴 只回<b>聚合计数</b>：禁止返回消息正文、单条明细、uid 列表、其他租户数据。
 *
 * @param from        区间起（ISO-8601 UTC，原样回显请求值）
 * @param to          区间止
 * @param granularity {@code day} / {@code hour}
 * @param series      时间桶序列（按 bucket 升序，🔴 空桶也补零 —— 前端画图需要连续序列）
 * @param total       区间合计
 */
public record UsageReportDTO(String from,
                             String to,
                             String granularity,
                             List<UsageBucketDTO> series,
                             UsageTotalsDTO total) {
}
