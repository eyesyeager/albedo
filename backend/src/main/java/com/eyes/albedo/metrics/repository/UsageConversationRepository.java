package com.eyes.albedo.metrics.repository;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.conversation.entity.Conversation;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 会话数聚合仓储（api-spec §7.11.1 {@code conversationCount}）。
 *
 * <p>🔴 口径：时间桶内<b>新建</b>会话数（按 {@code created_at} 落桶），<b>不是</b>活跃会话数。
 * 两者差异很大且容易写错：活跃数需要 join {@code messages}，而契约明确要的是新建数。
 *
 * <p>🔴 与 {@code UsageMetricsRepository} 同规格：继承标记接口 {@link Repository}，
 * 只暴露聚合方法，编译期不存在任何返回会话实体的入口。
 */
public interface UsageConversationRepository extends Repository<Conversation, Long> {

    /**
     * 新建会话数按桶。
     *
     * @return 每行 {@code [bucket(String), count(Long)]}
     */
    @Query("select function('date_format', c.createdAt, :bucketFormat), count(c)"
            + " from Conversation c"
            + " where c.createdAt >= :from and c.createdAt < :to and c.deletedAt is null"
            + " group by function('date_format', c.createdAt, :bucketFormat)")
    List<Object[]> conversationCountByBucket(@Param("from") Instant from,
                                             @Param("to") Instant to,
                                             @Param("bucketFormat") String bucketFormat);
}
