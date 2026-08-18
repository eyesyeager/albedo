package com.eyes.albedo.metrics.dto;

/**
 * token 用量三项（api-spec §7.11.1）。
 *
 * <p>🔴 {@code NULL} 视为 0（契约明确）：早期消息或失败生成可能没有 token 统计，
 * 若原样回 null 会让前端做无意义的空值分支，也会让"合计"变成 NaN。
 */
public record UsageTokenUsageDTO(long promptTokens, long completionTokens, long totalTokens) {

    public static final UsageTokenUsageDTO ZERO = new UsageTokenUsageDTO(0L, 0L, 0L);

    public UsageTokenUsageDTO plus(UsageTokenUsageDTO other) {
        if (other == null) {
            return this;
        }
        return new UsageTokenUsageDTO(promptTokens + other.promptTokens(),
                completionTokens + other.completionTokens(),
                totalTokens + other.totalTokens());
    }
}
