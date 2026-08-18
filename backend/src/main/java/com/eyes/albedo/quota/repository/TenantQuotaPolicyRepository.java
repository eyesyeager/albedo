package com.eyes.albedo.quota.repository;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.quota.entity.TenantQuotaPolicy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 租户额度策略版本流的读取入口（architecture.md §13.5.11）。
 *
 * <p>🔴 <b>租户维度由 Hibernate discriminator 自动追加</b>（实体继承 {@code BaseTenantEntity}）——
 * 因此这里<b>不写</b> {@code tenant_id} 条件，也<b>不接受</b> tenantId 入参：
 * 让调用方传 tenantId 会给"传错一个租户号即跨租户读取"留出口。
 *
 * <p>🔴 <b>只读</b>：一期策略行由 DBA 直接插入（仅追加的版本流），
 * 本接口<b>不提供</b>任何写方法 —— 避免出现"代码里删历史行"这种破坏审计解释力的入口。
 */
public interface TenantQuotaPolicyRepository extends JpaRepository<TenantQuotaPolicy, Long> {

    /**
     * 取<b>当前生效</b>的策略版本（{@code effective_at <= now} 的最新一行）。
     *
     * <p>🔴 排序必须是 {@code effective_at desc, id desc}：同一 {@code effective_at} 被唯一键
     * {@code uk_tenant_effective} 挡住，但 {@code id desc} 仍是必要的<b>确定性 tie-break</b>
     * （避免 MySQL 在等值行上返回不确定顺序）；该唯一键即最优最左前缀索引，
     * 🔴 <b>不需要</b>再建 {@code idx_tenant_effective}（冗余索引只增写入成本）。
     *
     * <p>🔴 调用方必须传 {@code Pageable = PageRequest.of(0, 1)}：只取一行。
     *
     * @param now 判定时刻（UTC；🔴 由 {@code Clock} 提供，禁止在实现里 {@code Instant.now()}）
     */
    @Query("select p from TenantQuotaPolicy p where p.effectiveAt <= :now "
            + "order by p.effectiveAt desc, p.id desc")
    List<TenantQuotaPolicy> findEffective(@Param("now") Instant now, Pageable pageable);
}
