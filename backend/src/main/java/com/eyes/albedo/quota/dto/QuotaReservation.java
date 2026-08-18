package com.eyes.albedo.quota.dto;

/**
 * 一次生成尝试的<b>日额度预占凭据</b>（ADR-020 ②）。
 *
 * <p>🔴 <b>禁用额度时返回 {@link #none()} 这个 no-op 哨兵而不是 {@code null}</b>：
 * {@code null} 会让"结算 / 释放"路径处处需要判空，而漏一处判空就是一个 NPE 或一次漏释放；
 * 哨兵让 {@code settle} / {@code release} 变成无条件可调用的幂等操作。
 *
 * @param tracked       是否需要结算 / 释放（{@code false} = 每日额度未启用，全链路 no-op）
 * @param redisBacked   预占是否真的落在 Redis 预占集合里。
 *                      🔴 {@code false} = <b>Redis 降级态</b>（AR-024）：判定已退化为
 *                      "DB 已结算数 ≥ limit 即拒绝"，🔴 <b>仍不放行</b>但失去防超发能力
 *                      （超发上界 = 该用户当时的并发数，🔴 不是无限）；结算走 DB 单语句 UPSERT，
 *                      🔴 账本永不失真
 * @param tenantId      租户号（快照值，🔴 结算的原生 SQL 显式携带它）
 * @param uid           用户 uid（快照值）
 * @param reservationId 服务端生成的预占标识（ZSET member）
 * @param window        本次预占所属的额度日窗口（结算时写入账本的 quota_date / 边界快照）
 */
public record QuotaReservation(boolean tracked,
                               boolean redisBacked,
                               String tenantId,
                               long uid,
                               String reservationId,
                               QuotaWindow window) {

    /** 无需计数的哨兵（每日额度未启用 / 幂等回放 / 平台派生调用）。 */
    public static QuotaReservation none() {
        return new QuotaReservation(false, false, "", 0L, "", null);
    }
}
