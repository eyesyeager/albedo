package com.eyes.albedo.quota.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 额度快照（api-spec §7.15.2 —— 🔴 <b>唯一形态，恰 9 键，多一键或少一键均为缺陷</b>）。
 *
 * <p>用于两处且<b>形状完全一致</b>：{@code GET /api/v1/me/quota} 的 {@code data}，
 * 以及 {@code 30070} 拒绝响应的 {@code data}（让"用尽那一刻"零延迟进入用尽态）。
 *
 * <p>🔴 <b>{@code @JsonInclude(ALWAYS)} 是契约必需</b>：全局
 * {@code default-property-inclusion: non_null} 会把 {@code limit=null} / {@code remaining=null}
 * 整键省略 → 变成 7 键，直接违反"恰 9 键"（K13）。同 {@code common/Result} 的处理。
 *
 * <p>🔴 <b>逐条"不返回"的字段（出现即缺陷）</b>：
 * <ul>
 *   <li>❌ QPM 阈值 / {@code qpmEnabled} —— 下发限流阈值等于把策略暴露给客户端，
 *       并诱导前端做本地预判（双实现）；QPM 的唯一前端输入是命中时的
 *       {@code 10005 + data.retryAfterSeconds}</li>
 *   <li>❌ {@code retryAfterSeconds} —— 日额度不是秒级可恢复</li>
 *   <li>❌ 租户本地时间字符串 / {@code quotaDate} —— 只给 UTC 绝对时间 + IANA 时区，
 *       展示由前端按 {@code timezone} 渲染（🔴 禁止用浏览器本地时区推算）</li>
 * </ul>
 *
 * <p>🔴 <b>{@code used} 与 {@code remaining} 的口径<b>刻意不同</b>（{@code used + remaining ≤ limit}
 * 是有意为之，不得判缺陷）</b>：
 * <ul>
 *   <li>{@code used} 严格 = <b>已结算</b>次数（权威来源 {@code user_daily_quota_usages.settled_count}），
 *       🔴 <b>不含</b>在途预占（PRD F-QUOTA-003 硬口径）</li>
 *   <li>{@code remaining} 采<b>含在途预占的保守口径</b> {@code max(limit − used − holds, 0)}：
 *       若把在途预占算作"还剩着"，用户会看到"还有 1 次"却在点击时立刻收到 {@code 30070}
 *       —— 显示一个点不动的按钮比少显示 1 次更糟</li>
 * </ul>
 * 🔴 因此 @测试 断言 {@code used}/{@code remaining} 必须在<b>无在途生成</b>的静止态取样。
 *
 * @param enabled     当前<b>有效策略</b>下每日限额是否启用（平台默认 + 租户覆盖解析后的结果）
 * @param limit       当前有效日总量；🔴 {@code enabled=false} 时<b>必须</b> {@code null}（禁止伪造数值上限）
 * @param used        🔴 当日<b>已结算</b>次数；{@code enabled=false} 时仍返回<b>真实</b>值（🔴 不伪造 0）
 * @param remaining   {@code enabled=true} 时 {@code = max(limit − used − 在途预占, 0)}；否则 {@code null}
 * @param status      {@code available} / {@code exhausted} / {@code unlimited}
 *                    （🔴 与 {@code enabled}/{@code remaining} 恒一致）
 * @param periodStart 当前额度日起点，ISO-8601 <b>UTC</b>
 * @param resetsAt    下一个租户当地零点，ISO-8601 <b>UTC</b>（🔴 必须晚于 {@code periodStart}）
 * @param timezone    当前租户 IANA 时区（取自 {@code tenants.timezone}）
 * @param asOf        快照计算时刻，ISO-8601 UTC
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QuotaSnapshotDTO(boolean enabled,
                               Integer limit,
                               long used,
                               Integer remaining,
                               String status,
                               String periodStart,
                               String resetsAt,
                               String timezone,
                               String asOf) {

    /** 每日额度可用。 */
    public static final String STATUS_AVAILABLE = "available";
    /** 今日额度已用尽（{@code remaining=0}）。 */
    public static final String STATUS_EXHAUSTED = "exhausted";
    /** 未启用每日额度（{@code limit}/{@code remaining} 恒 {@code null}）。 */
    public static final String STATUS_UNLIMITED = "unlimited";
}
