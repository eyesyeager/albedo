package com.eyes.albedo.quota.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import com.eyes.albedo.quota.entity.UserDailyQuotaUsage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户每日额度账本（architecture.md §13.5.12）。
 *
 * <p>读取路径走 JPQL（🔴 租户维度由 discriminator 自动追加）；
 * 结算路径是本项目<b>唯一登记的原生 SQL 受控例外</b>，理由与纪律见 {@link #settle}。
 */
public interface UserDailyQuotaUsageRepository extends JpaRepository<UserDailyQuotaUsage, Long> {

    /**
     * 读取某用户某额度日的账本行（🔴 {@code used} 的权威来源）。
     *
     * <p>🔴 不传 tenantId：Hibernate discriminator 自动追加 {@code tenant_id = ?}，
     * 因此"同一 uid 在 gift / redbook 互不可见"是<b>框架层</b>保证而非"记得写 where"。
     */
    @Query("select u from UserDailyQuotaUsage u where u.uid = :uid and u.quotaDate = :quotaDate")
    Optional<UserDailyQuotaUsage> findByUidAndDate(@Param("uid") long uid,
                                                   @Param("quotaDate") LocalDate quotaDate);

    /**
     * 🔴 <b>单语句原子结算</b>（{@code settled_count += 1}）。
     *
     * <p>🔴 <b>原生 SQL 受控例外（已在 architecture.md §13.5.12 与
     * {@code TenantIsolationScanTest} 白名单登记）</b>：JPA 没有 {@code ON DUPLICATE KEY UPDATE}
     * 的等价 API，而"先查再写"需要行锁或乐观锁重试 —— 结算发生在 SSE 流内（首字之后），
     * 用行锁会把短事务拉长（AR-011）。唯一键 {@code uk_tenant_uid_date} 使本语句天然原子。
     *
     * <p>🔴 <b>因此本语句必须显式携带 {@code tenant_id}</b>（Hibernate 不会为原生 SQL 追加
     * discriminator）：INSERT 列表含 {@code tenant_id}，且冲突判定用的唯一键也含
     * {@code tenant_id} → 两个租户的同一 uid 落在<b>不同行</b>，不可能互相累加。
     * 🔴 扫描测试断言的正是"该 SQL 文本包含 tenant_id"（不是简单豁免）。
     *
     * <p>🔴 {@code settled_count} 只增不减；{@code first_settled_at} 仅在 INSERT 时写入
     * （冲突分支<b>不</b>回写），{@code last_settled_at} 每次推进。
     * 🔴 {@code timezone} / {@code period_start_at} / {@code resets_at} 同样只在 INSERT 时写入 ——
     * 它们是<b>结算时刻的快照</b>，租户改时区后<b>不追溯改写历史行</b>（AR-027 ④）。
     *
     * @return 受影响行数（INSERT=1，UPDATE=2，值不变的 UPDATE=0；🔴 调用方不得据此判定成败）
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO user_daily_quota_usages
              (tenant_id, uid, quota_date, timezone, period_start_at, resets_at,
               settled_count, first_settled_at, last_settled_at, created_at, updated_at)
            VALUES
              (:tenantId, :uid, :quotaDate, :timezone, :periodStartAt, :resetsAt,
               1, :now, :now, :now, :now)
            ON DUPLICATE KEY UPDATE
              settled_count = settled_count + 1,
              last_settled_at = :now,
              updated_at = :now
            """)
    int settle(@Param("tenantId") String tenantId,
               @Param("uid") long uid,
               @Param("quotaDate") LocalDate quotaDate,
               @Param("timezone") String timezone,
               @Param("periodStartAt") Instant periodStartAt,
               @Param("resetsAt") Instant resetsAt,
               @Param("now") Instant now);
}
