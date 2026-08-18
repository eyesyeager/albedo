package com.eyes.albedo.metrics.repository;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.chat.entity.Message;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 用量聚合仓储（api-spec §7.11.1，🔴 <b>只读聚合，只回计数</b>）。
 *
 * <p>🔴 <b>为什么不继承 {@code JpaRepository}</b>：本仓储只服务 {@code GET /admin/metrics/usage}，
 * 而该接口<b>禁止返回消息正文、单条明细、uid 列表</b>。继承 {@code JpaRepository} 会顺手带来
 * {@code findAll} / {@code findById} 等"能取回整行"的入口，让"只回聚合计数"退化为
 * "靠人不去调用它"。这里继承 Spring Data 的标记接口 {@link Repository}，
 * 🔴 编译期就<b>不存在</b>任何返回实体的方法。
 *
 * <p>🔴 <b>租户隔离</b>：{@code messages} 是租户表，Hibernate discriminator 会自动追加
 * {@code tenant_id}（本仓储全部为 JPQL，🔴 未使用 {@code nativeQuery} —— 原生 SQL 不会被追加租户条件）。
 *
 * <p>🔴 <b>禁止无界扫描</b>：每个方法都强制带 {@code createdAt} 区间（上层限制 ≤31 天），
 * 走 {@code messages} 的租户 + 时间索引。
 *
 * <p><b>🔴 时间桶实现</b>：用 {@code function('date_format', …)} 在<b>数据库侧</b>分桶并 group by。
 * 为什么不在 Java 侧分桶：那需要把区间内<b>每一行</b>拉回内存（31 天 × 高活跃租户可达数十万行），
 * 既违反"只回聚合计数"，也会让接口成为内存风险点。
 */
public interface UsageMetricsRepository extends Repository<Message, Long> {

    /**
     * 消息数按桶（🔴 口径：{@code role in ('user','assistant')} 且 {@code is_current=1}，
     * <b>不含</b> {@code system}/{@code tool}，api-spec §7.11.1）。
     *
     * @param bucketFormat MySQL {@code DATE_FORMAT} 模式（{@code %Y-%m-%d} 或 {@code %Y-%m-%d %H}）
     * @return 每行 {@code [bucket(String), count(Long)]}
     */
    @Query("select function('date_format', m.createdAt, :bucketFormat), count(m)"
            + " from Message m"
            + " where m.createdAt >= :from and m.createdAt < :to"
            + " and m.role in ('user','assistant') and m.isCurrent = 1"
            + " and m.deletedAt is null"
            + " group by function('date_format', m.createdAt, :bucketFormat)")
    List<Object[]> messageCountByBucket(@Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("bucketFormat") String bucketFormat);

    /**
     * 活跃用户数按桶（🔴 {@code distinct uid}；只回<b>计数</b>，绝不回 uid 列表）。
     */
    @Query("select function('date_format', m.createdAt, :bucketFormat), count(distinct m.uid)"
            + " from Message m"
            + " where m.createdAt >= :from and m.createdAt < :to"
            + " and m.role in ('user','assistant') and m.isCurrent = 1"
            + " and m.deletedAt is null"
            + " group by function('date_format', m.createdAt, :bucketFormat)")
    List<Object[]> activeUserCountByBucket(@Param("from") Instant from,
                                           @Param("to") Instant to,
                                           @Param("bucketFormat") String bucketFormat);

    /**
     * 区间级活跃用户数（🔴 <b>不能</b>由各桶累加得出）。
     *
     * <p>为什么必须单独查：{@code distinct uid} <b>不可加</b> —— 同一用户在 10 天里活跃，
     * 累加桶值会得到 10，而真实的区间活跃用户是 1。累加还会让同一区间在
     * {@code granularity=hour} 下得出比 {@code day} 大得多的数字（结果随粒度漂移，属统计错误）。
     */
    @Query("select count(distinct m.uid) from Message m"
            + " where m.createdAt >= :from and m.createdAt < :to"
            + " and m.role in ('user','assistant') and m.isCurrent = 1"
            + " and m.deletedAt is null")
    long activeUserCountTotal(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * token 用量按桶（🔴 只聚合 {@code assistant} 消息；{@code NULL} 视为 0）。
     *
     * <p>{@code token_usage} 是 JSON 列，用 MySQL 的
     * {@code JSON_EXTRACT} 取三项后求和；🔴 仍是 JPQL（实体查询），
     * 因此租户条件依然由 discriminator 自动追加。
     *
     * @return 每行 {@code [bucket, promptSum, completionSum, totalSum]}
     */
    @Query("select function('date_format', m.createdAt, :bucketFormat),"
            + " coalesce(sum(function('json_extract', m.tokenUsage, '$.promptTokens')), 0),"
            + " coalesce(sum(function('json_extract', m.tokenUsage, '$.completionTokens')), 0),"
            + " coalesce(sum(function('json_extract', m.tokenUsage, '$.totalTokens')), 0)"
            + " from Message m"
            + " where m.createdAt >= :from and m.createdAt < :to"
            + " and m.role = 'assistant' and m.isCurrent = 1 and m.deletedAt is null"
            + " group by function('date_format', m.createdAt, :bucketFormat)")
    List<Object[]> tokenUsageByBucket(@Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("bucketFormat") String bucketFormat);
}
