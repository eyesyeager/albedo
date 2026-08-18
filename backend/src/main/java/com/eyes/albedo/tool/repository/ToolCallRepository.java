package com.eyes.albedo.tool.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.tool.entity.ToolCall;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

/**
 * 工具调用仓储（{@code scope=tenant}）。
 *
 * <p>🔴 状态机的<b>唯一裁决点</b>是 {@link #findByIdForUpdate(Long)} 的行锁
 * （ADR-008 第 5 条 / AR-010 三方竞态：confirm / 等待超时 / 停止生成）。
 * 🔴 行锁必须在<b>短事务</b>内持有：严禁把工具执行期或确认等待期包进该事务，
 * 否则 confirm 接口与生成线程互等 → 死锁（ADR-010 / AR-011）。
 */
public interface ToolCallRepository extends JpaRepository<ToolCall, Long> {

    /**
     * 行锁读取（{@code SELECT … FOR UPDATE}）——状态流转的唯一裁决入口。
     *
     * <p>🔴 调用方必须处在<b>短</b>事务中，且事务内禁止任何网络调用 / SSE 写出。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select tc from ToolCall tc where tc.id = :id")
    Optional<ToolCall> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    /** 幂等落库：同一轮工具请求重复投递不重复建行（走 {@code uk_tenant_msg_call}）。 */
    Optional<ToolCall> findByMessageIdAndProviderCallId(Long messageId, String providerCallId);

    /** 会话维度稳定分页（{@code created_at ASC, id ASC}，走 {@code idx_tenant_conv_created}）。 */
    Page<ToolCall> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId, Pageable pageable);

    /** 会话 + 消息过滤（走 {@code idx_tenant_msg}）。 */
    Page<ToolCall> findByConversationIdAndMessageIdOrderByCreatedAtAscIdAsc(
            Long conversationId, Long messageId, Pageable pageable);

    /** 会话 + 状态过滤。 */
    Page<ToolCall> findByConversationIdAndStatusInOrderByCreatedAtAscIdAsc(
            Long conversationId, List<String> statuses, Pageable pageable);

    /** 某条 assistant 消息的全部工具调用（流结束时把非终态收敛为 cancelled 用）。 */
    List<ToolCall> findByMessageId(Long messageId);

    /**
     * 🔴 <b>历史消息回显专用批量查询</b>：一页消息只查一次 {@code tool_calls}。
     *
     * <p>🔴 <b>为什么必须批量</b>：历史接口一页可达 100 条消息，逐条单查即 N+1 ——
     * 会把「拉取一页历史」从 2 次查询放大到 100+ 次（§14.2 性能门禁）。
     *
     * <p>排序 {@code created_at ASC, id ASC} 与 SSE 到达顺序一致，
     * 保证「实时看到的顺序」与「刷新后看到的顺序」完全相同。
     *
     * <p>🔴 派生查询（非原生 SQL）才会被 Hibernate discriminator 追加 {@code tenant_id}（AR-003）。
     */
    List<ToolCall> findByMessageIdInOrderByCreatedAtAscIdAsc(Collection<Long> messageIds);
}
