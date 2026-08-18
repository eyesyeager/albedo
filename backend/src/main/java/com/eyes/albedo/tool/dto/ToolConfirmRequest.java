package com.eyes.albedo.tool.dto;

/**
 * 提交确认决定的请求体（api-spec §7.8.2）。
 *
 * <p>🔴 本接口<b>不使用</b> {@code Idempotency-Key}（§1.4）：幂等由
 * <b>资源状态机 + {@code tool_calls} 行锁</b>保证 —— 重复同一 {@code decision} 回放，
 * 相反 {@code decision} 返回 {@code 30055}。传入该请求头也会被忽略。
 *
 * @param decision 🔴 只允许 {@code allow} / {@code deny}，其他值 → {@code 10001}
 * @param reason   拒绝原因，≤200 字符；🔴 写入审计，禁含敏感值
 */
public record ToolConfirmRequest(String decision, String reason) {
}
