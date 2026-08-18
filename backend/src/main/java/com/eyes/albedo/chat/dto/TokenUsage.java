package com.eyes.albedo.chat.dto;

/**
 * token 用量摘要（{@code messages.token_usage} 的 JSON 结构）。
 *
 * <p>🔴 统计值保持 number（不做 string 化）：ADR-004 只要求 ID 类字段为 string。
 * 上游未返回用量时整个对象为 {@code null}，前端按「不显示」处理，绝不编造数字。
 *
 * @param promptTokens     提示词 token
 * @param completionTokens 生成 token
 * @param totalTokens      合计
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
}
