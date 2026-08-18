package com.eyes.albedo.conversation.repository;

import java.util.Optional;

import com.eyes.albedo.conversation.entity.Conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 会话仓储（tenant scope）。
 *
 * <p>🔴 每个方法都<b>显式</b>带 {@code uid} 条件：租户隔离由 Hibernate 保证，
 * 但「本人会话」是业务级水平权限，必须写在查询里，不能靠 Service 事后比较（少一处判断即越权）。
 *
 * <p>索引对齐：列表查询走 {@code idx_tenant_uid_updated(tenant_id, uid, deleted_at, updated_at, id)}，
 * 排序键 {@code updated_at DESC, id DESC} 保证稳定分页（无重复无遗漏）。
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndUidAndDeletedAtIsNull(Long id, Long uid);

    @Query("SELECT c FROM Conversation c WHERE c.uid = :uid AND c.deletedAt IS NULL "
            + "ORDER BY c.updatedAt DESC, c.id DESC")
    Page<Conversation> findMine(@Param("uid") Long uid, Pageable pageable);

    @Query("SELECT c FROM Conversation c WHERE c.uid = :uid AND c.deletedAt IS NULL "
            + "AND LOWER(c.title) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "ORDER BY c.updatedAt DESC, c.id DESC")
    Page<Conversation> searchMine(@Param("uid") Long uid,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);
}
