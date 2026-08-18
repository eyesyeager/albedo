package com.eyes.albedo.quota.dto;

import java.time.Instant;

/**
 * 一次准入的额度上下文（🔴 §9.6.1 步骤 ① 的产物，<b>一次准入只解析一次</b>）。
 *
 * <p>🔴 <b>为什么要把它显式传递</b>：若 ②③④ 各自解析一次，可能出现"QPM 取平台默认、
 * 日额度取租户覆盖"的错配，且把 1 次索引点查放大成 3 次（§9.6.1 表第 ① 行）。
 *
 * @param tenantId 租户号（快照值）
 * @param uid      用户 uid（快照值，🔴 只来自 {@code UserInfoHolder}，绝不来自请求参数）
 * @param policy   有效策略（平台默认 ⊕ 租户覆盖）
 * @param window   当前额度日窗口
 * @param asOf     本次解析时刻（🔴 全流程共用同一个"现在"，避免步骤间的时刻漂移）
 */
public record QuotaContext(String tenantId,
                           long uid,
                           EffectiveQuotaPolicy policy,
                           QuotaWindow window,
                           Instant asOf) {
}
