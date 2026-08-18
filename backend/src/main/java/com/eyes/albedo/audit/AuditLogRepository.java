package com.eyes.albedo.audit;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 审计仓储（🔴 <b>只写不改不删</b>，architecture.md §13.5.1 / AC-AUD-001）。
 *
 * <p>为什么<b>不</b>继承 {@code JpaRepository}：那会自动带来 {@code delete*} / {@code deleteAll} /
 * {@code saveAll} 与可更新的 {@code save} 语义，使"审计不可篡改"退化为"靠人不去调用它"。
 * 本接口继承的是 Spring Data 的<b>标记接口</b> {@link Repository}，
 * 只显式暴露 {@link #save} 与若干查询方法 —— 编译期就<b>不存在</b>删除/批量更新入口。
 *
 * <p>🔴 租户维度查询必须手写 {@code where tenant_id = :tenantId}（平台表无 discriminator 保护）。
 *
 * <p>一期没有 {@code /admin/audit} 查询接口（§6 Deferred），本接口的查询方法仅供
 * 数据核验、集成测试与 @测试 的断言使用。
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /**
     * 追加一条审计事件（唯一写入入口）。
     *
     * <p>🔴 调用方必须经 {@link AuditWriter}，不要直接使用本方法：{@code AuditWriter} 承担
     * scope/tenantId 强制校验、action 白名单校验与六类禁记项脱敏。
     */
    AuditLog save(AuditLog auditLog);

    /** 按 {@code event_id} 精确查询（幂等重放保护与对外 auditEventId 对账）。 */
    Optional<AuditLog> findByEventId(String eventId);

    /**
     * 租户维度按 action 倒序查询（🔴 显式带 tenant_id 条件）。
     */
    @Query("select a from AuditLog a where a.tenantId = :tenantId and a.action = :action"
            + " order by a.occurredAt desc, a.id desc")
    List<AuditLog> findTenantEvents(@Param("tenantId") String tenantId,
                                    @Param("action") String action,
                                    Pageable pageable);

    /**
     * 平台维度按 action 倒序查询（{@code scope='platform'}，🔴 tenant_id 必须为 null）。
     */
    @Query("select a from AuditLog a where a.tenantId is null and a.action = :action"
            + " order by a.occurredAt desc, a.id desc")
    List<AuditLog> findPlatformEvents(@Param("action") String action, Pageable pageable);

    /**
     * 租户维度事件计数（数据核验用，🔴 显式带 tenant_id 条件）。
     */
    @Query("select count(a) from AuditLog a where a.tenantId = :tenantId and a.action = :action")
    long countTenantEvents(@Param("tenantId") String tenantId, @Param("action") String action);

    /**
     * 某对象上是否已存在该 action 的审计行（🔴 <b>防刷点查</b>，走 {@code idx_object}）。
     *
     * <p>唯一用途：api-spec §7.8.2 ⑤ 的 {@code tool.confirm_conflict} 去重 ——
     * 同一 {@code toolCallId} <b>至多写一条</b>冲突审计。
     *
     * <p>🔴 <b>为什么需要它</b>：confirm 接口<b>不使用</b> {@code Idempotency-Key}（§1.4），
     * 多标签页 / 网络抖动重试 / 用户连点都会重复提交；若每次冲突都写审计，
     * 一次高风险确认可能落几十条同 action 行，把真正的越权与拒绝事件淹没。
     * 冲突事实"发生过"即已完成留痕，重复刷同一冲突不增加任何信息量。
     *
     * <p>🔴 {@code audit_logs} 是<b>平台表</b>（无 discriminator），故 {@code tenant_id}
     * 必须手写在条件里，否则会跨租户命中并误判"已留痕"。
     */
    @Query("select count(a) from AuditLog a where a.tenantId = :tenantId and a.action = :action"
            + " and a.objectType = :objectType and a.objectId = :objectId")
    long countTenantObjectEvents(@Param("tenantId") String tenantId,
                                 @Param("action") String action,
                                 @Param("objectType") String objectType,
                                 @Param("objectId") String objectId);
}
