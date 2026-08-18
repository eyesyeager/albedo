package com.eyes.albedo.quota.dto;

/**
 * 本次准入的<b>有效额度策略</b>（平台默认 ⊕ 租户覆盖的解析结果，api-spec §7.15.5）。
 *
 * <p>🔴 <b>为什么必须是一个快照对象</b>：QPM 与日限额<b>必须来自同一次解析</b>
 * （§9.6.1 步骤 ①）。若各步骤各解析一次，会出现"QPM 取平台默认、日额度取租户覆盖"的错配，
 * 且把 1 次 DB 点查放大成 3 次。因此准入入口解析一次，把本记录传给 ②③④。
 *
 * <p>🔴 逐字段独立解析（不是整行覆盖）：租户行某列 {@code NULL} → 继承平台默认；
 * 非 {@code NULL} 合法 → 覆盖；非 {@code NULL} 非法 → {@code 50003}（🔴 禁止静默继承）。
 *
 * <p>🔴 <b>本记录不得对外下发</b>：额度快照（{@code QuotaSnapshotDTO}）刻意<b>不含</b>
 * QPM 阈值 —— 下发限流阈值等于把策略暴露给客户端并诱导前端做本地预判（双实现）。
 *
 * @param qpmEnabled       是否启用分钟窗限流（{@code false} → 准入第 3 步整步跳过）
 * @param qpmLimit         每分钟消息数上限（🔴 恒 ≥1）
 * @param dailyQuotaEnabled 是否启用每日额度（{@code false} → 不预检、不预占、不结算）
 * @param dailyQuotaLimit  每日额度总量（🔴 恒 ≥1；{@code dailyQuotaEnabled=false} 时不应被使用）
 */
public record EffectiveQuotaPolicy(boolean qpmEnabled,
                                   int qpmLimit,
                                   boolean dailyQuotaEnabled,
                                   int dailyQuotaLimit) {
}
