package com.eyes.albedo.quota.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户每日额度<b>结算账本</b>（{@code scope=tenant}，architecture.md §13.5.12 / ADR-020 ②）。
 *
 * <p>🔴 <b>为什么必须有这张表（而不是只用 Redis）</b>：Redis 是<b>易失</b>存储
 * （重启 / {@code maxmemory} 驱逐 / 主从切换），只用 Redis 意味着"运维重启一次 Redis =
 * 全体用户当日额度免费重置"，而额度<b>直接对应模型调用成本</b>；且前端要展示"已用 / 总量"，
 * {@code used} 必须有可信来源。🔴 因此本表是 {@code used} 的<b>唯一权威</b>，
 * Redis 侧计数只是<b>可从本表重建的镜像</b>（§12.2）。
 *
 * <p>🔴 <b>{@code quota_date} 是租户当地日历日</b>（不是 UTC 日）；
 * {@code timezone} / {@code period_start_at} / {@code resets_at} 是<b>结算时刻的快照</b>：
 * 租户 {@code timezone} 允许被变更（PRD §8.11.4.5），只存 {@code quota_date} 会让历史行的
 * 日界线<b>无法事后复原</b>。🔴 它们不随租户改配置而回写。
 *
 * <p>🔴 <b>{@code settled_count} 只增不减</b>：释放预占是 Redis 侧动作，与账本无关，
 * 🔴 <b>禁止</b>任何 {@code UPDATE settled_count = settled_count - 1}；
 * 🔴 下调租户 {@code daily_quota_limit} 时<b>不得</b>改写本表
 * （AC-QUOTA-015"历史 used 不重算、不清零"）。
 *
 * <p>🔴 <b>隐私红线</b>：本表<b>不含</b>任何消息正文 / 标题 / 会话 ID —— 它只回答"这天用了几次"。
 *
 * <p>🔴 <b>本表无 {@code deleted_at} 且不补加</b>：账本只追加 / 累加，从不删除
 * （按 §13.5.10 体例<b>显式豁免</b> §13.2 第 4 条）。
 *
 * <p>🔴 唯一键 {@code uk_tenant_uid_date(tenant_id, uid, quota_date)} 是
 * {@code INSERT … ON DUPLICATE KEY UPDATE settled_count = settled_count + 1} 这条
 * <b>单语句原子结算</b>成立的前提（无需行锁、无需乐观锁重试）。
 */
@Getter
@Setter
@Entity
@Table(name = "user_daily_quota_usages")
public class UserDailyQuotaUsage extends BaseTenantEntity {

    /** eyesUser uid（🔴 同一 uid 在 gift / redbook 天然是两行，AC-QUOTA-002）。 */
    @Column(name = "uid", nullable = false)
    private Long uid;

    /** 🔴 <b>租户当地</b>日历日（非 UTC 日）。 */
    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    /** 结算时的租户 IANA 时区快照（供事后解释日界线）。 */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** UTC：该额度日起点（租户当地 00:00，DST 缺失该时刻时为当天实际第一个时刻）。 */
    @Column(name = "period_start_at", nullable = false)
    private Instant periodStartAt;

    /** UTC：下一租户当地零点。 */
    @Column(name = "resets_at", nullable = false)
    private Instant resetsAt;

    /** 已结算次数（🔴 只增不减）。 */
    @Column(name = "settled_count", nullable = false)
    private Integer settledCount = 0;

    @Column(name = "first_settled_at")
    private Instant firstSettledAt;

    @Column(name = "last_settled_at")
    private Instant lastSettledAt;
}
