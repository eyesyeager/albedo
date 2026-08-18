package com.eyes.albedo.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.chat.entity.Message;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 消息仓储（tenant scope）。
 *
 * <p>🔴 全部 JPQL：Hibernate 会自动追加 {@code tenant_id}；一旦改用 {@code nativeQuery=true}
 * 隔离即失效（AR-003），{@code TenantIsolationScanTest} 会静态拦截该退化。
 *
 * <p>索引对齐：历史查询走 {@code idx_tenant_conv_created(tenant_id, conversation_id, created_at, id)}。
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    Optional<Message> findByIdAndUid(Long id, Long uid);

    Optional<Message> findByUidAndIdempotencyKey(Long uid, String idempotencyKey);

    /**
     * 会话历史（对终端用户可见的角色 + 当前尝试）。
     *
     * <p>🔴 只返回 {@code user} / {@code assistant}：system / tool 正文不得下发（PRD §8.8）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL "
            + "AND m.role IN ('user','assistant') AND m.isCurrent = 1 "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    Page<Message> findVisible(@Param("conversationId") Long conversationId, Pageable pageable);

    /**
     * 会话历史（含被取代的历史尝试）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL "
            + "AND m.role IN ('user','assistant') "
            + "ORDER BY m.createdAt ASC, m.id ASC")
    Page<Message> findVisibleIncludingSuperseded(@Param("conversationId") Long conversationId,
                                                 Pageable pageable);

    /**
     * 上下文组装用：按时间<b>倒序</b>取最近 N 条已定稿消息，一次查询后在内存里反转。
     *
     * <p>🔴 禁止 N+1：整个上下文只允许这一次分页查询（性能红线 P95 ≤500ms）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL "
            + "AND m.isCurrent = 1 AND m.role IN ('user','assistant') "
            + "AND m.status IN ('sent','completed','stopped') "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findRecentForContext(@Param("conversationId") Long conversationId, Pageable pageable);

    /**
     * 上下文可用消息总数（判断是否需要摘要更早内容）。
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL "
            + "AND m.isCurrent = 1 AND m.role IN ('user','assistant') "
            + "AND m.status IN ('sent','completed','stopped')")
    long countForContext(@Param("conversationId") Long conversationId);

    /**
     * 摘要用：取<b>严格早于给定游标</b>的消息，按时间<b>倒序</b>（即「紧邻窗口者在最前」）。
     *
     * <p>🔴 <b>为什么用游标而不是下标偏移</b>：窗口在装配时会跳过空 assistant、
     * 并按长度预算提前收尾，因此「窗口内的 K 条」<b>不等于</b>倒序结果的前 K 条。
     * 旧实现用 {@code subList(windowSize, …)} 做下标算术，一旦发生上述跳过就会
     * 把仍在窗口里的消息重复计入摘要、或漏掉本该摘要的消息。
     * 以「窗口中最旧那条」为游标是唯一稳的边界。
     *
     * <p>🔴 倒序返回是<b>刻意</b>的：摘要预算有限，必须优先纳入紧邻窗口的内容
     * （对话连续性最相关），而不是最古老的那几条。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.deletedAt IS NULL "
            + "AND m.isCurrent = 1 AND m.role IN ('user','assistant') "
            + "AND m.status IN ('sent','completed','stopped') "
            + "AND (m.createdAt < :beforeCreatedAt "
            + "     OR (m.createdAt = :beforeCreatedAt AND m.id < :beforeId)) "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findEarlierForSummary(@Param("conversationId") Long conversationId,
                                       @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                       @Param("beforeId") Long beforeId,
                                       Pageable pageable);

    /**
     * 会话中仍在生成的 assistant 消息（删除会话时需先取消，EX-022）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId "
            + "AND m.role = 'assistant' AND m.status IN ('queued','streaming')")
    List<Message> findInFlight(@Param("conversationId") Long conversationId);

    /**
     * 会话首条用户消息（自动标题来源，PRD §6.5）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.role = 'user' "
            + "AND m.deletedAt IS NULL ORDER BY m.createdAt ASC, m.id ASC")
    List<Message> findFirstUserMessage(@Param("conversationId") Long conversationId, Pageable pageable);

    /**
     * 指定 assistant 消息之前最近的一条用户消息（重新生成时定位原提问）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.role = 'user' "
            + "AND m.deletedAt IS NULL AND m.id < :beforeMessageId ORDER BY m.id DESC")
    List<Message> findPrecedingUserMessage(@Param("conversationId") Long conversationId,
                                           @Param("beforeMessageId") Long beforeMessageId,
                                           Pageable pageable);

    /**
     * 指定用户消息之后的当前 assistant 尝试（幂等回放时定位原回答，EX-013）。
     */
    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.role = 'assistant' "
            + "AND m.isCurrent = 1 AND m.id > :afterMessageId ORDER BY m.id ASC")
    List<Message> findAssistantAfter(@Param("conversationId") Long conversationId,
                                     @Param("afterMessageId") Long afterMessageId,
                                     Pageable pageable);

    /**
     * 统计会话内对用户可见的消息条数（维护 {@code conversations.message_count}）。
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversationId = :conversationId "
            + "AND m.deletedAt IS NULL AND m.role IN ('user','assistant') AND m.isCurrent = 1")
    long countVisible(@Param("conversationId") Long conversationId);
}
